package com.metavault.cleaner.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class JpegEngineTest {
    private val clean = Fixtures.jpeg()

    @Test fun fixtureHasNoMetadata() {
        val r = PhotoEngine.inspect(clean)
        assertTrue(r.error, r.ok)
        assertEquals(PhotoFormat.JPEG, r.format)
        assertEquals(16, r.width); assertEquals(8, r.height)
        assertTrue(r.fields.isEmpty())
    }

    @Test fun inspectorReadsInjectedMetadata() {
        val dirty = Fixtures.injectAfterSoi(clean, Fixtures.exifSegment(1), Fixtures.xmpSegment(), Fixtures.iptcSegment(), Fixtures.commentSegment())
        val names = PhotoEngine.inspect(dirty).fields.map { it.name }
        assertTrue(names.toString(), "Camera make" in names)
        assertTrue("Date/time original" in names)
        assertTrue("GPS location data" in names)
        assertTrue("XMP packet" in names)
        assertTrue("Photoshop/IPTC block (APP13)" in names)
        assertTrue("JPEG comment" in names)
        val make = PhotoEngine.inspect(dirty).fields.first { it.name == "Camera make" }
        assertEquals("Canon", make.value)
    }

    @Test fun removesAllMetadataAndKeepsImageData() {
        val dirty = Fixtures.injectAfterSoi(clean, Fixtures.exifSegment(1), Fixtures.xmpSegment(), Fixtures.iptcSegment(), Fixtures.commentSegment())
        val r = PhotoEngine.clean(dirty)
        assertTrue(r.failure, r.verified)
        val out = r.output!!
        assertTrue(PhotoEngine.inspect(out).fields.isEmpty())
        assertTrue(r.removed.contains("EXIF · GPS location data"))
        assertTrue(r.removed.contains("XMP · XMP packet"))
        assertTrue(r.removed.contains("Comment · JPEG comment"))
        assertEquals(-1, Fixtures.indexOf(out, "Canon".toByteArray()))
        assertEquals(-1, Fixtures.indexOf(out, "secret".toByteArray()))
        val img = ImageIO.read(ByteArrayInputStream(out))
        assertNotNull(img); assertEquals(16, img.width); assertEquals(8, img.height)
    }

    @Test fun pixelsAreIdenticalToTheSource() {
        val dirty = Fixtures.injectAfterSoi(clean, Fixtures.exifSegment(1))
        val out = PhotoEngine.clean(dirty).output!!
        val a = ImageIO.read(ByteArrayInputStream(clean))
        val b = ImageIO.read(ByteArrayInputStream(out))
        for (x in 0 until a.width) for (y in 0 until a.height) assertEquals(a.getRGB(x, y), b.getRGB(x, y))
    }

    @Test fun keepsOrientationAsMinimalExifOnly() {
        val dirty = Fixtures.injectAfterSoi(clean, Fixtures.exifSegment(6))
        val r = PhotoEngine.clean(dirty)
        assertTrue(r.failure, r.verified)
        val after = PhotoEngine.inspect(r.output!!)
        assertEquals(listOf("Orientation"), after.fields.map { it.name })
        assertEquals("6", after.fields[0].value)
        assertEquals(-1, Fixtures.indexOf(r.output!!, "Canon".toByteArray()))
        assertTrue(r.notes.any { it.contains("orientation", ignoreCase = true) })
    }

    @Test fun dropsDataAppendedAfterEndOfImage() {
        val dirty = clean + "TRAILING-SECRET".toByteArray()
        assertTrue(PhotoEngine.inspect(dirty).fields.any { it.name.startsWith("Data after end of image") })
        val r = PhotoEngine.clean(dirty)
        assertTrue(r.failure, r.verified)
        assertEquals(-1, Fixtures.indexOf(r.output!!, "TRAILING".toByteArray()))
    }

    @Test fun keepsIccProfile() {
        val icc = Fixtures.iccSegment()
        val r = PhotoEngine.clean(Fixtures.injectAfterSoi(clean, icc, Fixtures.exifSegment(1)))
        assertTrue(r.failure, r.verified)
        assertTrue(Fixtures.indexOf(r.output!!, icc) >= 0)
    }

    @Test fun truncatedFileFailsWithoutOutput() {
        val r = PhotoEngine.clean(clean.copyOf(clean.size - 10))
        assertFalse(r.verified)
        assertNull(r.output)
        assertNotNull(r.failure)
    }

    @Test fun corruptSegmentLengthFailsWithoutOutput() {
        val bad = clean.copyOf()
        bad[4] = 0xFF.toByte(); bad[5] = 0xFF.toByte()
        val r = PhotoEngine.clean(bad)
        assertFalse(r.verified)
        assertNull(r.output)
    }
}
