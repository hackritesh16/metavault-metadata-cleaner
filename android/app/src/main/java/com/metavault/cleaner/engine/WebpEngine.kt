package com.metavault.cleaner.engine

import java.io.ByteArrayOutputStream

/**
 * Lossless WebP cleaner at RIFF-chunk level. Drops EXIF, XMP and unknown chunks, clears the matching VP8X flags
 * and rewrites the RIFF size. VP8/VP8L/ALPH/ANIM/ANMF/ICCP data is copied unchanged.
 */
internal object WebpEngine : BaseEngine() {
    override val format = PhotoFormat.WEBP
    override val engineName = "MetaVault WebP lossless chunk stripper"
    override val keptNotes = listOf(
        "No re-encoding: image, alpha and animation chunks are copied unchanged.",
        "Kept on purpose (technical): VP8X canvas header, ICC profile, ALPH, ANIM/ANMF and the image data chunk."
    )
    override val unverifiedNotes = listOf(
        "Hidden data inside the compressed image data is not inspected or removed.",
        "Unknown RIFF chunks are dropped and reported, but their meaning is not interpreted."
    )

    private val KEEP = setOf("ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L")
    private const val FLAG_EXIF = 0x08
    private const val FLAG_XMP = 0x04

    private class Chunk(val fourcc: String, val offset: Int, val dataStart: Int, val len: Int) {
        val end: Int get() = dataStart + len + (len and 1)
    }
    private class Parsed(val chunks: List<Chunk>, val riffEnd: Int)

    private fun parse(b: ByteArray): Parsed {
        require(b.size >= 20 && b.hasAscii(0, "RIFF") && b.hasAscii(8, "WEBP")) { "Not a WebP file" }
        val riffEnd = b.le32(4) + 8
        require(riffEnd >= 20 && riffEnd <= b.size) { "Truncated WebP: RIFF size exceeds file size" }
        val limit = riffEnd.toInt()
        val chunks = ArrayList<Chunk>()
        var p = 12
        while (p < limit) {
            require(chunks.size < 100_000) { "Corrupt WebP: too many chunks" }
            require(p + 8 <= limit) { "Corrupt WebP: truncated chunk header" }
            val lenL = b.le32(p + 4)
            require(lenL <= limit - p - 8) { "Corrupt WebP: chunk length out of range at offset $p" }
            val len = lenL.toInt()
            require(p + 8 + len + (len and 1) <= limit) { "Corrupt WebP: chunk padding out of range" }
            chunks += Chunk(String(b, p, 4, Charsets.ISO_8859_1), p, p + 8, len)
            p += 8 + len + (len and 1)
        }
        require(chunks.isNotEmpty()) { "WebP contains no chunks" }
        return Parsed(chunks, limit)
    }

    private fun chunkBytes(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.ISO_8859_1))
        val n = data.size
        out.write(n and 0xFF); out.write((n shr 8) and 0xFF); out.write((n shr 16) and 0xFF); out.write((n shr 24) and 0xFF)
        out.write(data)
        if (n and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    override fun analyze(b: ByteArray): Analysis {
        val p = parse(b)
        val first = p.chunks[0]
        val w: Int
        val h: Int
        when (first.fourcc) {
            "VP8X" -> {
                require(first.len >= 10) { "Corrupt WebP: VP8X chunk too short" }
                w = 1 + b.le24(first.dataStart + 4)
                h = 1 + b.le24(first.dataStart + 7)
            }
            "VP8L" -> {
                require(first.len >= 5 && b.u8(first.dataStart) == 0x2F) { "Corrupt WebP: invalid VP8L header" }
                val bits = b.le32(first.dataStart + 1)
                w = (bits and 0x3FFF).toInt() + 1
                h = ((bits shr 14) and 0x3FFF).toInt() + 1
            }
            "VP8 " -> {
                require(first.len >= 10 && b.u8(first.dataStart + 3) == 0x9D && b.u8(first.dataStart + 4) == 0x01 &&
                    b.u8(first.dataStart + 5) == 0x2A) { "Corrupt WebP: invalid VP8 header" }
                w = b.le16(first.dataStart + 6) and 0x3FFF
                h = b.le16(first.dataStart + 8) and 0x3FFF
            }
            else -> throw IllegalArgumentException("Unsupported WebP layout (first chunk is '${first.fourcc}')")
        }
        require(w > 0 && h > 0) { "Corrupt WebP: invalid image size" }
        require(p.chunks.any { it.fourcc == "VP8 " || it.fourcc == "VP8L" || it.fourcc == "ANMF" }) { "WebP contains no image data" }

        val fields = ArrayList<MetadataField>()
        var orientation: Int? = null
        val dg = Digest()
        for (c in p.chunks) {
            when {
                c.fourcc == "VP8X" -> {
                    require(c.len >= 10) { "Corrupt WebP: VP8X chunk too short" }
                    dg.addInt("VP8X-flags", b.u8(c.dataStart) and (0x20 or 0x10 or 0x02))
                    dg.add("VP8X-canvas", b, c.dataStart + 4, 6)
                }
                c.fourcc in KEEP -> dg.add(c.fourcc, b, c.dataStart, c.len)
                c.fourcc == "EXIF" -> {
                    val info = Exif.parse(Exif.payload(b, c.dataStart, c.len))
                    if (info == null) {
                        fields += MetadataField("EXIF", "EXIF block (present but unreadable)")
                    } else {
                        fields += info.fields
                        if (orientation == null) orientation = info.orientation
                    }
                }
                c.fourcc == "XMP " -> fields += MetadataField("XMP", "XMP packet")
                else -> fields += MetadataField("Other", "Unrecognised chunk '${c.fourcc}'")
            }
        }
        if (p.riffEnd < b.size) fields += MetadataField("Other", "Data after RIFF container (${b.size - p.riffEnd} bytes)")
        return Analysis(w, h, fields, orientation, dg.finish())
    }

    override fun rewrite(b: ByteArray, a: Analysis): ByteArray {
        val p = parse(b)
        val orient = a.orientation
        // Orientation can only be kept when a VP8X header exists to carry the EXIF flag.
        val keepOrientation = orient != null && orient in 2..8 && p.chunks[0].fourcc == "VP8X"
        val body = ByteArrayOutputStream()
        for (c in p.chunks) {
            when {
                c.fourcc == "VP8X" -> {
                    val data = b.copyOfRange(c.dataStart, c.dataStart + c.len)
                    var flags = data[0].toInt() and (FLAG_EXIF or FLAG_XMP).inv()
                    if (keepOrientation) flags = flags or FLAG_EXIF
                    data[0] = flags.toByte()
                    body.write(chunkBytes("VP8X", data))
                }
                c.fourcc in KEEP -> body.write(b, c.offset, c.end - c.offset)
            }
        }
        if (keepOrientation) body.write(chunkBytes("EXIF", Exif.minimalOrientation(orient!!)))
        val size = 4 + body.size()
        val out = ByteArrayOutputStream(8 + size)
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        out.write(size and 0xFF); out.write((size shr 8) and 0xFF); out.write((size shr 16) and 0xFF); out.write((size shr 24) and 0xFF)
        out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        out.write(body.toByteArray())
        return out.toByteArray()
    }
}
