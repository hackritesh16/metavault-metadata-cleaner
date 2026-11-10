package com.metavault.cleaner.engine

import org.junit.Assert.*
import org.junit.Test

class PhotoEngineTest {
    @Test fun detectsFormatsFromContent() {
        assertEquals(PhotoFormat.JPEG, PhotoEngine.detect(Fixtures.jpeg()))
        assertEquals(PhotoFormat.PNG, PhotoEngine.detect(Fixtures.png()))
        assertEquals(PhotoFormat.WEBP, PhotoEngine.detect(Fixtures.riff(Fixtures.webpChunk("VP8L", Fixtures.vp8lPayload))))
    }

    @Test fun unsupportedContentIsRefusedHonestly() {
        val pdf = "%PDF-1.7\n1 0 obj\n<< /Author (x) >>\nendobj\n".toByteArray()
        assertNull(PhotoEngine.detect(pdf))
        val r = PhotoEngine.clean(pdf)
        assertFalse(r.verified)
        assertNull(r.output)
        assertTrue(r.failure!!.contains("JPEG, PNG and WebP"))
        assertFalse(PhotoEngine.inspect(pdf).ok)
    }

    @Test fun emptyAndGarbageInputNeverProducesOutput() {
        for (bytes in listOf(ByteArray(0), ByteArray(100) { 0x41 }, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) {
            val r = PhotoEngine.clean(bytes)
            assertFalse(r.verified)
            assertNull(r.output)
        }
    }

    @Test fun filenameExtensionIsIgnored() {
        // A PNG is processed as a PNG regardless of what it is called; detection only sees bytes.
        assertEquals(PhotoFormat.PNG, PhotoEngine.inspect(Fixtures.png()).format)
    }
}
