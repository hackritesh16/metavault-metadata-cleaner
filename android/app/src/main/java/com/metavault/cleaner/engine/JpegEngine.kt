package com.metavault.cleaner.engine

import java.io.ByteArrayOutputStream

/**
 * Lossless JPEG cleaner. Works at marker-segment level: entropy-coded image data is copied byte for byte.
 * Removes APP1 (EXIF/XMP), APP13 (IPTC/Photoshop), COM, JFIF/JFXX thumbnails, other APPn segments
 * and any bytes after EOI. Keeps ICC profile and Adobe colour-transform segments (needed to render correctly).
 */
internal object JpegEngine : BaseEngine() {
    override val format = PhotoFormat.JPEG
    override val engineName = "MetaVault JPEG lossless segment stripper"
    override val keptNotes = listOf(
        "No re-encoding: compressed image data is copied unchanged, so pixels and quality are identical.",
        "Kept on purpose (technical, needed for correct rendering): quantisation/Huffman tables, ICC colour profile, Adobe colour-transform header, JFIF density."
    )
    override val unverifiedNotes = listOf(
        "Hidden data inside the compressed image itself (e.g. watermarks, steganography) is not inspected or removed.",
        "Only the segment types listed by the inspector are detected; nothing else is claimed."
    )

    private class Seg(val marker: Int, val offset: Int, val end: Int, val dataStart: Int, val dataEnd: Int)
    private class Parsed(val segs: List<Seg>, val eoiEnd: Int)

    private fun isSof(m: Int) = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC

    private fun parse(b: ByteArray): Parsed {
        require(b.size >= 4 && b.u8(0) == 0xFF && b.u8(1) == 0xD8) { "Not a JPEG file (missing start-of-image marker)" }
        val segs = ArrayList<Seg>()
        segs += Seg(0xD8, 0, 2, 2, 2)
        var p = 2
        var sawSof = false
        var sawSos = false
        while (true) {
            require(segs.size < 100_000) { "Corrupt JPEG: too many segments" }
            require(p + 1 < b.size) { "Truncated JPEG: end-of-image marker not found" }
            require(b.u8(p) == 0xFF) { "Corrupt JPEG: expected a marker at offset $p" }
            var q = p
            while (q + 1 < b.size && b.u8(q + 1) == 0xFF) q++
            require(q + 1 < b.size) { "Truncated JPEG" }
            val m = b.u8(q + 1)
            if (m == 0xD9) {
                require(sawSof && sawSos) { "JPEG contains no image data" }
                segs += Seg(m, q, q + 2, q + 2, q + 2)
                return Parsed(segs, q + 2)
            }
            require(m != 0x00) { "Corrupt JPEG: invalid marker at offset $q" }
            if (m == 0x01 || m in 0xD0..0xD8) {
                segs += Seg(m, q, q + 2, q + 2, q + 2)
                p = q + 2
                continue
            }
            require(q + 4 <= b.size) { "Truncated JPEG segment header" }
            val len = b.be16(q + 2)
            require(len >= 2 && q + 2 + len <= b.size) { "Corrupt JPEG: segment length out of range at offset $q" }
            val dataStart = q + 4
            val dataEnd = q + 2 + len
            if (isSof(m)) {
                require(len >= 8) { "Corrupt JPEG: frame header too short" }
                sawSof = true
            }
            if (m == 0xDA) {
                sawSos = true
                var i = dataEnd
                while (i + 1 < b.size) {
                    if (b.u8(i) == 0xFF) {
                        val n = b.u8(i + 1)
                        if (n == 0x00 || n in 0xD0..0xD7) { i += 2; continue }
                        break
                    }
                    i++
                }
                require(i + 1 < b.size) { "Truncated JPEG: image data not terminated" }
                segs += Seg(m, q, i, dataStart, dataEnd)
                p = i
            } else {
                segs += Seg(m, q, dataEnd, dataStart, dataEnd)
                p = dataEnd
            }
        }
    }

    override fun analyze(b: ByteArray): Analysis {
        val p = parse(b)
        var w = 0
        var h = 0
        var orientation: Int? = null
        val fields = ArrayList<MetadataField>()
        val dg = Digest()
        for (s in p.segs) {
            val m = s.marker
            val dl = s.dataEnd - s.dataStart
            when {
                m == 0xD8 || m == 0xD9 -> {}
                m == 0xE0 -> when {
                    b.hasAscii(s.dataStart, "JFIF\u0000") && dl >= 14 -> {
                        dg.add("JFIF", b, s.dataStart, 12)
                        if (dl > 14) fields += MetadataField("Thumbnail", "Embedded JFIF thumbnail")
                    }
                    b.hasAscii(s.dataStart, "JFXX\u0000") -> fields += MetadataField("Thumbnail", "JFXX thumbnail extension")
                    else -> fields += MetadataField("Other", "Unrecognised APP0 segment")
                }
                m == 0xE1 -> when {
                    b.hasAscii(s.dataStart, "Exif\u0000\u0000") && dl >= 6 -> {
                        val info = Exif.parse(b.copyOfRange(s.dataStart + 6, s.dataEnd))
                        if (info == null) {
                            fields += MetadataField("EXIF", "EXIF block (present but unreadable)")
                        } else {
                            fields += info.fields
                            if (orientation == null) orientation = info.orientation
                        }
                    }
                    b.hasAscii(s.dataStart, "http://ns.adobe.com/xap/1.0/") -> fields += MetadataField("XMP", "XMP packet")
                    b.hasAscii(s.dataStart, "http://ns.adobe.com/xmp/extension/") -> fields += MetadataField("XMP", "XMP extension packet")
                    else -> fields += MetadataField("Other", "Unrecognised APP1 segment")
                }
                m == 0xE2 ->
                    if (b.hasAscii(s.dataStart, "ICC_PROFILE\u0000")) dg.add("ICC", b, s.offset, s.end - s.offset)
                    else fields += MetadataField("Other", "APP2 segment (e.g. multi-picture data)")
                m == 0xED -> fields += MetadataField("IPTC", "Photoshop/IPTC block (APP13)")
                m == 0xEE ->
                    if (b.hasAscii(s.dataStart, "Adobe")) dg.add("ADOBE", b, s.offset, s.end - s.offset)
                    else fields += MetadataField("Other", "Unrecognised APP14 segment")
                m in 0xE3..0xEF -> fields += MetadataField("Other", "APP${m - 0xE0} segment")
                m == 0xFE -> {
                    val text = String(b, s.dataStart, minOf(dl, 120), Charsets.ISO_8859_1).filter { it >= ' ' }
                    fields += MetadataField("Comment", "JPEG comment", text)
                }
                else -> {
                    if (isSof(m) && w == 0) {
                        h = b.be16(s.dataStart + 1)
                        w = b.be16(s.dataStart + 3)
                    }
                    dg.add("J" + m.toString(16), b, s.offset, s.end - s.offset)
                }
            }
        }
        require(w > 0 && h > 0) { "Unsupported JPEG: image size is not stored in the frame header" }
        if (p.eoiEnd < b.size) {
            fields += MetadataField("Other", "Data after end of image (${b.size - p.eoiEnd} bytes)")
        }
        return Analysis(w, h, fields, orientation, dg.finish())
    }

    override fun rewrite(b: ByteArray, a: Analysis): ByteArray {
        val p = parse(b)
        val out = ByteArrayOutputStream(b.size)
        val orient = a.orientation
        var pending = orient != null && orient in 2..8

        fun flushExif() {
            if (!pending) return
            pending = false
            val tiff = Exif.minimalOrientation(orient!!)
            val len = 2 + 6 + tiff.size
            out.write(0xFF); out.write(0xE1); out.write(len shr 8); out.write(len and 0xFF)
            out.write("Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
            out.write(tiff)
        }
        fun copy(s: Seg) = out.write(b, s.offset, s.end - s.offset)

        for (s in p.segs) {
            val m = s.marker
            val dl = s.dataEnd - s.dataStart
            when {
                m == 0xD8 -> copy(s)
                m == 0xE0 -> if (b.hasAscii(s.dataStart, "JFIF\u0000") && dl >= 14) {
                    // Rewrite JFIF without any embedded thumbnail.
                    out.write(0xFF); out.write(0xE0); out.write(0x00); out.write(0x10)
                    out.write(b, s.dataStart, 12)
                    out.write(0); out.write(0)
                }
                m == 0xE2 && b.hasAscii(s.dataStart, "ICC_PROFILE\u0000") -> { flushExif(); copy(s) }
                m == 0xEE && b.hasAscii(s.dataStart, "Adobe") -> { flushExif(); copy(s) }
                m in 0xE0..0xEF || m == 0xFE -> {}
                else -> { flushExif(); copy(s) }
            }
        }
        return out.toByteArray()
    }
}
