package com.metavault.cleaner

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Offsets and lengths refer to payload bytes, including any identifying signature. */
internal data class MetadataBlock(val name: String, val type: String, val offset: Long, val size: Long)
internal data class BlockIndex(val blocks: List<MetadataBlock>, val warning: String?)

/** Uses positional reads so lazy pages and exports can share a read-only snapshot. */
internal fun FileChannel.readRange(offset: Long, count: Int): ByteArray {
    require(offset >= 0 && count >= 0 && offset <= size() - count)
    val bytes = ByteArray(count)
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
        if (read(buffer, offset + buffer.position()) <= 0) throw IOException("Could not read the complete block.")
    }
    return bytes
}

internal class MetadataBlockIndex(private val file: FileChannel, private val checkActive: () -> Unit = {}) {
    private val end = file.size()
    private val blocks = mutableListOf<MetadataBlock>()
    private var windowStart = -1L
    private var window = byteArrayOf()
    private var records = 0

    fun scan(): BlockIndex {
        val warning = try {
            when {
                matches(0, "\u00ff\u00d8") -> jpeg()
                matches(0, "\u0089PNG\r\n\u001a\n") -> png()
                matches(0, "RIFF") && matches(8, "WEBP") -> webp()
                else -> return BlockIndex(emptyList(), "This file has no supported JPEG, PNG or WebP signature.")
            }
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
        return BlockIndex(blocks, warning)
    }

    private fun record() {
        checkActive()
        require(++records <= 500_000) { "Scan stopped at the container record limit." }
    }

    private fun byte(pos: Long): Int {
        require(pos in 0 until end) { "The file ends inside a container record." }
        if (pos < windowStart || pos >= windowStart + window.size) {
            checkActive()
            windowStart = pos
            window = file.readRange(pos, minOf(end - pos, 64 * 1024L).toInt())
        }
        return window[(pos - windowStart).toInt()].toInt() and 255
    }

    private fun matches(pos: Long, text: String, available: Long = end - pos): Boolean =
        text.length <= available && text.indices.all { byte(pos + it) == text[it].code }

    private fun code(pos: Long) = (0..3).map { byte(pos + it).toChar() }.joinToString("")

    private fun uint32(pos: Long, order: ByteOrder): Long {
        val indices = if (order == ByteOrder.BIG_ENDIAN) 0..3 else 3 downTo 0
        var value = 0L
        for (i in indices) value = (value shl 8) or byte(pos + i).toLong()
        return value
    }

    private fun add(name: String, type: String, offset: Long, size: Long) {
        require(blocks.size < 8192) { "Scan stopped after 8192 metadata blocks." }
        blocks += MetadataBlock(name, type, offset, size)
    }

    private fun jpeg() {
        var pos = 2L
        var entropy = false
        while (true) {
            record()
            if (entropy) while (byte(pos) != 255) pos++
            require(byte(pos++) == 255) { "Invalid JPEG marker at byte ${pos - 1}." }
            while (byte(pos) == 255) pos++
            val marker = byte(pos++)
            if (entropy && (marker == 0 || marker in 0xd0..0xd7)) continue
            if (marker == 0xd9) return
            if (marker == 1 || marker in 0xd0..0xd7) continue
            require(marker != 0 && marker != 0xd8) { "Unexpected JPEG marker at byte ${pos - 1}." }
            val length = (byte(pos) shl 8) + byte(pos + 1)
            require(length >= 2 && length <= end - pos) { "Invalid JPEG segment length at byte $pos." }
            val start = pos + 2
            val size = length - 2L
            if (marker in 0xe0..0xef) {
                val type = "APP${marker - 0xe0}"
                val name = when {
                    matches(start, "Exif\u0000\u0000", size) -> "EXIF"
                    matches(start, "http://ns.adobe.com/xap/1.0/\u0000", size) -> "XMP"
                    matches(start, "http://ns.adobe.com/xmp/extension/\u0000", size) -> "Extended XMP"
                    matches(start, "ICC_PROFILE\u0000", size) -> "ICC Profile"
                    matches(start, "JFIF\u0000", size) -> "JFIF"
                    matches(start, "JFXX\u0000", size) -> "JFIF thumbnail"
                    matches(start, "Photoshop 3.0\u0000", size) -> "Photoshop / IPTC"
                    matches(start, "Adobe", size) -> "Adobe"
                    marker == 0xeb && matches(start, "JP", size) -> "JUMBF"
                    else -> "Unknown Block"
                }
                add(name, type, start, size)
            } else if (marker == 0xfe) add("Comment", "COM", start, size)
            else if (marker in 0xf0..0xfd || marker == 0xc8) add("Unknown Block", "JPEG 0x${marker.toString(16)}", start, size)
            pos += length
            entropy = marker == 0xda || (entropy && marker == 0xdc)
        }
    }

    private fun png() {
        var pos = 8L
        while (true) {
            record()
            require(end - pos >= 12) { "PNG ends before a complete chunk or IEND." }
            val size = uint32(pos, ByteOrder.BIG_ENDIAN)
            val type = code(pos + 4)
            require(type.all { it in 'A'..'Z' || it in 'a'..'z' }) { "Invalid PNG chunk type at byte $pos." }
            require(size <= end - pos - 12) { "Truncated PNG $type chunk." }
            val start = pos + 8
            when (type) {
                "IEND" -> { require(size == 0L) { "Invalid PNG IEND length." }; return }
                "IHDR", "PLTE", "IDAT", "acTL", "fcTL", "fdAT" -> Unit
                "tEXt", "zTXt", "iTXt" -> add(
                    if (matches(start, "XML:com.adobe.xmp\u0000", size)) "XMP" else "PNG text", type, start, size)
                "eXIf" -> add("EXIF", type, start, size)
                "iCCP" -> add("ICC Profile", type, start, size)
                "tIME" -> add("Modification time", type, start, size)
                "caBX" -> add("JUMBF", type, start, size)
                else -> add(pngNames[type] ?: "Unknown Block", type, start, size)
            }
            pos = start + size + 4 // Skip the stored CRC. This viewer does not validate pixel data.
        }
    }

    private fun webp() {
        val limit = 8 + uint32(4, ByteOrder.LITTLE_ENDIAN)
        require(limit >= 12) { "Invalid WebP RIFF size." }
        var pos = 12L
        while (pos < limit) {
            record()
            require(minOf(end, limit) - pos >= 8) { "Truncated WebP chunk header." }
            val type = code(pos)
            require(type.all { it.code in 32..126 }) { "Invalid WebP chunk type." }
            val size = uint32(pos + 4, ByteOrder.LITTLE_ENDIAN)
            val start = pos + 8
            val padded = size + size % 2
            require(padded <= minOf(end, limit) - start) { "Truncated WebP $type chunk." }
            when (type) {
                "VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF" -> Unit
                "EXIF" -> add("EXIF", type, start, size)
                "XMP " -> add("XMP", type, start, size)
                "ICCP" -> add("ICC Profile", type, start, size)
                "C2PA" -> add("C2PA", type, start, size)
                else -> add("Unknown Block", type, start, size)
            }
            pos = start + padded
        }
    }
}

private val pngNames = mapOf("gAMA" to "Image gamma", "cHRM" to "Chromaticity", "sRGB" to "Color space",
    "pHYs" to "Pixel dimensions", "sBIT" to "Significant bits", "bKGD" to "Background color",
    "hIST" to "Palette histogram", "tRNS" to "Transparency", "sPLT" to "Suggested palette")
