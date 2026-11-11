package com.metavault.cleaner

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Byte-level PNG metadata filter. Image data and every rendering-related chunk (IHDR, PLTE, IDAT, IEND,
 * tRNS transparency, gAMA/cHRM/sRGB/iCCP colour data, pHYs, APNG animation chunks, ...) are copied unchanged,
 * so pixels are NOT re-encoded.
 *
 * Removed: tEXt / zTXt / iTXt text chunks (this is where Stable Diffusion, ComfyUI, NovelAI and similar tools
 *          store prompts, seeds and workflows; iTXt also carries XMP), eXIf (EXIF), tIME (last-modified time)
 *          and caBX (C2PA content credentials).
 * Data after the IEND chunk is kept unchanged and reported through [Result.trailingBytes].
 *
 * Pure Kotlin with no Android dependencies, so it can be unit-tested on the JVM.
 */
object PngChunkStripper {
    class Found(
        val label: String,
        val value: String,
        val details: List<Pair<String, String>> = emptyList(),
        val isExif: Boolean = false
    )

    class Result(val bytes: ByteArray, val removed: List<Found>, val trailingBytes: Int)

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val REMOVE = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME", "caBX")
    private val AI_KEYS = setOf(
        "parameters", "prompt", "workflow", "sd-metadata", "invokeai_metadata", "invokeai_graph", "dream", "negative prompt"
    )
    private const val MAX_TEXT = 4000
    private const val MAX_INFLATED = 1 shl 20

    fun looksLikePng(d: ByteArray): Boolean {
        if (d.size < 8) return false
        for (i in SIGNATURE.indices) if (d[i] != SIGNATURE[i]) return false
        return true
    }

    /** Lists the chunks that [strip] would remove. Throws IllegalArgumentException for corrupt files. */
    fun scan(input: ByteArray): List<Found> = walk(input, null).first

    /** Returns a copy of [input] without metadata chunks. Throws IllegalArgumentException for corrupt files. */
    fun strip(input: ByteArray): Result {
        val out = ByteArrayOutputStream(input.size)
        val (removed, trailing) = walk(input, out)
        return Result(out.toByteArray(), removed, trailing)
    }

    private fun u(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    private fun be32(d: ByteArray, i: Int): Int = (u(d, i) shl 24) or (u(d, i + 1) shl 16) or (u(d, i + 2) shl 8) or u(d, i + 3)

    private fun walk(input: ByteArray, out: ByteArrayOutputStream?): Pair<List<Found>, Int> {
        require(looksLikePng(input)) { "Not a PNG file" }
        out?.write(input, 0, 8)
        var pos = 8
        val found = ArrayList<Found>()
        var sawIhdr = false
        var sawIdat = false
        var sawIend = false
        while (pos < input.size) {
            if (pos + 12 > input.size) throw IllegalArgumentException("Corrupt PNG: truncated chunk")
            val len = be32(input, pos)
            if (len < 0 || len > input.size - pos - 12) throw IllegalArgumentException("Corrupt PNG: bad chunk length")
            val type = String(input, pos + 4, 4, Charsets.ISO_8859_1)
            if (!sawIhdr && type != "IHDR") throw IllegalArgumentException("Corrupt PNG: IHDR must be the first chunk")
            if (type == "IHDR") sawIhdr = true
            if (type == "IDAT") sawIdat = true
            val total = 12 + len
            if (type in REMOVE) found += describe(type, input, pos + 8, len)
            else out?.write(input, pos, total)
            pos += total
            if (type == "IEND") { sawIend = true; break }
        }
        if (!sawIhdr || !sawIdat || !sawIend) throw IllegalArgumentException("Corrupt PNG: missing image data or end chunk")
        val trailing = input.size - pos
        if (trailing > 0) out?.write(input, pos, trailing)
        return found to trailing
    }

    private fun describe(type: String, d: ByteArray, start: Int, len: Int): Found {
        val end = start + len
        return when (type) {
            "tEXt" -> {
                val z = indexOfZero(d, start, end)
                if (z < 0) textFound(latin1(d, start, len), "")
                else textFound(latin1(d, start, z - start), latin1(d, z + 1, end - z - 1))
            }
            "zTXt" -> {
                val z = indexOfZero(d, start, end)
                if (z < 0 || z + 2 > end) Found("Compressed text", "$len bytes")
                else textFound(
                    latin1(d, start, z - start),
                    inflate(d.copyOfRange(z + 2, end), Charsets.ISO_8859_1) ?: "(could not decompress)"
                )
            }
            "iTXt" -> describeItxt(d, start, end, len)
            "eXIf" -> Found("EXIF block", "$len bytes", isExif = true)
            "tIME" -> if (len == 7) Found(
                "Last modified",
                String.format(
                    Locale.US, "%04d-%02d-%02d %02d:%02d:%02d UTC",
                    (u(d, start) shl 8) or u(d, start + 1), u(d, start + 2), u(d, start + 3),
                    u(d, start + 4), u(d, start + 5), u(d, start + 6)
                )
            ) else Found("Last modified", "$len bytes")
            "caBX" -> Found("C2PA content credentials", "$len bytes")
            else -> Found(type, "$len bytes")
        }
    }

    /** iTXt layout: keyword 0, compressionFlag, compressionMethod, languageTag 0, translatedKeyword 0, text. */
    private fun describeItxt(d: ByteArray, start: Int, end: Int, len: Int): Found {
        val fallback = Found("International text", "$len bytes")
        val z = indexOfZero(d, start, end)
        if (z < 0 || z + 3 > end) return fallback
        val keyword = latin1(d, start, z - start)
        val compressed = d[z + 1].toInt() != 0
        val z2 = indexOfZero(d, z + 3, end)
        if (z2 < 0) return fallback
        val z3 = indexOfZero(d, z2 + 1, end)
        if (z3 < 0) return fallback
        val raw = d.copyOfRange(z3 + 1, end)
        val text = if (compressed) inflate(raw, Charsets.UTF_8) ?: "(could not decompress)" else String(raw, Charsets.UTF_8)
        return textFound(keyword, text)
    }

    private fun textFound(keyword: String, text: String): Found {
        if (keyword == "XML:com.adobe.xmp") return Found("XMP block", "${text.length} characters", XmpSummary.fields(text))
        val ai = keyword.lowercase(Locale.ROOT) in AI_KEYS
        return Found((if (ai) "AI \u00B7 " else "Text \u00B7 ") + keyword, text.take(MAX_TEXT))
    }

    private fun latin1(d: ByteArray, from: Int, n: Int): String = String(d, from, maxOf(n, 0), Charsets.ISO_8859_1)

    private fun indexOfZero(d: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (d[i].toInt() == 0) return i
        return -1
    }

    /** Inflates zlib data, refusing to expand beyond [MAX_INFLATED] bytes (protects against zip bombs). */
    private fun inflate(data: ByteArray, charset: java.nio.charset.Charset): String? {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished() && out.size() < MAX_INFLATED) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), charset)
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
