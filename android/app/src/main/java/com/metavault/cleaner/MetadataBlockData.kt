package com.metavault.cleaner

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.InflaterInputStream

internal const val BLOCK_PAGE = 256
internal const val SNAPSHOT_LIMIT = 256L * 1024 * 1024

/** The private snapshot is unlinked on open (including if Android kills the process). */
internal fun snapshotChannel(directory: File): FileChannel {
    val temp = File.createTempFile("mv-blocks-", ".tmp", directory)
    return try {
        FileChannel.open(temp.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)
    } catch (e: Exception) {
        temp.delete()
        throw e
    }
}

internal fun FileChannel.snapshot(input: InputStream, checkActive: () -> Unit, limit: Long = SNAPSHOT_LIMIT) {
    val bytes = ByteArray(64 * 1024)
    while (true) {
        checkActive()
        val count = input.read(bytes)
        if (count < 0) return
        require(position() + count <= limit) { "This file exceeds the viewer’s 256 MiB limit." }
        val buffer = ByteBuffer.wrap(bytes, 0, count)
        while (buffer.hasRemaining()) write(buffer)
    }
}

internal fun FileChannel.exportBlock(block: MetadataBlock, output: OutputStream, checkActive: () -> Unit) {
    var written = 0L
    while (written < block.size) {
        checkActive()
        val bytes = readRange(block.offset + written, minOf(64 * 1024L, block.size - written).toInt())
        output.write(bytes)
        written += bytes.size
    }
}

private const val DIGITS = "0123456789ABCDEF"

internal fun ByteArray.blockDump(ascii: Boolean, columns: Int = 8): String = buildString {
    this@blockDump.forEachIndexed { i, b ->
        if (i > 0) {
            if (i % columns == 0) append('\n') else if (!ascii) append(' ')
        }
        val n = b.toInt() and 255
        if (ascii) append(if (n in 32..126) n.toChar() else '.')
        else append(DIGITS[n ushr 4]).append(DIGITS[n and 15])
    }
}

/** Limit decoded text separately from the raw byte view, which covers the entire block. */
internal fun FileChannel.blockPreview(block: MetadataBlock): String? {
    val bytes = readRange(block.offset, minOf(block.size, 65536L).toInt())
    fun text(start: Int, charset: java.nio.charset.Charset = Charsets.UTF_8): String? {
        if (start !in bytes.indices) return null
        val length = minOf(bytes.size - start, 4096)
        return String(bytes, start, length, charset).trimEnd('\u0000').takeIf { it.isNotBlank() }
    }
    fun u(i: Int) = bytes[i].toInt() and 255
    fun short(i: Int) = u(i) * 256 + u(i + 1)
    fun zero(start: Int) = (start until bytes.size).firstOrNull { bytes[it] == 0.toByte() }
    return when {
        block.name == "EXIF" -> tiffPreview(bytes)
        block.type == "C2PA" || block.type == "caBX" -> C2paReader.fields(bytes, max = 24)
            .takeIf { it.isNotEmpty() }?.joinToString("\n") { (key, value) -> "${key.take(160)}: $value" }
        block.type in setOf("tEXt", "zTXt", "iTXt") -> {
            val keyEnd = zero(0) ?: return null
            if (keyEnd !in 1..79) return null
            val key = String(bytes, 0, keyEnd, Charsets.ISO_8859_1)
            val start = keyEnd + 1
            val international = block.type == "iTXt"
            val value = when (block.type) {
                "tEXt" -> text(start, Charsets.ISO_8859_1)
                else -> {
                    if (start + 1 >= bytes.size) return null
                    val compressed = !international || u(start) == 1
                    if ((international && u(start) !in 0..1) || u(if (international) start + 1 else start) != 0) return null
                    val data = if (international) {
                        val language = zero(start + 2) ?: return null
                        (zero(language + 1) ?: return null) + 1
                    } else start + 1
                    val charset = if (international) Charsets.UTF_8 else Charsets.ISO_8859_1
                    if (!compressed) text(data, charset) else {
                        val output = ByteArray(4096)
                        var size = 0
                        InflaterInputStream(bytes.inputStream(data, bytes.size - data)).use { stream ->
                            while (size < output.size) {
                                val n = stream.read(output, size, output.size - size)
                                if (n < 0) break
                                size += n
                            }
                        }
                        String(output, 0, size, charset)
                    }
                }
            }
            value?.let { "$key\n$it" }
        }
        block.name == "XMP" -> text(if (block.type == "APP1") (zero(0) ?: return null) + 1 else 0)
        block.name == "Comment" -> text(0, Charsets.ISO_8859_1)
        block.name == "JFIF" && bytes.size >= 14 ->
            "JFIF ${u(5)}.${u(6).toString().padStart(2, '0')}\nDensity: ${short(8)} × ${short(10)} (${listOf("aspect ratio", "dpi", "dpcm").getOrNull(u(7)) ?: "unknown units"})"
        block.name == "ICC Profile" -> when (block.type) {
            "APP2" -> if (bytes.size >= 14) "ICC segment ${u(12)} of ${u(13)}" else null
            "ICCP" -> if (bytes.size >= 24) "Profile class: ${String(bytes, 12, 4, Charsets.US_ASCII)}\nColor space: ${String(bytes, 16, 4, Charsets.US_ASCII)}" else null
            "iCCP" -> zero(0)?.takeIf { it in 1..79 }?.let { "Profile: ${String(bytes, 0, it, Charsets.ISO_8859_1)}" }
            else -> null
        }
        block.type == "tIME" && bytes.size == 7 -> "${short(0)}-${u(2)}-${u(3)} ${u(4)}:${u(5)}:${u(6)} UTC"
        else -> null
    }
}

/** Bounded TIFF directory traversal. All offsets are relative to this EXIF payload. */
private fun tiffPreview(bytes: ByteArray): String? {
    val start = if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") 6 else 0
    val tiff = ByteBuffer.wrap(bytes, start, bytes.size - start).slice()
    if (tiff.limit() < 8) return null
    tiff.order(when (tiff.getShort(0).toInt()) {
        0x4949 -> ByteOrder.LITTLE_ENDIAN
        0x4d4d -> ByteOrder.BIG_ENDIAN
        else -> return null
    })
    fun u16(i: Int) = tiff.getShort(i).toInt() and 65535
    fun u32(i: Int) = tiff.getInt(i).toLong() and 0xffffffffL
    if (u16(2) != 42) return null
    val pending = ArrayDeque<Long>().apply { add(u32(4)) }
    val visited = mutableSetOf<Long>()
    val lines = mutableListOf<String>()
    while (pending.isNotEmpty() && visited.size < 16 && lines.size < 64) {
        val offset = pending.removeFirst()
        if (offset < 8 || offset > tiff.limit() - 2 || !visited.add(offset)) continue
        val entries = u16(offset.toInt())
        val first = offset.toInt() + 2
        if (entries > (tiff.limit() - first) / 12) continue
        for (index in 0 until entries) {
            val p = first + index * 12
            val tag = u16(p)
            val format = u16(p + 2)
            val count = u32(p + 4)
            if ((tag == 0x8769 || tag == 0x8825 || tag == 0xa005) && format == 4 && count == 1L) {
                if (pending.size < 16) pending.add(u32(p + 8))
                continue
            }
            val width = when (format) { 1, 2 -> 1; 3 -> 2; 4 -> 4; 5 -> 8; else -> continue }
            val length = count * width
            val at = if (length <= 4) p + 8L else u32(p + 8)
            if (count == 0L || at > tiff.limit() - length) continue
            val n = at.toInt()
            val value = if (format == 2) {
                String(bytes, start + n, minOf(count, 160L).toInt(), Charsets.ISO_8859_1).substringBefore('\u0000')
            } else (0 until minOf(count, 6L).toInt()).joinToString(", ") {
                val i = n + it * width
                when (format) { 1 -> (tiff[i].toInt() and 255).toString(); 3 -> u16(i).toString()
                    4 -> u32(i).toString(); else -> "${u32(i)}/${u32(i + 4)}" }
            }
            val label = exifLabels[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
            if (value.isNotBlank()) lines += "$label: $value"
            if (lines.size >= 64) break
        }
        val next = first + entries * 12
        if (next <= tiff.limit() - 4 && u32(next) != 0L) pending.add(u32(next))
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private val exifLabels = mapOf(0x010e to "Description", 0x010f to "Camera make", 0x0110 to "Camera model",
    0x0112 to "Orientation", 0x0131 to "Software", 0x0132 to "Date", 0x013b to "Artist",
    0x8298 to "Copyright", 0x9003 to "Date taken", 0xa434 to "Lens model")
