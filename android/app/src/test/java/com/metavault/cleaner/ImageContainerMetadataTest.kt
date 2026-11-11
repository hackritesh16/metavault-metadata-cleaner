package com.metavault.cleaner

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageContainerMetadataTest {
    @Test
    fun cleanPng_doesNotDuplicateMalformedTrailingBytes() {
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val malformedTail = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            "tEXt".toByteArray(Charsets.US_ASCII) + "broken-tail".toByteArray()
        val input = signature + malformedTail

        val cleaned = ImageContainerMetadata.clean(input)

        assertTrue(cleaned.contentEquals(input))
    }

    @Test
    fun inspectAndCleanWebp_handlesC2paAndClearsVp8xMetadataFlags() {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0)) // patched RIFF size
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        writeChunk(out, "VP8X", byteArrayOf(0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        writeChunk(out, "EXIF", byteArrayOf(1))
        writeChunk(out, "XMP ", byteArrayOf(2))
        writeChunk(out, "C2PA", byteArrayOf(3, 4))
        writeChunk(out, "VP8 ", byteArrayOf(5, 6))
        val original = out.toByteArray()
        val riffSize = original.size - 8
        for (i in 0..3) out.toByteArray()[0] // keep fixture construction explicit
        val input = original.copyOf().also {
            it[4] = riffSize.toByte()
            it[5] = (riffSize ushr 8).toByte()
            it[6] = (riffSize ushr 16).toByte()
            it[7] = (riffSize ushr 24).toByte()
        }

        val findings = ImageContainerMetadata.inspect(input)
        assertTrue(findings.any { it.label == "WebP EXIF" })
        assertTrue(findings.any { it.label == "WebP XMP" })
        assertTrue(findings.any { it.label == "C2PA Content Credentials" })

        val cleaned = ImageContainerMetadata.clean(input)
        val remaining = ImageContainerMetadata.inspect(cleaned)
        assertFalse(remaining.any { it.label == "WebP EXIF" || it.label == "WebP XMP" || it.label == "C2PA Content Credentials" })
        assertEquals(cleaned.size - 8, readLe32(cleaned, 4))
        val vp8x = findChunk(cleaned, "VP8X")!!
        assertEquals(0, vp8x[0].toInt() and 0x0C)
        assertTrue(findChunk(cleaned, "VP8 ") != null)
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(type.toByteArray(Charsets.US_ASCII))
        writeLe32(out, data.size)
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
    }

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 255)
        out.write((value ushr 8) and 255)
        out.write((value ushr 16) and 255)
        out.write((value ushr 24) and 255)
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or
            ((bytes[offset + 1].toInt() and 255) shl 8) or
            ((bytes[offset + 2].toInt() and 255) shl 16) or
            ((bytes[offset + 3].toInt() and 255) shl 24)

    private fun findChunk(bytes: ByteArray, wanted: String): ByteArray? {
        var p = 12
        while (p + 8 <= bytes.size) {
            val type = String(bytes, p, 4, Charsets.US_ASCII)
            val len = readLe32(bytes, p + 4)
            if (len < 0 || p + 8L + len + (len and 1) > bytes.size) return null
            if (type == wanted) return bytes.copyOfRange(p + 8, p + 8 + len)
            p += 8 + len + (len and 1)
        }
        return null
    }
}
