package com.metavault.cleaner.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class PngEngineTest {
    private val clean = Fixtures.png()

    private fun dirty(orientation: Int? = null): ByteArray {
        val chunks = mutableListOf(
            Fixtures.pngChunk("tEXt", "Author\u0000Jane Doe".toByteArray()),
            Fixtures.pngChunk("iTXt", "XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x/>".toByteArray()),
            Fixtures.pngChunk("tIME", byteArrayOf(0x07, 0xE4.toByte(), 1, 1, 10, 0, 0)),
            Fixtures.pngChunk("prVt", "private-secret".toByteArray())
        )
        if (orientation != null) chunks += Fixtures.pngChunk("eXIf", Fixtures.exifTiff(orientation))
        return Fixtures.pngInject(clean, *chunks.toTypedArray())
    }

    @Test fun inspectorReadsChunks() {
        val r = PhotoEngine.inspect(dirty(1))
        assertTrue(r.error, r.ok)
        assertEquals(PhotoFormat.PNG, r.format)
        val names = r.fields.map { it.name }
        assertTrue(names.toString(), "tEXt: Author" in names)
        assertTrue("Last-modified time (tIME)" in names)
        assertTrue("Ancillary chunk prVt" in names)
        assertTrue("GPS location data" in names)
    }

    @Test fun removesMetadataKeepsPixels() {
        val r = PhotoEngine.clean(dirty(1))
        assertTrue(r.failure, r.verified)
        val out = r.output!!
        assertTrue(PhotoEngine.inspect(out).fields.isEmpty())
        assertEquals(-1, Fixtures.indexOf(out, "Jane".toByteArray()))
        assertEquals(-1, Fixtures.indexOf(out, "private-secret".toByteArray()))
        val a = ImageIO.read(ByteArrayInputStream(clean))
        val b = ImageIO.read(ByteArrayInputStream(out))
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        for (x in 0 until a.width) for (y in 0 until a.height) assertEquals(a.getRGB(x, y), b.getRGB(x, y))
    }

    @Test fun keepsOrientationAsMinimalExifOnly() {
        val r = PhotoEngine.clean(dirty(6))
        assertTrue(r.failure, r.verified)
        val after = PhotoEngine.inspect(r.output!!)
        assertEquals(listOf("Orientation"), after.fields.map { it.name })
        assertEquals("6", after.fields[0].value)
        assertEquals(-1, Fixtures.indexOf(r.output!!, "Canon".toByteArray()))
    }

    @Test fun crcMismatchFailsWithoutOutput() {
        val bad = clean.copyOf()
        val idat = Fixtures.indexOf(bad, "IDAT".toByteArray())
        bad[idat + 6] = (bad[idat + 6].toInt() xor 0x55).toByte()
        val r = PhotoEngine.clean(bad)
        assertFalse(r.verified)
        assertNull(r.output)
        assertTrue(r.failure!!.contains("CRC"))
    }

    @Test fun truncatedFileFailsWithoutOutput() {
        val r = PhotoEngine.clean(clean.copyOf(clean.size - 5))
        assertFalse(r.verified)
        assertNull(r.output)
    }

    @Test fun dropsDataAfterIend() {
        val r = PhotoEngine.clean(clean + "TRAILING-SECRET".toByteArray())
        assertTrue(r.failure, r.verified)
        assertEquals(-1, Fixtures.indexOf(r.output!!, "TRAILING".toByteArray()))
    }
}
