package com.metavault.cleaner

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal fun transformPdf(context: Context,input: File,output: File,plan: TransformPlan,active: ()->Unit): String {
    PDFBoxResourceLoader.init(context)
    fun memory() = MemoryUsageSetting.setupMixed(8L shl 20,256L shl 20).setTempDir(output.parentFile)
    PDDocument.load(input,memory()).use { doc ->
        require(!doc.isEncrypted && doc.signatureDictionaries.isEmpty()) { "Encrypted and signed PDFs are unsupported." }
        val pages = doc.numberOfPages
        require(pages in 1..2000) { "PDF exceeds the 2000-page limit or has no pages." }
        if (plan.pdf==Output.TXT) {
            val stripper = PDFTextStripper()
            output.bufferedWriter().use { writer ->
                for(page in 1..pages) {
                    active()
                    stripper.startPage=page
                    stripper.endPage=page
                    stripper.writeText(doc,writer)
                    writer.flush()
                    check(output.length() <= 32L shl 20) { "Extracted text is too large." }
                }
            }
            require(output.useLines { lines -> lines.any { it.isNotBlank() } }) { "No searchable text in this PDF. Scans require OCR." }
            return "$pages pages · extracted text, layout and images omitted"
        }
        // Compress only unfiltered streams; keep encoded images, fonts and page objects intact.
        var total = 0L
        for(obj in doc.document.objects) {
            active()
            val stream = obj.`object` as? COSStream ?: continue
            if (stream.filters!=null || stream.length !in 128..(16L shl 20)) continue
            val scratch = File.createTempFile("pdf-stream-", ".tmp", output.parentFile)
            try {
                stream.createRawInputStream().use { source -> scratch.outputStream().use { total += source.copyTo(it) } }
                require(total <= 128L shl 20) { "PDF stream limit exceeded." }
                scratch.inputStream().use { data -> stream.createOutputStream(COSName.FLATE_DECODE).use { data.copyTo(it) } }
            } finally { scratch.delete() }
        }
        active()
        doc.save(output)
        PDDocument.load(output,memory()).use { require(it.numberOfPages==pages) { "PDF verification failed." } }
        if (output.length()>=input.length()) input.copyTo(output,true)
        return "$pages pages · lossless PDF optimization"
    }
}

internal fun transformOffice(input: File,output: File,extension: String,active: ()->Unit): String {
    val part = when(extension) { "docx" -> "word/document.xml"; "xlsx" -> "xl/workbook.xml"; "pptx" -> "ppt/presentation.xml"; else -> error("Unsupported Office package.") }
    ZipFile(input).use { zip ->
        require(zip.size()<=20000 && zip.getEntry(part)!=null && zip.getEntry("[Content_Types].xml")!=null) { "Invalid or oversized Office document." }
        var expanded=0L
        ZipOutputStream(output.outputStream().buffered()).use { out ->
            out.setLevel(9)
            val buf=ByteArray(32768)
            for(entry in zip.entries()) {
                active()
                require(!entry.name.startsWith("_xmlsignatures/",true)) { "Signed Office documents are unsupported." }
                out.putNextEntry(ZipEntry(entry.name))
                val crc=CRC32()
                zip.getInputStream(entry).use { stream ->
                    while(true) {
                        active()
                        val n=stream.read(buf)
                        if (n<0) break
                        expanded+=n
                        require(expanded <= 256L shl 20) { "Expanded Office document exceeds 256 MB." }
                        crc.update(buf,0,n)
                        out.write(buf,0,n)
                    }
                }
                require(crc.value==entry.crc) { "Office entry failed its checksum." }
                out.closeEntry()
            }
        }
        ZipFile(output).use { check ->
            require(check.size()==zip.size() && zip.entries().asSequence().all { check.getEntry(it.name)?.crc==it.crc })
        }
    }
    if (output.length()>=input.length()) input.copyTo(output,true)
    return "Lossless Office optimization · embedded contents preserved"
}
