package com.metavault.cleaner

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEngineTest {
    @Test
    fun kindOf_detectsFormats() {
        assertEquals(MetadataEngine.Kind.IMAGE, MetadataEngine.kindOf("a.JPG", "application/octet-stream"))
        assertEquals(MetadataEngine.Kind.MP3, MetadataEngine.kindOf("song.mp3", "audio/mpeg"))
        assertEquals(MetadataEngine.Kind.OFFICE, MetadataEngine.kindOf("report.docx", "application/octet-stream"))
        assertEquals(MetadataEngine.Kind.UNSUPPORTED, MetadataEngine.kindOf("x.pdf", "application/pdf"))
    }

    @Test
    fun cleanMp3_removesId3v2AndId3v1_keepsAudio() {
        val audio = ByteArray(300) { 0x55 }
        val id3v2Body = ByteArray(20) { 1 }
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 20)
        val id3v1 = ByteArray(128).also { it[0] = 'T'.code.toByte(); it[1] = 'A'.code.toByte(); it[2] = 'G'.code.toByte() }
        val result = MetadataEngine.cleanMp3(header + id3v2Body + audio + id3v1)
        assertEquals(2, result.removed)
        assertTrue(result.bytes.contentEquals(audio))
    }

    @Test
    fun cleanOffice_clearsAuthorFields() {
        val core = "<cp:coreProperties xmlns:cp=\"x\" xmlns:dc=\"y\"><dc:creator>Alice</dc:creator><cp:lastModifiedBy>Alice</cp:lastModifiedBy></cp:coreProperties>"
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { z ->
            z.putNextEntry(ZipEntry("docProps/core.xml")); z.write(core.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml")); z.write("<w:document>Alice wrote this</w:document>".toByteArray()); z.closeEntry()
        }
        val result = MetadataEngine.cleanOffice(buf.toByteArray())
        assertEquals(2, result.removed)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(result.bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) { entries[e.name] = String(zin.readBytes()); e = zin.nextEntry }
        }
        assertFalse(entries.getValue("docProps/core.xml").contains("Alice"))
        assertTrue(entries.getValue("word/document.xml").contains("Alice wrote this"))
    }
}
