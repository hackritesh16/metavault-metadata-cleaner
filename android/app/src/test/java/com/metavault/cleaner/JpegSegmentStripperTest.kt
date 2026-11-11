package com.metavault.cleaner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegSegmentStripperTest {
    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    // Entropy-coded data containing a stuffed 0xFF00 pair, which must not be mistaken for a marker.
    private val scanData = byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34)

    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun jpeg(vararg segs: ByteArray, trailing: ByteArray = ByteArray(0)): ByteArray {
        var out = soi
        for (s in segs) out += s
        out += seg(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
        out += scanData
        out += eoi
        return out + trailing
    }

    private val jfif = seg(0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))
    private val exif = seg(0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(8))
    private val icc = seg(0xE2, "ICC_PROFILE\u0000".toByteArray() + ByteArray(5))
    private val xmp = seg(
        0xE1,
        ("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF>" +
            "<rdf:Description xmp:CreatorTool=\"Stable Diffusion\"/></rdf:RDF></x:xmpmeta>").toByteArray()
    )
    private val iptc = seg(0xED, "Photoshop 3.0\u0000".toByteArray() + ByteArray(6))
    private val comment = seg(0xFE, "secret note".toByteArray())
    private val vendor = seg(0xE5, ByteArray(10) { 7 })
    private val c2pa = seg(0xEB, byteArrayOf(0x4A, 0x50, 0, 0) + "jumbc2pa".toByteArray())
    private val dqt = seg(0xDB, ByteArray(65) { 3 })

    // Expected minimal EXIF block for orientation 6: "Exif\0\0" + big-endian TIFF with a single Orientation entry.
    private val orientation6 = seg(
        0xE1,
        "Exif\u0000\u0000".toByteArray() + byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x06, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
    )

    @Test
    fun strip_removesAllMetadataBlocks_keepsImageSegments() {
        val input = jpeg(jfif, exif, icc, xmp, iptc, comment, vendor, c2pa, dqt)
        val result = JpegSegmentStripper.strip(input)
        assertArrayEquals(jpeg(jfif, icc, dqt), result.bytes)
        assertEquals(6, result.removed.size)
        assertEquals(0, result.trailingBytes)
    }

    @Test
    fun strip_keepsRotatedOrientationOnly() {
        val result = JpegSegmentStripper.strip(jpeg(jfif, exif, dqt), orientation = 6)
        assertArrayEquals(jpeg(jfif, orientation6, dqt), result.bytes)
    }

    @Test
    fun strip_normalOrientation_writesNoExif() {
        val result = JpegSegmentStripper.strip(jpeg(jfif, exif, dqt), orientation = 1)
        assertArrayEquals(jpeg(jfif, dqt), result.bytes)
    }

    @Test
    fun strip_fileWithoutMetadata_isByteIdentical() {
        val input = jpeg(jfif, dqt)
        val result = JpegSegmentStripper.strip(input)
        assertArrayEquals(input, result.bytes)
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun strip_keepsDataAfterEoi_andReportsIt() {
        val trailing = byteArrayOf(9, 9, 9, 9, 9)
        val result = JpegSegmentStripper.strip(jpeg(jfif, xmp, dqt, trailing = trailing))
        assertArrayEquals(jpeg(jfif, dqt, trailing = trailing), result.bytes)
        assertEquals(5, result.trailingBytes)
    }

    @Test
    fun scan_reportsKindsCommentTextAndXmpFields() {
        val found = JpegSegmentStripper.scan(jpeg(jfif, exif, xmp, iptc, comment, vendor, c2pa, dqt))
        assertEquals(
            listOf(
                JpegSegmentStripper.Kind.EXIF, JpegSegmentStripper.Kind.XMP, JpegSegmentStripper.Kind.IPTC,
                JpegSegmentStripper.Kind.COMMENT, JpegSegmentStripper.Kind.OTHER_APP, JpegSegmentStripper.Kind.C2PA
            ),
            found.map { it.kind }
        )
        assertEquals("secret note", found.first { it.kind == JpegSegmentStripper.Kind.COMMENT }.summary)
        val xmpFields = found.first { it.kind == JpegSegmentStripper.Kind.XMP }.details
        assertTrue(xmpFields.contains("xmp:CreatorTool" to "Stable Diffusion"))
    }

    @Test
    fun strip_result_hasNoMetadataLeft() {
        val result = JpegSegmentStripper.strip(jpeg(jfif, exif, xmp, iptc, comment, vendor, c2pa, dqt))
        assertTrue(JpegSegmentStripper.scan(result.bytes).isEmpty())
        assertFalse(String(result.bytes, Charsets.ISO_8859_1).contains("secret note"))
        assertFalse(String(result.bytes, Charsets.ISO_8859_1).contains("Stable Diffusion"))
    }

    @Test
    fun strip_rejectsNonJpeg() {
        assertThrows(IllegalArgumentException::class.java) {
            JpegSegmentStripper.strip(byteArrayOf(1, 2, 3, 4, 5))
        }
    }

    @Test
    fun strip_rejectsTruncatedJpeg() {
        val input = jpeg(jfif, xmp, dqt)
        assertThrows(IllegalArgumentException::class.java) {
            JpegSegmentStripper.strip(input.copyOf(input.size - 12))
        }
    }
}
