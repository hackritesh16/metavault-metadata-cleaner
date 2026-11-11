package com.metavault.cleaner

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegMetadataStripperTest {
    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun exif(orientation: Int, secret: String = "GPS-SECRET"): ByteArray {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0, 1, 0,
            0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0, 0
        )
        return seg(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff + secret.toByteArray())
    }

    private val jfif = seg(0xE0, "JFIF\u0000".toByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val scanData = byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56)
    private val body = seg(0xDB, ByteArray(65)) + seg(0xC0, ByteArray(15)) + seg(0xC4, ByteArray(20)) +
        seg(0xDA, ByteArray(10)) + scanData + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun jpeg(vararg extra: ByteArray, trailing: ByteArray = ByteArray(0)): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        o.write(jfif)
        extra.forEach { o.write(it) }
        o.write(body)
        o.write(trailing)
        return o.toByteArray()
    }

    private fun contains(h: ByteArray, n: ByteArray) = String(h, Charsets.ISO_8859_1).contains(String(n, Charsets.ISO_8859_1))

    @Test fun removesExifXmpIptcCommentAndTrailingData() {
        val input = jpeg(
            exif(1), seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000SECRET-XMP".toByteArray()),
            seg(0xED, "Photoshop 3.0\u0000SECRET-IPTC".toByteArray()), seg(0xFE, "SECRET-COMMENT".toByteArray()),
            trailing = "SECRET-TRAILER".toByteArray()
        )
        val r = JpegMetadataStripper.strip(input)
        listOf("GPS-SECRET", "SECRET-XMP", "SECRET-IPTC", "SECRET-COMMENT", "SECRET-TRAILER").forEach {
            assertFalse(it, contains(r.bytes, it.toByteArray()))
        }
        assertEquals(5, r.removedSegments)
        assertNull(r.orientationKept)
        assertTrue(r.bytes.size < input.size)
    }

    @Test fun imageDataIsByteIdentical() {
        val r = JpegMetadataStripper.strip(jpeg(exif(1)))
        assertEquals(jpeg().toList(), r.bytes.toList())
    }

    @Test fun keepsOnlyOrientationWhenRotated() {
        val r = JpegMetadataStripper.strip(jpeg(exif(6)))
        assertEquals(6, r.orientationKept)
        assertFalse(contains(r.bytes, "GPS-SECRET".toByteArray()))
        assertTrue(contains(r.bytes, "Exif".toByteArray()))
        assertEquals(0xD8, r.bytes[1].toInt() and 0xFF)
        assertEquals(0xE0, r.bytes[3].toInt() and 0xFF) // JFIF stays first
    }

    @Test fun dropsOrientationWhenRequested() {
        val r = JpegMetadataStripper.strip(jpeg(exif(6)), keepOrientation = false)
        assertNull(r.orientationKept)
        assertFalse(contains(r.bytes, "Exif".toByteArray()))
    }

    @Test fun keepsIccProfile() {
        val icc = seg(0xE2, "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1, 9, 9))
        assertTrue(contains(JpegMetadataStripper.strip(jpeg(icc)).bytes, "ICC_PROFILE".toByteArray()))
    }

    @Test fun rejectsNonJpegAndTruncated() {
        assertThrows(IllegalArgumentException::class.java) { JpegMetadataStripper.strip(byteArrayOf(1, 2, 3, 4, 5)) }
        val cut = jpeg().copyOf(20)
        assertThrows(IllegalArgumentException::class.java) { JpegMetadataStripper.strip(cut) }
    }

    private fun assertThrows(c: Class<out Throwable>, f: () -> Unit) {
        try { f() } catch (t: Throwable) { if (c.isInstance(t)) return; throw t }
        fail("Expected ${c.simpleName}")
    }
}
