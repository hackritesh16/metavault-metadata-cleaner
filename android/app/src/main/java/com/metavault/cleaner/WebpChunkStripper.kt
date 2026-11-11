package com.metavault.cleaner

import java.io.ByteArrayOutputStream

/**
 * Byte-level WebP (RIFF) metadata filter. Image, alpha, colour-profile and animation chunks are copied
 * unchanged, so pixels are NOT re-encoded.
 *
 * Removed: EXIF, XMP and C2PA chunks. The matching EXIF/XMP flags in the VP8X header are cleared and the
 *          RIFF size is rewritten. Data after the RIFF container is kept and reported.
 *
 * Pure Kotlin with no Android dependencies, so it can be unit-tested on the JVM.
 */
object WebpChunkStripper {
    class Found(
        val label: String,
        val value: String,
        val details: List<Pair<String, String>> = emptyList(),
        val isExif: Boolean = false
    )

    class Result(val bytes: ByteArray, val removed: List<Found>, val trailingBytes: Int)

    private val REMOVE = setOf("EXIF", "XMP ", "C2PA")
    private val IMAGE_CHUNKS = setOf("VP8 ", "VP8L", "ANMF")

    private fun u(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    private fun le32(d: ByteArray, i: Int): Long =
        u(d, i).toLong() or (u(d, i + 1).toLong() shl 8) or (u(d, i + 2).toLong() shl 16) or (u(d, i + 3).toLong() shl 24)

    private fun ascii(d: ByteArray, at: Int, s: String): Boolean = String(d, at, 4, Charsets.ISO_8859_1) == s

    fun looksLikeWebp(d: ByteArray): Boolean = d.size >= 12 && ascii(d, 0, "RIFF") && ascii(d, 8, "WEBP")

    /** Lists the chunks that [strip] would remove. Throws IllegalArgumentException for corrupt files. */
    fun scan(input: ByteArray): List<Found> = walk(input, null).first

    /** Returns a copy of [input] without metadata chunks. Throws IllegalArgumentException for corrupt files. */
    fun strip(input: ByteArray): Result {
        val body = ByteArrayOutputStream(input.size)
        val (removed, trailing) = walk(input, body)
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream(bodyBytes.size + 12 + trailing)
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        val riffSize = bodyBytes.size + 4 // "WEBP" + chunks
        out.write(riffSize and 0xFF)
        out.write((riffSize shr 8) and 0xFF)
        out.write((riffSize shr 16) and 0xFF)
        out.write((riffSize shr 24) and 0xFF)
        out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        out.write(bodyBytes)
        if (trailing > 0) out.write(input, input.size - trailing, trailing)
        return Result(out.toByteArray(), removed, trailing)
    }

    /** Walks the chunks after the 12-byte RIFF header; kept chunks are written to [body] when it is not null. */
    private fun walk(input: ByteArray, body: ByteArrayOutputStream?): Pair<List<Found>, Int> {
        require(looksLikeWebp(input)) { "Not a WebP file" }
        val declaredEnd = 8L + le32(input, 4)
        if (declaredEnd < 12 || declaredEnd > input.size) throw IllegalArgumentException("Corrupt WebP: bad file size")
        val end = declaredEnd.toInt()
        val found = ArrayList<Found>()
        var sawImage = false
        var pos = 12
        while (pos < end) {
            if (pos + 8 > end) throw IllegalArgumentException("Corrupt WebP: truncated chunk header")
            val type = String(input, pos, 4, Charsets.ISO_8859_1)
            val size = le32(input, pos + 4)
            if (size > end - pos - 8) throw IllegalArgumentException("Corrupt WebP: bad chunk size")
            val sz = size.toInt()
            val dataStart = pos + 8
            val chunkEnd = minOf(dataStart + sz + (sz and 1), end) // chunks are padded to an even size
            if (type in IMAGE_CHUNKS) sawImage = true
            when {
                type in REMOVE -> found += describe(type, input, dataStart, sz)
                type == "VP8X" && sz >= 10 -> {
                    val chunk = input.copyOfRange(pos, chunkEnd)
                    chunk[8] = (chunk[8].toInt() and 0xF3).toByte() // clear the EXIF (0x08) and XMP (0x04) flags
                    body?.write(chunk)
                }
                else -> body?.write(input, pos, chunkEnd - pos)
            }
            pos = chunkEnd
        }
        if (!sawImage) throw IllegalArgumentException("Corrupt WebP: no image data found")
        return found to (input.size - end)
    }

    private fun describe(type: String, d: ByteArray, start: Int, size: Int): Found = when (type) {
        "EXIF" -> Found("EXIF block", "$size bytes", isExif = true)
        "XMP " -> Found("XMP block", "$size bytes", XmpSummary.fields(String(d, start, size, Charsets.UTF_8)))
        else -> Found("C2PA content credentials", "$size bytes")
    }
}
