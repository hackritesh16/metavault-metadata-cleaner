package com.metavault.cleaner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ExportOptionsTest {
    @Test fun suffixIsIdempotentAndKeepsEditedStem() {
        for (stem in listOf("photo", "image", "document", "trip.day", "मेरा फोटो")) {
            assertEquals("${stem}_clean", cleanStem(stem))
            assertEquals(cleanStem(stem), cleanStem(cleanStem(stem)))
        }
        assertEquals("PHOTO_CLEAN", cleanStem("PHOTO_CLEAN"))
        assertTrue(validStem("day.1 edited"))
        for (bad in listOf("", "  ", " file", "..", "x/y", "x\\y", "a\nb")) assertFalse(validStem(bad))
    }

    @Test fun multipleRenamesRecheckCollisionsAndKeepFormat() = runBlocking {
        val asked = mutableListOf<String>()
        val result = exportNamed("photo", "PNG", { if (it == "free.PNG") emptyList() else listOf(it) },
            { true }, { conflict ->
                asked += conflict.name
                CollisionChoice.Rename(if (asked.size == 1) "other" else "free")
            }, { it }, { error("Unexpected overwrite") })
        assertEquals(listOf("photo.PNG", "other.PNG"), asked)
        assertEquals("free.PNG", result)
    }

    @Test fun skipAndAmbiguousOrProtectedNamesNeverWrite() = runBlocking {
        for (hits in listOf(listOf(1), listOf(1, 2))) {
            val skipped = exportNamed("image", "png", { hits }, { false },
                { assertFalse(it.canOverwrite); CollisionChoice.Skip },
                { error("Unexpected create") }, { error("Unexpected overwrite") })
            assertNull(skipped)
        }
    }

    @Test fun duplicateOverwriteRechecksIdentityBeforeWriting() = runBlocking {
        var queries = 0
        val written = exportNamed("image", "png", { queries++; listOf(1) }, { true },
            { assertTrue(it.canOverwrite); CollisionChoice.Overwrite },
            { error("Unexpected create") }, { id -> "updated $id" })
        assertEquals("updated 1", written)
        assertEquals(2, queries)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                exportNamed("image", "png", { listOf(++queries) }, { true }, { CollisionChoice.Overwrite },
                    { error("Unexpected create") }, { throw AssertionError("Wrong file was replaced") })
            }
        }
    }

    @Test fun providerAutoRenameReturnsToConflictChoices() = runBlocking {
        val result = exportNamed("photo", "jpg", { emptyList<String>() }, { true },
            { assertFalse(it.canOverwrite); CollisionChoice.Rename("chosen") },
            { if (it == "photo.jpg") throw NameUnavailable() else it }, { error("Unexpected overwrite") })
        assertEquals("chosen.jpg", result)
    }

    @Test fun failedReadBackRestoresBackupBeforeReportingFailure() {
        val backup = File.createTempFile("previous", ".bak")
        try {
            val old = "original data".toByteArray()
            backup.writeBytes(old)
            var target = old
            var writes = 0
            val ex = assertThrows(IllegalStateException::class.java) {
                writeWithRecovery("new".toByteArray(), backup, openWriter = {
                    writes++
                    object : ByteArrayOutputStream() {
                        override fun close() { target = if (writes == 1) byteArrayOf(1) else toByteArray() }
                    }
                }, openReader = { target.inputStream() })
            }
            assertTrue(ex.message!!.contains("restored"))
            assertArrayEquals(old, target)
            assertArrayEquals(old, backup.readBytes())
            assertEquals(2, writes)
        } finally { backup.delete() }
    }

    @Test fun deniedWriterNeverAttemptsRestoreOrReportsSuccess() {
        var attempts = 0
        assertThrows(SecurityException::class.java) {
            writeWithRecovery(byteArrayOf(1), File("unused"), { attempts++; throw SecurityException("denied") },
                { throw AssertionError("Unexpected read") })
        }
        assertEquals(1, attempts)
    }
}
