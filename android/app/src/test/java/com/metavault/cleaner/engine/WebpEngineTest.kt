package com.metavault.cleaner.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * NOTE: WebP fixtures are structural (see Fixtures.vp8lPayload): they verify RIFF handling, flag updates and byte
 * preservation, but the image payload is not decodable. Real decoding is checked on-device by the app.
 */
class WebpEngineTest {
    private val image = Fixtures.webpChunk("VP8L", Fixtures.vp8lPayload)

    private fun extended(orientation: Int): ByteArray = Fixtures.riff(
        Fixtures.vp8x(0x08 or 0x04, 1, 1),
        image,
        Fixtures.webpChunk("EXIF", Fixtures.exifTiff(orientation)),
        Fixtures.webpChunk("XMP ", "<x:xmpmeta/>".toByteArray()),
        Fixtures.webpChunk("junk", "unknown-secret".toByteArray())
    )

    @Test fun simpleFileWithoutMetadataIsUnchanged() {
        val src = Fixtures.riff(image)
        val r = PhotoEngine.clean(src)
        assertTrue(r.failure, r.verified)
        assertArrayEquals(src, r.output)
        assertTrue(r.removed.isEmpty())
    }

    @Test fun removesExifXmpAndUnknownChunks() {
        val r = PhotoEngine.clean(extended(1))
        assertTrue(r.failure, r.verified)
        val out = r.output!!
        assertTrue(PhotoEngine.inspect(out).fields.isEmpty())
        assertEquals(-1, Fixtures.indexOf(out, "Canon".toByteArray()))
        assertEquals(-1, Fixtures.indexOf(out, "unknown-secret".toByteArray()))
        assertTrue(r.removed.contains("EXIF · Camera make"))
        assertTrue(r.removed.contains("XMP · XMP packet"))
        assertTrue(Fixtures.indexOf(out, Fixtures.vp8lPayload) >= 0)
        // RIFF size must match the real size, and the EXIF/XMP flags must be cleared.
        assertEquals((out.size - 8).toLong(), out.le32(4))
        val vp8x = Fixtures.indexOf(out, "VP8X".toByteArray())
        assertEquals(0, out.u8(vp8x + 8) and (0x08 or 0x04))
    }

    @Test fun keepsOrientationAsMinimalExifOnly() {
        val r = PhotoEngine.clean(extended(6))
        assertTrue(r.failure, r.verified)
        val out = r.output!!
        val after = PhotoEngine.inspect(out)
        assertEquals(listOf("Orientation"), after.fields.map { it.name })
        assertEquals("6", after.fields[0].value)
        assertEquals(-1, Fixtures.indexOf(out, "Canon".toByteArray()))
        val vp8x = Fixtures.indexOf(out, "VP8X".toByteArray())
        assertEquals(0x08, out.u8(vp8x + 8) and (0x08 or 0x04))
        assertEquals((out.size - 8).toLong(), out.le32(4))
    }

    @Test fun exifWithoutVp8xThatCannotPreserveOrientationIsRefused() {
        val src = Fixtures.riff(image, Fixtures.webpChunk("EXIF", Fixtures.exifTiff(6)))
        val r = PhotoEngine.clean(src)
        assertFalse(r.verified)
        assertNull(r.output)
        assertTrue(r.failure!!.contains("orientation"))
    }

    @Test fun truncatedFileFailsWithoutOutput() {
        val src = extended(1)
        val r = PhotoEngine.clean(src.copyOf(src.size - 6))
        assertFalse(r.verified)
        assertNull(r.output)
    }
}
