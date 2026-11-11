package com.metavault.cleaner

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO

class MetadataProcessorTest {
    // ---- helpers -------------------------------------------------------------------------
    private fun image(): BufferedImage = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).also { im ->
        for (y in 0 until 30) for (x in 0 until 40) im.setRGB(x, y, (x * 6 shl 16) or (y * 8 shl 8) or ((x + y) * 3))
    }
    private fun encode(fmt: String): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image(), fmt, it) }.toByteArray()
    private fun pixels(b: ByteArray): List<Int> {
        val im = ImageIO.read(ByteArrayInputStream(b)); return (0 until im.height).flatMap { y -> (0 until im.width).map { x -> im.getRGB(x, y) } }
    }
    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Int) = be16(v ushr 16) + be16(v and 0xFFFF)

    /** Big-endian TIFF: IFD0 {Make, Artist, GPS ptr, Orientation} + GPS IFD (N 12.5, E 77.25). */
    private fun tiffWithGps(orientation: Int = 1): ByteArray {
        val make = "Acme\u0000".toByteArray()           // 5 bytes -> offset
        val artist = "Secret Person\u0000".toByteArray()  // 14 bytes -> offset
        // layout: header 8 | IFD0 (2 + 4*12 + 4 = 54) -> 62 | make(5)+pad | artist(14) | GPS IFD | rationals
        val ifd0Start = 8; val dataStart = 8 + 54
        val makeOff = dataStart; val artistOff = makeOff + 6; val gpsOff = artistOff + 14
        val gpsEntries = 4; val gpsSize = 2 + gpsEntries * 12 + 4
        val latOff = gpsOff + gpsSize; val lonOff = latOff + 24
        fun ent(tag: Int, type: Int, count: Int, v: ByteArray) = be16(tag) + be16(type) + be32(count) + v
        val ifd0 = be16(4) +
            ent(0x010F, 2, 5, be32(makeOff)) + ent(0x0112, 3, 1, be16(orientation) + byteArrayOf(0, 0)) +
            ent(0x013B, 2, 14, be32(artistOff)) + ent(0x8825, 4, 1, be32(gpsOff)) + be32(0)
        val gps = be16(gpsEntries) +
            ent(1, 2, 2, "N\u0000".toByteArray() + byteArrayOf(0, 0)) + ent(2, 5, 3, be32(latOff)) +
            ent(3, 2, 2, "E\u0000".toByteArray() + byteArrayOf(0, 0)) + ent(4, 5, 3, be32(lonOff)) + be32(0)
        fun rats(d: Int, m: Int, s: Int) = be32(d) + be32(1) + be32(m) + be32(1) + be32(s) + be32(1)
        return "MM".toByteArray() + be16(42) + be32(ifd0Start) + ifd0 + "Acme\u0000\u0000".toByteArray() +
            "Secret Person\u0000".toByteArray() + gps + rats(12, 30, 0) + rats(77, 15, 0)
    }
    private fun app(marker: Int, payload: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte()) + be16(payload.size + 2) + payload
    private fun jpegWithMeta(orientation: Int = 1): ByteArray {
        val base = encode("jpg")
        val segs = app(0xE1, "Exif\u0000\u0000".toByteArray() + tiffWithGps(orientation)) +
            app(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x>SECRET-XMP</x>".toByteArray()) +
            app(0xED, "Photoshop 3.0\u0000SECRET-IPTC".toByteArray()) + app(0xFE, "SECRET-COMMENT".toByteArray())
        // insert after SOI (+ JFIF APP0 if ImageIO wrote one)
        var at = 2
        if (base[2] == 0xFF.toByte() && base[3] == 0xE0.toByte()) at = 4 + ((base[4].toInt() and 0xFF) shl 8 or (base[5].toInt() and 0xFF))
        return base.copyOfRange(0, at) + segs + base.copyOfRange(at, base.size) + "SECRET-TRAILER".toByteArray()
    }
    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(); val crc = CRC32().also { it.update(t); it.update(data) }.value.toInt()
        return be32(data.size) + t + data + be32(crc)
    }
    private fun pngWithMeta(): ByteArray {
        val base = encode("png")
        val iend = base.size - 12
        val extra = pngChunk("tEXt", "Author\u0000SECRET-AUTHOR".toByteArray()) + pngChunk("iTXt", "XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x>SECRET-XMP</x>".toByteArray()) +
            pngChunk("eXIf", tiffWithGps()) + pngChunk("tIME", byteArrayOf(0x07, 0xE8.toByte(), 5, 17, 14, 30, 0)) + pngChunk("caBX", "SECRET-C2PA".toByteArray())
        return base.copyOfRange(0, iend) + extra + base.copyOfRange(iend, base.size) + "SECRET-TRAILER".toByteArray()
    }
    private fun has(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(s)
    private fun pngChunksValid(b: ByteArray): Boolean {
        var p = 8
        while (p + 12 <= b.size) {
            val len = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            val crc = CRC32().also { it.update(b, p + 4, 4 + len) }.value.toInt()
            val stored = ((b[p + 8 + len].toInt() and 0xFF) shl 24) or ((b[p + 9 + len].toInt() and 0xFF) shl 16) or ((b[p + 10 + len].toInt() and 0xFF) shl 8) or (b[p + 11 + len].toInt() and 0xFF)
            if (crc != stored) return false
            p += 12 + len
        }
        return p == b.size
    }

    // ---- reader --------------------------------------------------------------------------
    @Test fun readsJpegFieldsIncludingGps() {
        val s = MetadataReader.scan(jpegWithMeta(), "image/jpeg")
        assertEquals("JPEG", s.format)
        assertTrue(s.hasGps)
        assertEquals("Acme", s.valueOf("Make")); assertEquals("Secret Person", s.valueOf("Artist"))
        assertEquals("12.500000", s.fields.first { it.name == "GPSLatitude" }.value)
        assertEquals("77.250000", s.fields.first { it.name == "GPSLongitude" }.value)
        assertTrue(s.fields.any { it.group == "XMP" }); assertTrue(s.fields.any { it.group == "IPTC / Photoshop" })
        assertTrue(s.fields.any { it.name == "JPEG comment" && it.value == "SECRET-COMMENT" })
        assertTrue(s.fields.any { it.group == "Trailing data" })
    }

    @Test fun readsPngFields() {
        val s = MetadataReader.scan(pngWithMeta(), "image/png")
        assertEquals("PNG", s.format); assertTrue(s.hasGps)
        assertEquals("SECRET-AUTHOR", s.valueOf("Author"))
        assertTrue(s.fields.any { it.name == "Last modified" && it.value.startsWith("2024-05-17 14:30") })
        assertTrue(s.fields.any { it.name == "Chunk 'caBX'" }); assertTrue(s.fields.any { it.group == "Trailing data" })
    }

    @Test fun readsWebpExifChunk() {
        val tiff = tiffWithGps()
        val chunk = "EXIF".toByteArray() + byteArrayOf(tiff.size.toByte(), 0, 0, 0) + tiff + (if (tiff.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        val body = "WEBP".toByteArray() + chunk
        val webp = "RIFF".toByteArray() + byteArrayOf(body.size.toByte(), 0, 0, 0) + body
        val s = MetadataReader.scan(webp, "image/webp")
        assertEquals("WEBP", s.format); assertTrue(s.hasGps); assertEquals("Acme", s.fields.first { it.name == "Make" }.value)
    }

    @Test fun unknownFormatIsNotPretendedSupported() {
        val s = MetadataReader.scan(ByteArray(64) { 7 }, "application/pdf")
        assertEquals("Unsupported", s.format); assertTrue(s.fields.isEmpty())
    }

    // ---- remove --------------------------------------------------------------------------
    @Test fun jpegRemoveKeepsPixelsAndVerifiesClean() {
        val input = jpegWithMeta()
        val r = MetadataProcessor.process(input, "image/jpeg", Mode.REMOVE, emptyMap())
        assertEquals(pixels(input), pixels(r.bytes))
        assertTrue(r.remaining.isEmpty()); assertTrue(r.lossless)
        assertNotEquals(r.hashBefore, r.hashAfter); assertEquals(64, r.hashAfter.length)
        listOf("SECRET-XMP", "SECRET-IPTC", "SECRET-COMMENT", "SECRET-TRAILER", "Secret Person", "Acme").forEach { assertFalse(it, has(r.bytes, it)) }
        assertTrue(r.sizeAfter < r.sizeBefore)
    }

    @Test fun jpegRemoveKeepsRotationOnly() {
        val r = MetadataProcessor.process(jpegWithMeta(6), "image/jpeg", Mode.REMOVE, emptyMap())
        assertEquals(listOf("Orientation"), r.remaining.map { it.name })
        assertFalse(r.remaining.any { it.name.startsWith("GPS") })
    }

    @Test fun pngRemoveKeepsPixelsAndChunksValid() {
        val input = pngWithMeta()
        val r = MetadataProcessor.process(input, "image/png", Mode.REMOVE, emptyMap())
        assertEquals(pixels(input), pixels(r.bytes))
        assertTrue(pngChunksValid(r.bytes)); assertTrue(r.remaining.isEmpty())
        listOf("SECRET-AUTHOR", "SECRET-XMP", "SECRET-C2PA", "SECRET-TRAILER", "tIME", "eXIf").forEach { assertFalse(it, has(r.bytes, it)) }
    }

    // ---- edit ----------------------------------------------------------------------------
    @Test fun jpegEditWritesOnlyChosenFields() {
        val edits = mapOf("Artist" to "Ritesh", "Copyright" to "© 2026 MetaVault", "Make" to "")
        val r = MetadataProcessor.process(jpegWithMeta(), "image/jpeg", Mode.EDIT, edits)
        val s = MetadataReader.scan(r.bytes, "image/jpeg")
        assertEquals("Ritesh", s.valueOf("Artist")); assertEquals("© 2026 MetaVault", s.valueOf("Copyright"))
        assertNull(s.valueOf("Make")); assertFalse(s.hasGps)
        assertEquals(2, r.remaining.size)
        assertEquals(pixels(jpegWithMeta()), pixels(r.bytes))
        assertFalse(has(r.bytes, "Secret Person"))
    }

    @Test fun jpegEditLongValueUsesOffsetAndDateTimeChecked() {
        val long = "A".repeat(500)
        val r = MetadataProcessor.process(jpegWithMeta(), "image/jpeg", Mode.EDIT, mapOf("ImageDescription" to long, "DateTime" to "2024:05:17 14:30:00"))
        assertEquals(long.take(300), MetadataReader.scan(r.bytes, "image/jpeg").valueOf("ImageDescription"))
        assertEquals("2024:05:17 14:30:00", MetadataReader.scan(r.bytes, "image/jpeg").valueOf("DateTime"))
        assertThrows { MetadataProcessor.process(jpegWithMeta(), "image/jpeg", Mode.EDIT, mapOf("DateTime" to "yesterday")) }
        assertThrows { MetadataProcessor.process(jpegWithMeta(), "image/jpeg", Mode.EDIT, mapOf("Title" to "x")) }
    }

    @Test fun pngEditLatin1AndUnicode() {
        val r = MetadataProcessor.process(pngWithMeta(), "image/png", Mode.EDIT, mapOf("Author" to "Ritesh", "Title" to "दृश्य ✓"))
        val s = MetadataReader.scan(r.bytes, "image/png")
        assertEquals("Ritesh", s.valueOf("Author")); assertEquals("दृश्य ✓", s.valueOf("Title"))
        assertTrue(pngChunksValid(r.bytes)); assertEquals(pixels(pngWithMeta()), pixels(r.bytes))
        assertFalse(has(r.bytes, "SECRET-AUTHOR")); assertFalse(s.hasGps)
    }

    @Test fun viewModeAndBadInputRejected() {
        assertThrows { MetadataProcessor.process(jpegWithMeta(), "image/jpeg", Mode.VIEW, emptyMap()) }
        assertThrows { MetadataProcessor.process(ByteArray(50) { 1 }, "image/jpeg", Mode.REMOVE, emptyMap()) }
        assertThrows { MetadataProcessor.process(pngWithMeta().copyOf(60), "image/png", Mode.REMOVE, emptyMap()) }
    }

    @Test fun outputNameRules() {
        assertEquals("processed_Cute Sister.png", MetadataProcessor.outputName("Cute Sister.png", "", true, "image/png"))
        assertEquals("Cute Sister.jpg", MetadataProcessor.outputName("Cute Sister.jpeg", "  ", false, "image/jpeg"))
        assertEquals("my_pic.webp", MetadataProcessor.outputName("a.webp", "my/pic", false, "image/webp"))
        assertEquals("image.jpg", MetadataProcessor.outputName("a.jpg", "...", false, "image/jpeg"))
    }

    private fun assertThrows(f: () -> Unit) {
        try { f() } catch (e: IllegalArgumentException) { return }
        fail("Expected IllegalArgumentException")
    }
}
