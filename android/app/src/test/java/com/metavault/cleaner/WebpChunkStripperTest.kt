package com.metavault.cleaner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebpChunkStripperTest {
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val pad = if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return type.toByteArray(Charsets.ISO_8859_1) + le32(data.size) + data + pad
    }

    private fun webp(vararg chunks: ByteArray, trailing: ByteArray = ByteArray(0)): ByteArray {
        var body = "WEBP".toByteArray()
        for (c in chunks) body += c
        return "RIFF".toByteArray() + le32(body.size) + body + trailing
    }

    private fun vp8x(flags: Int) = chunk("VP8X", ByteArray(10).also { it[0] = flags.toByte() })

    private val image = chunk("VP8L", ByteArray(12) { 4 })
    private val exif = chunk("EXIF", ByteArray(30) { 6 })
    private val xmp = chunk("XMP ", "<x:xmpmeta><rdf:Description xmp:CreatorTool=\"Midjourney\"/></x:xmpmeta>".toByteArray())
    private val c2pa = chunk("C2PA", ByteArray(21) { 8 }) // odd size: needs a padding byte
    private val icc = chunk("ICCP", ByteArray(5) { 2 })   // odd size: kept, padding must survive

    @Test
    fun strip_removesExifXmpC2pa_clearsFlags_keepsAlphaAndIcc() {
        // Flags: ICC 0x20, alpha 0x10, EXIF 0x08, XMP 0x04.
        val input = webp(vp8x(0x20 or 0x10 or 0x08 or 0x04), icc, image, exif, xmp, c2pa)
        val result = WebpChunkStripper.strip(input)
        assertArrayEquals(webp(vp8x(0x20 or 0x10), icc, image), result.bytes)
        assertEquals(3, result.removed.size)
    }

    @Test
    fun scan_reportsBlocksAndXmpFields() {
        val found = WebpChunkStripper.scan(webp(vp8x(0x0C), image, exif, xmp, c2pa))
        assertTrue(found.first { it.label == "EXIF block" }.isExif)
        assertTrue(found.first { it.label == "XMP block" }.details.contains("xmp:CreatorTool" to "Midjourney"))
        assertTrue(found.any { it.label == "C2PA content credentials" })
    }

    @Test
    fun strip_result_hasNoMetadataLeft() {
        val result = WebpChunkStripper.strip(webp(vp8x(0x0C), image, exif, xmp, c2pa))
        assertTrue(WebpChunkStripper.scan(result.bytes).isEmpty())
    }

    @Test
    fun strip_simpleWebpWithoutMetadata_isByteIdentical() {
        val input = webp(image)
        assertArrayEquals(input, WebpChunkStripper.strip(input).bytes)
    }

    @Test
    fun strip_keepsDataAfterRiff_andReportsIt() {
        val trailing = byteArrayOf(3, 3, 3, 3)
        val result = WebpChunkStripper.strip(webp(vp8x(0x08), image, exif, trailing = trailing))
        assertArrayEquals(webp(vp8x(0x00), image, trailing = trailing), result.bytes)
        assertEquals(4, result.trailingBytes)
    }

    @Test
    fun strip_rejectsNonWebpAndCorruptWebp() {
        assertThrows(IllegalArgumentException::class.java) { WebpChunkStripper.strip(ByteArray(40)) }
        val good = webp(vp8x(0x08), image, exif)
        assertThrows(IllegalArgumentException::class.java) { WebpChunkStripper.strip(good.copyOf(good.size - 10)) }
        assertThrows(IllegalArgumentException::class.java) { WebpChunkStripper.strip(webp(exif)) } // no image data
    }
}
