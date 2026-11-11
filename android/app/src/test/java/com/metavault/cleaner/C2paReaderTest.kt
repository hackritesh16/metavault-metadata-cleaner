package com.metavault.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class C2paReaderTest {
    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    // ---- JUMBF boxes ----
    private fun box(type: String, payload: ByteArray) =
        be32(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    /** jumd payload: 16-byte type, toggles (0x03 = requestable + labelled), null-terminated label. */
    private fun jumd(label: String) = box("jumd", ByteArray(16) + byteArrayOf(0x03) + label.toByteArray() + byteArrayOf(0))

    private fun jumb(label: String, vararg children: ByteArray) = box("jumb", jumd(label) + cat(*children))

    // ---- CBOR encoding (only what the fixtures need) ----
    private fun text(s: String): ByteArray {
        val b = s.toByteArray()
        return (if (b.size < 24) byteArrayOf((0x60 or b.size).toByte()) else byteArrayOf(0x78, b.size.toByte())) + b
    }
    private fun map(vararg kv: ByteArray) = byteArrayOf((0xA0 or (kv.size / 2)).toByte()) + cat(*kv)
    private fun array(vararg items: ByteArray) = byteArrayOf((0x80 or items.size).toByte()) + cat(*items)
    private fun bytes(n: Int) = byteArrayOf((0x40 or n).toByte()) + ByteArray(n)

    private val aiUrl = "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"

    private val actions = map(
        text("actions"),
        array(
            map(
                text("action"), text("c2pa.created"),
                text("softwareAgent"), map(text("name"), text("ChatGPT")),
                text("digitalSourceType"), text(aiUrl)
            )
        )
    )
    private val claim = map(
        text("claim_generator_info"), map(text("name"), text("OpenAI Media Service API")),
        text("hash"), bytes(32)
    )

    // Same shape as a real manifest: store > manifest > (assertions, claim, signature).
    private val store = jumb(
        "c2pa",
        jumb(
            "urn:c2pa:test",
            jumb("c2pa.assertions", jumb("c2pa.actions.v2", box("cbor", actions))),
            jumb("c2pa.claim.v2", box("cbor", claim)),
            jumb("c2pa.signature", box("cbor", bytes(10)))
        )
    )

    private val dot = "\u00B7"

    @Test
    fun fields_readsAgentActionAndClaimGenerator_skipsBinary() {
        val f = C2paReader.fields(store)
        assertTrue(f.contains("C2PA $dot c2pa.actions.v2 $dot actions[0].action" to "c2pa.created"))
        assertTrue(f.contains("C2PA $dot c2pa.actions.v2 $dot actions[0].softwareAgent.name" to "ChatGPT"))
        assertTrue(f.contains("C2PA $dot c2pa.actions.v2 $dot actions[0].digitalSourceType" to aiUrl))
        assertTrue(f.contains("C2PA $dot c2pa.claim.v2 $dot claim_generator_info.name" to "OpenAI Media Service API"))
        assertTrue(f.any { it.first == "C2PA $dot c2pa.signature" })
        assertFalse(f.any { it.first.endsWith("hash") }) // the 32-byte hash is binary and must not be listed
    }

    @Test
    fun summary_flagsAiGeneratedAndNamesTheAgent() {
        val s = C2paReader.summary(C2paReader.fields(store), 1234)
        assertTrue(s.contains("AI-generated"))
        assertTrue(s.contains("ChatGPT"))
        assertTrue(s.contains("1234 bytes"))
    }

    @Test
    fun fields_damagedData_returnsWhatWasReadAndNeverThrows() {
        val damaged = store.copyOf(store.size - 40)
        C2paReader.fields(damaged) // must not throw
        assertTrue(C2paReader.fields(ByteArray(0)).isEmpty())
        assertTrue(C2paReader.fields(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)).isEmpty())
    }

    // ---- Integration with the PNG stripper: a ChatGPT-style file with a caBX chunk right after IHDR ----
    private fun chunk(type: String, data: ByteArray = ByteArray(0)) =
        be32(data.size) + type.toByteArray(Charsets.ISO_8859_1) + data + ByteArray(4)

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngWithC2pa() =
        cat(signature, chunk("IHDR", ByteArray(13)), chunk("caBX", store), chunk("IDAT", byteArrayOf(1, 2, 3, 4)), chunk("IEND"))

    @Test
    fun png_caBX_isListedWithReadableDetails_andRemoved() {
        val png = pngWithC2pa()
        val found = PngChunkStripper.scan(png).first { it.label == "C2PA content credentials" }
        assertTrue(found.value.contains("AI-generated"))
        assertTrue(found.details.any { it.second == "ChatGPT" })

        val cleaned = PngChunkStripper.strip(png)
        assertEquals(1, cleaned.removed.size)
        assertTrue(PngChunkStripper.scan(cleaned.bytes).isEmpty())
        assertFalse(String(cleaned.bytes, Charsets.ISO_8859_1).contains("ChatGPT"))
    }
}
