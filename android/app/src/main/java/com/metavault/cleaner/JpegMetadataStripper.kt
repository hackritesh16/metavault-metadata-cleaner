package com.metavault.cleaner

import java.io.ByteArrayOutputStream

/** EXIF text fields the user may set when editing a JPEG (key -> TIFF tag). */
object ExifTags {
    val editable: Map<String, Int> = linkedMapOf(
        "ImageDescription" to 0x010E, "Make" to 0x010F, "Model" to 0x0110, "Software" to 0x0131,
        "DateTime" to 0x0132, "Artist" to 0x013B, "Copyright" to 0x8298
    )
}

/**
 * Byte-level JPEG metadata removal/editing. Image data (tables, frame and scan data) is copied
 * unchanged, so pixels are NOT re-encoded.
 *
 * Kept: JFIF (APP0), ICC colour profile (APP2 "ICC_PROFILE"), Adobe colour transform (APP14).
 * Removed: all other APPn segments (EXIF, XMP, IPTC, MPF, ...), comments and any bytes after the
 * end-of-image marker (e.g. embedded motion-photo video).
 * Written: a new minimal EXIF block containing only Orientation (when not "normal", so photos do
 * not appear rotated) and the text fields passed in `edits`.
 */
object JpegMetadataStripper {
    data class Result(
        val bytes: ByteArray,
        val removedSegments: Int,
        val removedBytes: Int,
        val orientationKept: Int?,
        val editedFields: Int = 0
    )

    private const val FF = 0xFF
    private val EXIF_ID = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)
    private val ICC_ID = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)

    fun looksLikeJpeg(data: ByteArray) =
        data.size >= 4 && data[0].toInt() and 0xFF == FF && data[1].toInt() and 0xFF == 0xD8

    /** Position of the next real marker's 0xFF after entropy-coded data starting at [from]. */
    internal fun skipScan(input: ByteArray, from: Int): Int {
        var pos = from
        while (pos < input.size) {
            if (input[pos].toInt() and 0xFF == FF && pos + 1 < input.size) {
                val n = input[pos + 1].toInt() and 0xFF
                if (n == 0x00 || n in 0xD0..0xD7) { pos += 2; continue }
                break
            }
            pos++
        }
        return pos
    }

    fun strip(input: ByteArray, keepOrientation: Boolean = true, edits: Map<String, String> = emptyMap()): Result {
        require(looksLikeJpeg(input)) { "Not a JPEG file" }
        val out = ByteArrayOutputStream(input.size)
        out.write(FF); out.write(0xD8)
        var pos = 2
        var removedSegments = 0
        var orientation: Int? = null
        var sawScan = false
        var sawEoi = false

        fun u(i: Int) = input[i].toInt() and 0xFF

        loop@ while (pos < input.size) {
            if (u(pos) != FF) throw IllegalArgumentException("Corrupt JPEG: expected marker at byte $pos")
            while (pos < input.size && u(pos) == FF) pos++ // skip fill bytes
            if (pos >= input.size) break
            val marker = u(pos++)
            when (marker) {
                0xD9 -> { out.write(FF); out.write(0xD9); sawEoi = true; break@loop }
                0x01, in 0xD0..0xD8 -> { out.write(FF); out.write(marker); continue@loop }
            }
            if (pos + 2 > input.size) throw IllegalArgumentException("Corrupt JPEG: truncated segment")
            val len = (u(pos) shl 8) or u(pos + 1)
            if (len < 2 || pos + len > input.size) throw IllegalArgumentException("Corrupt JPEG: bad segment length")
            val start = pos + 2
            val end = pos + len
            val keep = when (marker) {
                0xE0, 0xEE -> true
                0xE2 -> matches(input, start, end, ICC_ID)
                0xFE -> false
                in 0xE1..0xEF -> {
                    if (marker == 0xE1 && orientation == null && matches(input, start, end, EXIF_ID)) {
                        orientation = readOrientation(input, start + EXIF_ID.size, end)
                    }
                    false
                }
                else -> true
            }
            if (keep) {
                out.write(FF); out.write(marker); out.write(input, pos, len)
            } else removedSegments++
            pos = end
            if (marker == 0xDA) {
                sawScan = true
                val dataStart = pos
                pos = skipScan(input, pos)
                out.write(input, dataStart, pos - dataStart)
            }
        }
        if (!sawScan || !sawEoi) throw IllegalArgumentException("Corrupt JPEG: missing image data or end marker")
        val trailing = input.size - pos
        var result = out.toByteArray()
        val keptOrientation = if (keepOrientation && orientation != null && orientation != 1) orientation else null
        val cleanEdits = edits.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val exif = buildExif(keptOrientation, cleanEdits)
        if (exif != null) result = insertSegment(result, exif)
        return Result(
            bytes = result,
            removedSegments = removedSegments + if (trailing > 0) 1 else 0,
            removedBytes = (input.size - result.size).coerceAtLeast(0),
            orientationKept = keptOrientation,
            editedFields = cleanEdits.size
        )
    }

    private fun matches(d: ByteArray, from: Int, to: Int, id: ByteArray): Boolean {
        if (to - from < id.size) return false
        for (i in id.indices) if (d[from + i] != id[i]) return false
        return true
    }

    /** Reads EXIF Orientation (1..8) from TIFF data at [from]; returns null if absent/invalid. */
    private fun readOrientation(d: ByteArray, from: Int, to: Int): Int? {
        val t = TiffReader(d, from, to)
        if (!t.valid()) return null
        var result: Int? = null
        t.walkIfd(t.firstIfd()) { tag, type, _, pos ->
            if (tag == 0x0112 && type == 3 && pos >= 0) { val v = t.s(pos); if (v in 1..8) result = v }
        }
        return result
    }

    /** Builds a big-endian EXIF APP1 segment, or null when there is nothing to write. */
    internal fun buildExif(orientation: Int?, fields: Map<String, String>): ByteArray? {
        class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)
        val entries = ArrayList<Entry>()
        if (orientation != null) entries += Entry(0x0112, 3, 1, byteArrayOf(0, orientation.toByte()))
        for ((key, text) in fields) {
            val tag = ExifTags.editable[key] ?: continue
            entries += Entry(tag, 2, 0, text.toByteArray(Charsets.UTF_8) + 0)
        }
        if (entries.isEmpty()) return null
        entries.sortBy { it.tag }
        val ifdSize = 2 + entries.size * 12 + 4
        val dataStart = 8 + ifdSize
        val tiff = ByteArrayOutputStream()
        fun w16(v: Int) { tiff.write(v shr 8 and 0xFF); tiff.write(v and 0xFF) }
        fun w32(v: Int) { w16(v ushr 16); w16(v and 0xFFFF) }
        tiff.write("MM".toByteArray()); w16(42); w32(8)
        w16(entries.size)
        val extra = ByteArrayOutputStream()
        for (e in entries) {
            val count = if (e.type == 2) e.data.size else e.count
            w16(e.tag); w16(e.type); w32(count)
            if (e.data.size <= 4) {
                tiff.write(e.data); repeat(4 - e.data.size) { tiff.write(0) }
            } else {
                w32(dataStart + extra.size())
                extra.write(e.data)
                if (extra.size() % 2 == 1) extra.write(0)
            }
        }
        w32(0)
        tiff.write(extra.toByteArray())
        val payload = EXIF_ID + tiff.toByteArray()
        val len = payload.size + 2
        if (len > 0xFFFF) throw IllegalArgumentException("Metadata text is too long")
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** Inserts [seg] after SOI, and after a leading JFIF APP0 if present (JFIF must come first). */
    private fun insertSegment(jpeg: ByteArray, seg: ByteArray): ByteArray {
        var at = 2
        if (jpeg.size > 6 && jpeg[2].toInt() and 0xFF == FF && jpeg[3].toInt() and 0xFF == 0xE0) {
            at = 4 + ((jpeg[4].toInt() and 0xFF) shl 8 or (jpeg[5].toInt() and 0xFF))
        }
        return jpeg.copyOfRange(0, at) + seg + jpeg.copyOfRange(at, jpeg.size)
    }
}
