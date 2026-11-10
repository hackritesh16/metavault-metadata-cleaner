package com.metavault.cleaner

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Byte-level PNG metadata removal/editing. Pixel chunks are copied unchanged (no re-encode).
 * Kept: critical chunks plus colour/animation chunks needed for correct display.
 * Removed: text (tEXt/zTXt/iTXt incl. XMP), eXIf, tIME, unknown ancillary chunks and anything
 * after IEND. Written: tEXt/iTXt chunks for the fields in `edits`.
 */
object PngMetadataStripper {
    data class Result(val bytes: ByteArray, val removedChunks: Int, val removedBytes: Int, val editedFields: Int)

    private val KEEP = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs", "acTL", "fcTL", "fdAT", "cICP")

    fun strip(input: ByteArray, edits: Map<String, String> = emptyMap()): Result {
        require(MetadataReader.isPng(input)) { "Not a PNG file" }
        val out = ByteArrayOutputStream(input.size)
        out.write(input, 0, 8)
        fun be(i: Int) = ((input[i].toInt() and 0xFF).toLong() shl 24) or ((input[i + 1].toInt() and 0xFF).toLong() shl 16) or
            ((input[i + 2].toInt() and 0xFF).toLong() shl 8) or (input[i + 3].toInt() and 0xFF).toLong()
        val cleanEdits = edits.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        var pos = 8; var removed = 0; var sawIhdr = false; var sawIdat = false; var sawIend = false
        while (pos + 12 <= input.size) {
            val len = be(pos)
            if (len < 0 || pos + 12L + len > input.size) throw IllegalArgumentException("Corrupt PNG: bad chunk length")
            val type = String(input, pos + 4, 4, Charsets.ISO_8859_1)
            val total = 12 + len.toInt()
            if (!sawIhdr && type != "IHDR") throw IllegalArgumentException("Corrupt PNG: IHDR missing")
            val keep = type in KEEP || type[0].isUpperCase()
            if (keep) {
                out.write(input, pos, total)
                if (type == "IHDR") { sawIhdr = true; cleanEdits.forEach { (k, v) -> out.write(textChunk(k, v)) } }
                if (type == "IDAT") sawIdat = true
            } else removed++
            pos += total
            if (type == "IEND") { sawIend = true; break }
        }
        if (!sawIhdr || !sawIdat || !sawIend) throw IllegalArgumentException("Corrupt PNG: missing image data or IEND")
        val trailing = input.size - pos
        val bytes = out.toByteArray()
        return Result(bytes, removed + if (trailing > 0) 1 else 0, (input.size - bytes.size).coerceAtLeast(0), cleanEdits.size)
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        fun w32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) o.write((v shr s and 0xFF).toInt()) }
        w32(data.size.toLong()); val t = type.toByteArray(Charsets.ISO_8859_1); o.write(t); o.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data); w32(crc.value)
        return o.toByteArray()
    }

    /** tEXt when the value fits Latin-1, otherwise uncompressed iTXt (UTF-8). */
    private fun textChunk(key: String, value: String): ByteArray {
        val k = key.toByteArray(Charsets.ISO_8859_1)
        return if (value.all { it.code <= 0xFF && it != '\u0000' }) chunk("tEXt", k + 0 + value.toByteArray(Charsets.ISO_8859_1))
        else chunk("iTXt", k + byteArrayOf(0, 0, 0, 0, 0) + value.toByteArray(Charsets.UTF_8))
    }
}
