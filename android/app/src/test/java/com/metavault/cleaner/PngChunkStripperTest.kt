package com.metavault.cleaner

import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PngChunkStripperTest {
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // The stripper never validates CRCs, so a zero CRC is enough for these fixtures.
    private fun chunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val len = data.size
        return byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()) +
            type.toByteArray(Charsets.ISO_8859_1) + data + ByteArray(4)
    }

    private fun png(vararg mid: ByteArray, trailing: ByteArray = ByteArray(0)): ByteArray {
        var out = signature + chunk("IHDR", ByteArray(13))
        for (c in mid) out += c
        out += chunk("IDAT", byteArrayOf(1, 2, 3, 4))
        out += chunk("IEND")
        return out + trailing
    }

    private fun deflate(s: String): ByteArray {
        val d = Deflater()
        d.setInput(s.toByteArray())
        d.finish()
        val buf = ByteArray(512)
        val n = d.deflate(buf)
        d.end()
        return buf.copyOf(n)
    }

    private val sdText = "parameters\u0000masterpiece, 1girl\nSteps: 20, Seed: 123456".toByteArray(Charsets.ISO_8859_1)
    private val xmpText = "<x:xmpmeta><rdf:Description xmp:CreatorTool=\"ComfyUI\"/></x:xmpmeta>"
    private val xmpChunk = chunk(
        "iTXt",
        "XML:com.adobe.xmp".toByteArray() + byteArrayOf(0, 0, 0, 0, 0) + xmpText.toByteArray()
    )
    private val ztxt = chunk("zTXt", "Comment".toByteArray() + byteArrayOf(0, 0) + deflate("hidden prompt text"))
    private val exif = chunk("eXIf", ByteArray(20) { 5 })
    private val time = chunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 11, 12, 10, 30, 0))
    private val c2pa = chunk("caBX", ByteArray(40) { 9 })
    private val textChunk = chunk("tEXt", sdText)
    private val plte = chunk("PLTE", ByteArray(6))
    private val trns = chunk("tRNS", ByteArray(2))
    private val gama = chunk("gAMA", ByteArray(4))

    @Test
    fun strip_removesTextXmpExifTimeAndC2pa_keepsRenderingChunks() {
        val input = png(plte, trns, gama, textChunk, xmpChunk, ztxt, exif, time, c2pa)
        val result = PngChunkStripper.strip(input)
        assertArrayEquals(png(plte, trns, gama), result.bytes)
        assertEquals(6, result.removed.size)
        assertEquals(0, result.trailingBytes)
    }

    @Test
    fun scan_showsAiPromptCompressedTextAndXmpFields() {
        val found = PngChunkStripper.scan(png(textChunk, xmpChunk, ztxt, exif, time, c2pa))
        val ai = found.first { it.label == "AI \u00B7 parameters" }
        assertTrue(ai.value.contains("Seed: 123456"))
        assertEquals("hidden prompt text", found.first { it.label == "Text \u00B7 Comment" }.value)
        assertTrue(found.first { it.label == "XMP block" }.details.contains("xmp:CreatorTool" to "ComfyUI"))
        assertTrue(found.first { it.label == "Last modified" }.value.startsWith("2026-11-12"))
        assertTrue(found.any { it.label == "C2PA content credentials" })
        assertTrue(found.first { it.label == "EXIF block" }.isExif)
    }

    @Test
    fun strip_result_hasNoMetadataLeft() {
        val result = PngChunkStripper.strip(png(textChunk, xmpChunk, ztxt, exif, time, c2pa))
        assertTrue(PngChunkStripper.scan(result.bytes).isEmpty())
        assertFalse(String(result.bytes, Charsets.ISO_8859_1).contains("Seed: 123456"))
    }

    @Test
    fun strip_fileWithoutMetadata_isByteIdentical() {
        val input = png(plte, trns)
        assertArrayEquals(input, PngChunkStripper.strip(input).bytes)
    }

    @Test
    fun strip_keepsDataAfterIend_andReportsIt() {
        val trailing = byteArrayOf(1, 1, 1)
        val result = PngChunkStripper.strip(png(textChunk, trailing = trailing))
        assertArrayEquals(png(trailing = trailing), result.bytes)
        assertEquals(3, result.trailingBytes)
    }

    @Test
    fun strip_rejectsNonPngAndCorruptPng() {
        assertThrows(IllegalArgumentException::class.java) { PngChunkStripper.strip(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) }
        val good = png(textChunk)
        assertThrows(IllegalArgumentException::class.java) { PngChunkStripper.strip(good.copyOf(good.size - 20)) }
        // A chunk that claims to be larger than the file.
        val badLength = signature + byteArrayOf(0x7F, 0, 0, 0) + "IHDR".toByteArray() + ByteArray(20)
        assertThrows(IllegalArgumentException::class.java) { PngChunkStripper.strip(badLength) }
    }
}
