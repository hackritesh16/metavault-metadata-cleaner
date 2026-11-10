package com.metavault.cleaner.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Lossless PNG cleaner at chunk level. Only an allow-list of chunks is kept; text chunks (tEXt/zTXt/iTXt),
 * tIME, eXIf and unknown ancillary chunks are dropped. IDAT is copied unchanged. CRCs are verified on read.
 */
internal object PngEngine : BaseEngine() {
    override val format = PhotoFormat.PNG
    override val engineName = "MetaVault PNG lossless chunk stripper"
    override val keptNotes = listOf(
        "No re-encoding: IDAT image data is copied unchanged, so pixels and transparency are identical.",
        "Kept on purpose (technical): IHDR, PLTE, IDAT, IEND, tRNS, gAMA, cHRM, sRGB, iCCP, sBIT, bKGD, pHYs, cICP, mDCv, cLLi and APNG animation chunks."
    )
    override val unverifiedNotes = listOf(
        "Hidden data inside the compressed pixel data (e.g. steganography) is not inspected or removed.",
        "Free-text inside kept chunks such as an iCCP profile description is not inspected."
    )

    private val KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs",
        "cICP", "mDCv", "cLLi", "acTL", "fcTL", "fdAT"
    )
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private class Chunk(val type: String, val offset: Int, val dataStart: Int, val len: Int) {
        val end: Int get() = dataStart + len + 4
    }
    private class Parsed(val chunks: List<Chunk>, val end: Int)

    private fun parse(b: ByteArray): Parsed {
        require(b.size >= 20 && SIGNATURE.indices.all { b[it] == SIGNATURE[it] }) { "Not a PNG file" }
        val chunks = ArrayList<Chunk>()
        var p = 8
        while (true) {
            require(chunks.size < 100_000) { "Corrupt PNG: too many chunks" }
            require(p + 12 <= b.size) { "Truncated PNG: IEND chunk not found" }
            val lenL = b.be32(p)
            require(lenL <= b.size - p - 12) { "Corrupt PNG: chunk length out of range at offset $p" }
            val len = lenL.toInt()
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            require(type.all { it in 'A'..'Z' || it in 'a'..'z' }) { "Corrupt PNG: invalid chunk type at offset ${p + 4}" }
            val crc = CRC32()
            crc.update(b, p + 4, 4 + len)
            require(crc.value == b.be32(p + 8 + len)) { "Corrupt PNG: CRC mismatch in $type chunk" }
            if (chunks.isEmpty()) require(type == "IHDR" && len == 13) { "Corrupt PNG: first chunk is not IHDR" }
            chunks += Chunk(type, p, p + 8, len)
            p += 12 + len
            if (type == "IEND") return Parsed(chunks, p)
        }
    }

    private fun keyword(b: ByteArray, c: Chunk): String {
        var n = 0
        while (n < c.len && n < 79 && b[c.dataStart + n] != 0.toByte()) n++
        return String(b, c.dataStart, n, Charsets.ISO_8859_1).filter { it >= ' ' }
    }

    private fun chunkBytes(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        return ByteBuffer.allocate(12 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(data.size).put(t).put(data).putInt(crc.value.toInt()).array()
    }

    override fun analyze(b: ByteArray): Analysis {
        val p = parse(b)
        val ihdr = p.chunks[0]
        val w = b.be32(ihdr.dataStart).toInt()
        val h = b.be32(ihdr.dataStart + 4).toInt()
        require(w > 0 && h > 0) { "Corrupt PNG: invalid image size" }
        require(p.chunks.any { it.type == "IDAT" }) { "PNG contains no image data" }
        val fields = ArrayList<MetadataField>()
        var orientation: Int? = null
        val dg = Digest()
        for (c in p.chunks) {
            when {
                c.type in KEEP -> dg.add(c.type, b, c.dataStart, c.len)
                c.type == "tEXt" || c.type == "zTXt" || c.type == "iTXt" ->
                    fields += MetadataField("Text", "${c.type}: ${keyword(b, c)}")
                c.type == "tIME" -> fields += MetadataField("Timestamp", "Last-modified time (tIME)")
                c.type == "eXIf" -> {
                    val info = Exif.parse(Exif.payload(b, c.dataStart, c.len))
                    if (info == null) {
                        fields += MetadataField("EXIF", "EXIF block (present but unreadable)")
                    } else {
                        fields += info.fields
                        if (orientation == null) orientation = info.orientation
                    }
                }
                c.type[0].isUpperCase() -> throw IllegalArgumentException("Unsupported critical PNG chunk ${c.type}")
                else -> fields += MetadataField("Other", "Ancillary chunk ${c.type}")
            }
        }
        if (p.end < b.size) fields += MetadataField("Other", "Data after IEND (${b.size - p.end} bytes)")
        return Analysis(w, h, fields, orientation, dg.finish())
    }

    override fun rewrite(b: ByteArray, a: Analysis): ByteArray {
        val p = parse(b)
        val out = ByteArrayOutputStream(b.size)
        out.write(SIGNATURE)
        val orient = a.orientation
        var pending = orient != null && orient in 2..8
        for (c in p.chunks) {
            if (c.type !in KEEP) continue
            if (pending && c.type == "IDAT") {
                pending = false
                out.write(chunkBytes("eXIf", Exif.minimalOrientation(orient!!)))
            }
            out.write(b, c.offset, c.end - c.offset)
        }
        return out.toByteArray()
    }
}
