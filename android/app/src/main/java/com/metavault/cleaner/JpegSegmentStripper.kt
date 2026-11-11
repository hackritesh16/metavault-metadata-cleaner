package com.metavault.cleaner

import java.io.ByteArrayOutputStream

/**
 * Byte-level JPEG metadata filter. Every segment that is not removed (quantisation/Huffman tables,
 * frame header, scan headers and entropy-coded data) is copied unchanged, so pixels are NOT re-encoded.
 *
 * Kept:    JFIF (APP0), ICC colour profile (APP2), Adobe colour transform (APP14) and all image-data segments.
 * Removed: EXIF (APP1, incl. GPS, maker notes and embedded thumbnail), XMP (APP1, incl. extended XMP),
 *          IPTC/Photoshop (APP13), C2PA/JUMBF content credentials (APP11), comments (COM),
 *          JFXX thumbnails (APP0 extension) and every other APPn block (MPF, FlashPix, vendor data, ...).
 * Written:  a minimal EXIF block that holds only the Orientation tag, and only when the original photo was
 *           rotated (orientation 2..8), so the cleaned photo does not appear sideways.
 * Data stored after the end-of-image marker (for example a motion-photo video) is kept unchanged and
 * reported through [Result.trailingBytes]; it is never silently discarded.
 *
 * Pure Kotlin with no Android dependencies, so it can be unit-tested on the JVM.
 */
object JpegSegmentStripper {
    enum class Kind(val label: String) {
        EXIF("EXIF block"),
        XMP("XMP block"),
        IPTC("IPTC / Photoshop block"),
        C2PA("C2PA content credentials"),
        COMMENT("JPEG comment"),
        OTHER_APP("Other app block")
    }

    /** A metadata segment found in the file. [text] is only set for comments; [details] only for XMP. */
    class Found(
        val kind: Kind,
        val bytes: Int,
        val text: String? = null,
        val details: List<Pair<String, String>> = emptyList()
    ) {
        val label: String get() = kind.label
        val summary: String get() = text?.takeIf { it.isNotBlank() } ?: "$bytes bytes"
        val isExif: Boolean get() = kind == Kind.EXIF
    }

    class Result(val bytes: ByteArray, val removed: List<Found>, val trailingBytes: Int)

    private const val FF = 0xFF
    private val EXIF_ID = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
    private val JFXX_ID = "JFXX\u0000".toByteArray(Charsets.ISO_8859_1)
    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val XMP_EXT_ID = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val ICC_ID = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)

    fun looksLikeJpeg(data: ByteArray): Boolean =
        data.size >= 4 && (data[0].toInt() and 0xFF) == FF && (data[1].toInt() and 0xFF) == 0xD8

    /** Lists the metadata segments that [strip] would remove. Throws IllegalArgumentException for corrupt files. */
    fun scan(input: ByteArray): List<Found> = walk(input, null).first

    /**
     * Returns a copy of [input] without the metadata segments. Pass the photo's EXIF [orientation] (1..8)
     * to keep it. Throws IllegalArgumentException for corrupt files.
     */
    fun strip(input: ByteArray, orientation: Int? = null): Result {
        val out = ByteArrayOutputStream(input.size)
        val (removed, trailing) = walk(input, out)
        var bytes = out.toByteArray()
        if (orientation != null && orientation in 2..8) bytes = insertAfterJfif(bytes, orientationExif(orientation))
        return Result(bytes, removed, trailing)
    }

    private fun u(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    /** Walks the JPEG; writes kept bytes to [out] when it is not null. Returns removed segments and trailing byte count. */
    private fun walk(input: ByteArray, out: ByteArrayOutputStream?): Pair<List<Found>, Int> {
        require(looksLikeJpeg(input)) { "Not a JPEG file" }
        out?.write(FF)
        out?.write(0xD8)
        val found = ArrayList<Found>()
        var pos = 2
        var sawScan = false
        var sawEoi = false

        loop@ while (pos < input.size) {
            if (u(input, pos) != FF) throw IllegalArgumentException("Corrupt JPEG: expected marker at byte $pos")
            while (pos < input.size && u(input, pos) == FF) pos++ // skip fill bytes
            if (pos >= input.size) break
            val marker = u(input, pos++)
            if (marker == 0xD9) {
                out?.write(FF)
                out?.write(0xD9)
                sawEoi = true
                break@loop
            }
            if (marker == 0x01 || marker in 0xD0..0xD8) { // standalone markers without a length
                out?.write(FF)
                out?.write(marker)
                continue@loop
            }
            if (pos + 2 > input.size) throw IllegalArgumentException("Corrupt JPEG: truncated segment")
            val len = (u(input, pos) shl 8) or u(input, pos + 1)
            if (len < 2 || pos + len > input.size) throw IllegalArgumentException("Corrupt JPEG: bad segment length")
            val start = pos + 2
            val end = pos + len
            val drop = classify(input, marker, start, end)
            if (drop != null) {
                found += drop
            } else {
                out?.write(FF)
                out?.write(marker)
                out?.write(input, pos, len)
            }
            pos = end
            if (marker == 0xDA) { // start of scan: copy entropy-coded data untouched
                val dataStart = pos
                pos = skipScan(input, pos)
                out?.write(input, dataStart, pos - dataStart)
                sawScan = true
            }
        }
        if (!sawScan || !sawEoi) throw IllegalArgumentException("Corrupt JPEG: missing image data or end marker")
        val trailing = input.size - pos
        if (trailing > 0) out?.write(input, pos, trailing)
        return found to trailing
    }

    /** Returns a [Found] when the segment is metadata that must be removed, or null when it is kept. */
    private fun classify(d: ByteArray, marker: Int, start: Int, end: Int): Found? {
        val size = end - start
        return when {
            marker == 0xFE -> Found(
                Kind.COMMENT, size,
                String(d, start, minOf(size, 300), Charsets.UTF_8).trim { it.isWhitespace() || it == '\u0000' }
            )
            marker == 0xE0 && matches(d, start, end, JFXX_ID) -> Found(Kind.OTHER_APP, size)
            marker == 0xE0 || marker == 0xEE -> null // JFIF, Adobe colour transform
            marker == 0xE1 && matches(d, start, end, EXIF_ID) -> Found(Kind.EXIF, size)
            marker == 0xE1 && matches(d, start, end, XMP_ID) -> Found(
                Kind.XMP, size,
                details = XmpSummary.fields(String(d, start + XMP_ID.size, size - XMP_ID.size, Charsets.UTF_8))
            )
            marker == 0xE1 && matches(d, start, end, XMP_EXT_ID) -> Found(Kind.XMP, size)
            marker == 0xE2 && matches(d, start, end, ICC_ID) -> null
            marker == 0xEB -> if (containsAscii(d, start, minOf(end, start + 200), "jumb") ||
                containsAscii(d, start, minOf(end, start + 200), "c2pa")
            ) Found(Kind.C2PA, size) else Found(Kind.OTHER_APP, size)
            marker == 0xED -> Found(Kind.IPTC, size)
            marker in 0xE1..0xEF -> Found(Kind.OTHER_APP, size)
            else -> null
        }
    }

    private fun matches(d: ByteArray, from: Int, to: Int, id: ByteArray): Boolean {
        if (to - from < id.size) return false
        for (i in id.indices) if (d[from + i] != id[i]) return false
        return true
    }

    private fun containsAscii(d: ByteArray, from: Int, to: Int, s: String): Boolean {
        val id = s.toByteArray(Charsets.ISO_8859_1)
        var i = from
        while (i + id.size <= to) {
            if (matches(d, i, i + id.size, id)) return true
            i++
        }
        return false
    }

    /** Position of the next real marker's 0xFF after entropy-coded data starting at [from]. */
    private fun skipScan(input: ByteArray, from: Int): Int {
        var pos = from
        while (pos < input.size) {
            if (u(input, pos) == FF && pos + 1 < input.size) {
                val n = u(input, pos + 1)
                if (n == 0x00 || n in 0xD0..0xD7) { pos += 2; continue } // stuffed byte or restart marker
                break
            }
            pos++
        }
        return pos
    }

    /** Builds a big-endian EXIF APP1 segment that contains only the Orientation tag. */
    private fun orientationExif(orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // "MM", 42, offset of the first IFD
            0x00, 0x01,                                     // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, // tag 0x0112 (Orientation), SHORT, count 1
            0x00, orientation.toByte(), 0x00, 0x00,         // value
            0x00, 0x00, 0x00, 0x00                          // no next IFD
        )
        val payload = EXIF_ID + tiff
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + payload
    }

    /** Inserts [seg] after SOI, and after a leading JFIF APP0 if present (JFIF must come first). */
    private fun insertAfterJfif(jpeg: ByteArray, seg: ByteArray): ByteArray {
        var at = 2
        if (jpeg.size > 6 && u(jpeg, 2) == FF && u(jpeg, 3) == 0xE0) at = 4 + ((u(jpeg, 4) shl 8) or u(jpeg, 5))
        if (at > jpeg.size) at = 2
        return jpeg.copyOfRange(0, at) + seg + jpeg.copyOfRange(at, jpeg.size)
    }
}
