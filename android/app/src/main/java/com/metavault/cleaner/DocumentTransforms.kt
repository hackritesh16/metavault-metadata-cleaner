package com.metavault.cleaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal fun transformPdf(
    context: Context,
    input: File,
    output: File,
    plan: TransformPlan,
    active: () -> Unit,
): String {
    PDFBoxResourceLoader.init(context)
    fun memory() =
        MemoryUsageSetting.setupMixed(8L shl 20, 256L shl 20).setTempDir(output.parentFile)
    PDDocument.load(input, memory()).use { doc ->
        require(!doc.isEncrypted && doc.signatureDictionaries.isEmpty()) {
            "Encrypted and signed PDFs are unsupported."
        }
        val pages = doc.numberOfPages
        require(pages in 1..2000) { "PDF exceeds the 2000-page limit or has no pages." }
        if (plan.pdf == Output.TXT) {
            val stripper =
                object : PDFTextStripper() {
                    override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                        active()
                        require(output.length() < 32L shl 20) { "Extracted text exceeds 32 MB." }
                        super.startPage(page)
                    }
                }
            output.bufferedWriter().use { stripper.writeText(doc, it) }
            require(output.length() <= 32L shl 20) { "Extracted text exceeds 32 MB." }
            require(output.useLines { lines -> lines.any { it.isNotBlank() } }) {
                "No searchable text in this PDF. Scans require OCR."
            }
            return "$pages pages · extracted text, layout and images omitted"
        }
        // Compress only unfiltered streams; keep encoded images, fonts and page objects intact.
        var total = 0L
        var photos = 0
        for (obj in doc.document.objects) {
            active()
            val stream = obj.`object` as? COSStream ?: continue
            if (
                plan.compressPdfPhotos &&
                    stream.getCOSName(COSName.SUBTYPE) == COSName.IMAGE &&
                    stream.getCOSName(COSName.FILTER) == COSName.DCT_DECODE &&
                    stream.getCOSName(COSName.COLORSPACE) == COSName.DEVICERGB &&
                    stream.getInt(COSName.BITS_PER_COMPONENT) == 8 &&
                    !stream.containsKey(COSName.DECODE_PARMS) &&
                    !stream.containsKey(COSName.DECODE) &&
                    stream.length in 256..(8L shl 20)
            ) {
                if (compressPdfPhoto(stream, input.length(), plan, output.parentFile!!, active))
                    photos++
                continue
            }
            if (stream.filters != null || stream.length !in 128..(16L shl 20)) continue
            val scratch = File.createTempFile("pdf-stream-", ".tmp", output.parentFile)
            try {
                stream.createRawInputStream().use { source ->
                    scratch.outputStream().use { total += source.copyTo(it) }
                }
                require(total <= 128L shl 20) { "PDF stream limit exceeded." }
                scratch.inputStream().use { data ->
                    stream.createOutputStream(COSName.FLATE_DECODE).use { data.copyTo(it) }
                }
            } finally {
                scratch.delete()
            }
        }
        active()
        doc.save(output)
        PDDocument.load(output, memory()).use {
            require(it.numberOfPages == pages) { "PDF verification failed." }
        }
        if (output.length() >= input.length()) {
            input.copyTo(output, true)
            return "$pages pages · original PDF was already smaller"
        }
        return if (photos > 0) "$pages pages · $photos embedded photos recompressed"
        else "$pages pages · lossless PDF optimization"
    }
}

private fun compressPdfPhoto(
    stream: COSStream,
    sourceBytes: Long,
    plan: TransformPlan,
    dir: File,
    active: () -> Unit,
): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    stream.createRawInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (
        bounds.outWidth != stream.getInt(COSName.WIDTH) ||
            bounds.outHeight != stream.getInt(COSName.HEIGHT) ||
            bounds.outWidth.toLong() * bounds.outHeight !in 1..4_000_000
    )
        return false
    val bitmap =
        stream.createRawInputStream().use { BitmapFactory.decodeStream(it) } ?: return false
    val scratch = File.createTempFile("photo-", ".jpg", dir)
    val best = File.createTempFile("photo-best-", ".jpg", dir)
    try {
        val budget =
            plan.target?.let {
                (stream.length * (it.toDouble() / sourceBytes)).toLong().coerceAtLeast(1)
            }
        searchQuality(
            plan.quality,
            budget,
            { quality ->
                active()
                scratch.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it))
                }
                scratch.length()
            },
            { scratch.copyTo(best, true) },
        )
        if (best.length() >= stream.length) return false
        best.inputStream().use { src -> stream.createRawOutputStream().use { src.copyTo(it) } }
        return true
    } finally {
        bitmap.recycle()
        scratch.delete()
        best.delete()
    }
}

internal fun transformOffice(
    input: File,
    output: File,
    extension: String,
    active: () -> Unit,
): String {
    val part =
        when (extension) {
            "docx" -> "word/document.xml"
            "xlsx" -> "xl/workbook.xml"
            "pptx" -> "ppt/presentation.xml"
            else -> error("Unsupported Office package.")
        }
    ZipFile(input).use { zip ->
        require(
            zip.size() <= 20000 &&
                zip.getEntry(part) != null &&
                zip.getEntry("[Content_Types].xml") != null
        ) {
            "Invalid or oversized Office document."
        }
        var expanded = 0L
        ZipOutputStream(output.outputStream().buffered()).use { out ->
            out.setLevel(9)
            val buf = ByteArray(32768)
            for (entry in zip.entries()) {
                active()
                require(!entry.name.startsWith("_xmlsignatures/", true)) {
                    "Signed Office documents are unsupported."
                }
                out.putNextEntry(ZipEntry(entry.name))
                val crc = CRC32()
                zip.getInputStream(entry).use { stream ->
                    while (true) {
                        active()
                        val n = stream.read(buf)
                        if (n < 0) break
                        expanded += n
                        require(expanded <= 256L shl 20) {
                            "Expanded Office document exceeds 256 MB."
                        }
                        crc.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                require(crc.value == entry.crc) { "Office entry failed its checksum." }
                out.closeEntry()
            }
        }
        ZipFile(output).use { check ->
            require(
                check.size() == zip.size() &&
                    zip.entries().asSequence().all { check.getEntry(it.name)?.crc == it.crc }
            )
        }
    }
    if (output.length() >= input.length()) input.copyTo(output, true)
    return "Lossless Office optimization · embedded contents preserved"
}
