package com.metavault.cleaner

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** Builds a C2PA manifest shaped like the one found in a real ChatGPT-generated PNG. */
class ContentCredentialsTest {
    private val certDer = Base64.getDecoder().decode(
        "MIIBujCCAWGgAwIBAgIUAhVgu0rNFMgv55ulxvi9Kj/OYrEwCgYIKoZIzj0EAwIwMzEeMBwGA1UEAwwVTWV0YVZhdWx0IFRlc3QgU2lnbmVyMREwDwYDVQQKDAhUZXN0IE9yZzAeFw0yNjExMTEyMjAyMTFaFw0zNjExMDgyMjAyMTFaMDMxHjAcBgNVBAMMFU1ldGFWYXVsdCBUZXN0IFNpZ25lcjERMA8GA1UECgwIVGVzdCBPcmcwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAThirHdDdn2GxFrtsJksgN5Pyt7oL+svSgz3zf0xz46YSjGeCevWl0pKP1eJKXazhQfJTxpzP7aNbeOP3roPzi6o1MwUTAdBgNVHQ4EFgQUeH30ICg5ZF56sD5+6jVPsKh7O1AwHwYDVR0jBBgwFoAUeH30ICg5ZF56sD5+6jVPsKh7O1AwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNHADBEAiBzpsiGFRKUjOJsoe46aYtTcMpZLb9o96ckTtzUeAZCYgIgQGybvzWhrS6H5DsngSqFB1Dnss6JOcN05/2E+gD8qpo="
    )

    // ---- tiny CBOR encoder -------------------------------------------------------------
    private fun head(major: Int, n: Long): ByteArray = when {
        n < 24 -> byteArrayOf(((major shl 5) or n.toInt()).toByte())
        n < 256 -> byteArrayOf(((major shl 5) or 24).toByte(), n.toByte())
        n < 65536 -> byteArrayOf(((major shl 5) or 25).toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(((major shl 5) or 26).toByte(), (n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }
    private fun enc(x: Any?): ByteArray = when (x) {
        null -> byteArrayOf(0xF6.toByte())
        is Boolean -> byteArrayOf(if (x) 0xF5.toByte() else 0xF4.toByte())
        is Int -> if (x >= 0) head(0, x.toLong()) else head(1, (-1 - x).toLong())
        is String -> head(3, x.toByteArray().size.toLong()) + x.toByteArray()
        is ByteArray -> head(2, x.size.toLong()) + x
        is List<*> -> x.fold(head(4, x.size.toLong())) { a, e -> a + enc(e) }
        is Map<*, *> -> x.entries.fold(head(5, x.size.toLong())) { a, e -> a + enc(e.key) + enc(e.value) }
        is Pair<*, *> -> head(6, (x.first as Int).toLong()) + enc(x.second) // (tag, value)
        else -> error("unsupported ${x::class}")
    }

    // ---- JUMBF builder -----------------------------------------------------------------
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(payload.size + 8) + type.toByteArray() + payload
    private fun jumb(label: String, vararg children: ByteArray): ByteArray {
        val jumd = box("jumd", ByteArray(16) + byteArrayOf(0x03) + label.toByteArray() + 0)
        return box("jumb", children.fold(jumd) { a, c -> a + c })
    }
    private fun cborBox(x: Any?) = box("cbor", enc(x))

    private fun manifest(): ByteArray {
        val actions = mapOf("actions" to listOf(mapOf(
            "action" to "c2pa.created", "when" to (0 to "2026:11:10 11:44:38.867056905Z"),
            "softwareAgent" to mapOf("name" to "ChatGPT", "version" to "gpt-image"),
            "digitalSourceType" to "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"
        )), "allActionsIncluded" to false)
        val hash = mapOf("exclusions" to listOf(mapOf("start" to 33, "length" to 23666)), "name" to "jumbf manifest", "alg" to "sha256", "hash" to ByteArray(32), "pad" to ByteArray(8))
        val claim = mapOf("claim_generator_info" to mapOf("name" to "OpenAI Media Service API"), "instanceID" to "xmp:iid:d3721e17-c800-4d91-b105-ee4b9e596e25", "dc:title" to "image.png", "alg" to "sha256")
        val sig = 18 to listOf(
            enc(mapOf(1 to -7)),
            mapOf(33 to listOf(certDer), "sigTst2" to mapOf("tstTokens" to listOf(mapOf("val" to ByteArray(5259)))), "rVals" to mapOf("ocspVals" to listOf(ByteArray(2007)))),
            null, ByteArray(64)
        )
        return jumb("c2pa", jumb("urn:c2pa:3c27fe99-cb2a-4389-af15-ebf7ce7332b3",
            jumb("c2pa.assertions", jumb("c2pa.actions.v2", cborBox(actions)), jumb("c2pa.hash.data", cborBox(hash)), jumb("c2pa.icon", box("bfdb", ByteArray(2415)))),
            jumb("c2pa.claim.v2", cborBox(claim)), jumb("c2pa.signature", cborBox(sig))))
    }

    // ---- image helpers -----------------------------------------------------------------
    private fun img() = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).also { im -> for (y in 0 until 30) for (x in 0 until 40) im.setRGB(x, y, x * 6 shl 16 or (y * 8 shl 8) or 90) }
    private fun encode(fmt: String) = ByteArrayOutputStream().also { ImageIO.write(img(), fmt, it) }.toByteArray()
    private fun pixels(b: ByteArray): List<Int> { val im = ImageIO.read(ByteArrayInputStream(b)); return (0 until im.height).flatMap { y -> (0 until im.width).map { x -> im.getRGB(x, y) } } }
    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(); val crc = CRC32().also { it.update(t); it.update(data) }.value.toInt()
        return be32(data.size) + t + data + be32(crc)
    }
    private fun pngWithC2pa(): ByteArray { val b = encode("png"); val i = b.size - 12; return b.copyOfRange(0, i) + pngChunk("caBX", manifest()) + b.copyOfRange(i, b.size) }
    private fun field(s: MetadataScan, name: String) = s.fields.filter { it.group.startsWith("Content") }.firstOrNull { it.name == name }?.value
    private fun prop(s: MetadataScan, name: String) = s.properties.firstOrNull { it.name == name }?.value

    // ---- tests -------------------------------------------------------------------------
    @Test fun parsesManifestLikeRealChatGptImage() {
        val m = manifest()
        val r = ContentCredentials.parse(m, 0, m.size)!!
        assertEquals("Content Credentials declare this image as AI-generated (ChatGPT gpt-image).", r.aiNote)
        fun f(n: String) = r.fields.firstOrNull { it.name == n }?.value
        assertEquals("AI-generated (trainedAlgorithmicMedia)", f("Declared origin"))
        assertEquals("ChatGPT gpt-image", f("Software agent")); assertEquals("OpenAI Media Service API", f("Claim generator"))
        assertEquals("c2pa.created at 2026:11:10 11:44:38.867056905Z", f("Action"))
        assertEquals("image.png", f("Title")); assertEquals("xmp:iid:d3721e17-c800-4d91-b105-ee4b9e596e25", f("Instance ID"))
        assertEquals("urn:c2pa:3c27fe99-cb2a-4389-af15-ebf7ce7332b3", f("Manifest"))
        assertEquals("ES256", f("Signature algorithm")); assertEquals("1 in chain", f("Certificates"))
        assertTrue(f("Signed by")!!.contains("MetaVault Test Signer")); assertTrue(f("Issuer")!!.contains("Test Org"))
        assertEquals("present (5259 bytes)", f("Trusted timestamp")); assertEquals("present (2007 bytes)", f("Revocation (OCSP) data"))
        assertEquals("sha256, excludes 23666 bytes at offset 33", f("Hash binding"))
        assertEquals("c2pa.actions.v2, c2pa.hash.data, c2pa.icon", f("Assertions"))
    }

    @Test fun pngShowsCredentialsAndFileProperties() {
        val s = MetadataReader.scan(pngWithC2pa(), "image/png")
        assertNotNull(s.aiNote); assertEquals("ChatGPT gpt-image", field(s, "Software agent"))
        assertFalse(s.fields.any { it.name == "Chunk 'caBX'" })
        assertEquals("40x30", prop(s, "Image size")); assertEquals("8", prop(s, "Bit depth")); assertEquals("RGB", prop(s, "Colour type"))
        assertEquals("Deflate/Inflate", prop(s, "Compression")); assertEquals("Adaptive", prop(s, "Filter")); assertEquals("Noninterlaced", prop(s, "Interlace"))
        assertEquals("image/png", prop(s, "MIME type")); assertEquals("0.0", prop(s, "Megapixels"))
        assertTrue(prop(s, "Raw header")!!.startsWith("89 50 4E 47 0D 0A 1A 0A"))
        assertTrue(s.properties.none { it in s.fields })
    }

    @Test fun removeDeletesCredentialsKeepsPixelsAndSaysSo() {
        val input = pngWithC2pa()
        val r = MetadataProcessor.process(input, "image/png", Mode.REMOVE, emptyMap())
        assertEquals(pixels(input), pixels(r.bytes)); assertTrue(r.remaining.isEmpty())
        assertFalse(String(r.bytes, Charsets.ISO_8859_1).contains("caBX")); assertFalse(String(r.bytes, Charsets.ISO_8859_1).contains("ChatGPT"))
        assertTrue(r.notes.any { it.contains("C2PA") && it.contains("watermark") })
        assertNull(MetadataReader.scan(r.bytes, "image/png").aiNote)
    }

    @Test fun jpegMultiPacketApp11IsReassembled() {
        val m = manifest(); val cut = m.size / 2
        fun packet(z: Int, payload: ByteArray): ByteArray {
            val body = "JP".toByteArray() + byteArrayOf(0x00, 0x01) + be32(z) + payload
            return byteArrayOf(0xFF.toByte(), 0xEB.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body
        }
        val base = encode("jpg"); var at = 2
        if (base[2] == 0xFF.toByte() && base[3] == 0xE0.toByte()) at = 4 + ((base[4].toInt() and 0xFF) shl 8 or (base[5].toInt() and 0xFF))
        // second packet repeats an 8-byte box header (LBox,TBox) before its fragment
        val segs = packet(1, m.copyOfRange(0, cut)) + packet(2, be32(0) + "jumb".toByteArray() + m.copyOfRange(cut, m.size))
        val jpeg = base.copyOfRange(0, at) + segs + base.copyOfRange(at, base.size)
        val s = MetadataReader.scan(jpeg, "image/jpeg")
        assertEquals("ChatGPT gpt-image", field(s, "Software agent")); assertNotNull(s.aiNote)
        assertEquals("40x30", prop(s, "Image size")); assertEquals("Baseline DCT", prop(s, "Encoding process")); assertEquals("3", prop(s, "Colour components"))
        val r = MetadataProcessor.process(jpeg, "image/jpeg", Mode.REMOVE, emptyMap())
        assertTrue(r.remaining.isEmpty()); assertEquals(pixels(jpeg), pixels(r.bytes))
    }

    @Test fun webpDimensionsAreReadFromVp8x() {
        fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
        val vp8x = "VP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) + byteArrayOf(0, 0, 0, 0) + le24(1085) + le24(1447)
        val body = "WEBP".toByteArray() + vp8x
        val webp = "RIFF".toByteArray() + byteArrayOf(body.size.toByte(), 0, 0, 0) + body
        val s = MetadataReader.scan(webp, "image/webp")
        assertEquals("1086x1448", prop(s, "Image size")); assertEquals("1.6", prop(s, "Megapixels"))
    }

    @Test fun garbageAndTruncatedInputNeverCrash() {
        val m = manifest()
        assertNull(ContentCredentials.parse(ByteArray(40) { 7 }, 0, 40))
        for (n in intArrayOf(10, 40, 120, 600, m.size - 1)) ContentCredentials.parse(m.copyOf(n), 0, n) // must not throw
        val bad = pngWithC2pa().let { it.copyOf(it.size) }
        MetadataReader.scan(bad.copyOf(300), "image/png")
    }
}
