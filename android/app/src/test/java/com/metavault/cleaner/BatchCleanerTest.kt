package com.metavault.cleaner

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchCleanerTest {
    // Synthetic one-pixel images, each tested with one additional metadata block.
    private fun decode(text: String) = Base64.getDecoder().decode(text)
    private fun be(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    @Test fun jpegRemovesCommentAndPreservesOriginalEncoding() {
        val jpeg = decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAj/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFAEBAAAAAAAAAAAAAAAAAAAAAP/EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhEDEQA/AJ/AB//Z")
        val tagged = jpeg.copyOfRange(0, 2) + byteArrayOf(-1, -2, 0, 3, 65) + jpeg.copyOfRange(2, jpeg.size)
        // JVM fixtures supply the orientation normally read by AndroidX ExifInterface.
        assertArrayEquals(jpeg, prepareBatchCopy(BatchFormat.JPEG, tagged) { 1 }.bytes)
        val rotated = prepareBatchCopy(BatchFormat.JPEG, tagged) { 6 }.bytes
        JpegSegmentStripper.verifyCleaned(rotated, 6)
        assertThrows(IllegalStateException::class.java) { JpegSegmentStripper.verifyCleaned(rotated, 8) }
        assertThrows(IllegalStateException::class.java) { JpegSegmentStripper.verifyCleaned(tagged, 1) }
    }

    @Test fun pngRemovesTextAndPreservesPixelChunks() {
        val png = decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mNgYGAAAAAEAAHI6uv5AAAAAElFTkSuQmCC")
        val data = "Author\u0000Alice".toByteArray()
        val payload = "tEXt".toByteArray() + data
        val text = be(data.size) + payload + be(CRC32().apply { update(payload) }.value.toInt())
        val tagged = png.copyOfRange(0, 33) + text + png.copyOfRange(33, png.size)
        assertArrayEquals(png, prepareBatchCopy(BatchFormat.PNG, tagged).bytes)
    }

    @Test fun webpRemovesXmpAndPreservesEncodedFrame() {
        val webp = decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v02aAA=")
        val xmp = "XMP ".toByteArray() + le(4) + "test".toByteArray()
        val tagged = "RIFF".toByteArray() + le(webp.size - 8 + xmp.size) + webp.copyOfRange(8, webp.size) + xmp
        assertArrayEquals(webp, prepareBatchCopy(BatchFormat.WEBP, tagged).bytes)
    }

    @Test fun mp3ChecksId3BoundariesAndKeepsAudio() {
        val audio = byteArrayOf(-1, -5, -112, 0) + ByteArray(413)
        val v2 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0)
        val v1 = "TAG".toByteArray() + ByteArray(125)
        assertArrayEquals(audio, prepareBatchCopy(BatchFormat.MP3, v2 + audio + v1).bytes)
        assertThrows(IllegalStateException::class.java) { prepareBatchCopy(BatchFormat.MP3, audio + v1 + v1) }
        assertThrows(IllegalArgumentException::class.java) { prepareBatchCopy(BatchFormat.MP3, v2 + "hello".toByteArray()) }
    }

    private fun office(main: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, data) in mapOf("[Content_Types].xml" to "<Types/>", main to "<body/>",
                "docProps/core.xml" to "<dc:creator>Alice</dc:creator>")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data.toByteArray())
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    @Test fun officeChecksSelectedPropertiesAndMatchingDocumentPart() {
        for ((format, part) in mapOf(BatchFormat.DOCX to "word/document.xml",
            BatchFormat.XLSX to "xl/workbook.xml", BatchFormat.PPTX to "ppt/presentation.xml")) {
            val source = office(part)
            assertThrows(IllegalStateException::class.java) { MetadataEngine.verifyOfficeCopy(source, part) }
            val saved = prepareBatchCopy(format, source)
            MetadataEngine.verifyOfficeCopy(saved.bytes, part)
        }
        assertThrows(IllegalArgumentException::class.java) { prepareBatchCopy(BatchFormat.XLSX, office("word/document.xml")) }
    }

    @Test fun formatSelectionHandlesGenericMimeMissingExtensionAndConflicts() {
        assertEquals(BatchFormat.JPEG, BatchFormat.resolve("PHOTO.JPEG", "application/octet-stream"))
        assertEquals(BatchFormat.DOCX, BatchFormat.resolve("report", BatchFormat.DOCX.mime))
        assertNull(BatchFormat.resolve("x.pdf", "application/pdf"))
        assertNull(BatchFormat.resolve("x.png", "image/jpeg"))
    }

    @Test fun savedFileMustMatchIncludingLength() {
        val expected = ByteArray(18000) { it.toByte() }
        var deletes = 0
        val output = ByteArrayOutputStream()
        writeVerifiedCopy(expected, { output }, { output.toByteArray().inputStream() }, { deletes++; true })
        assertArrayEquals(expected, output.toByteArray())
        assertEquals(0, deletes)
        for (wrong in listOf(expected.copyOf(20), expected + 0.toByte(), expected.reversedArray())) {
            assertThrows(IllegalStateException::class.java) {
                writeVerifiedCopy(expected, { ByteArrayOutputStream() }, { wrong.inputStream() }, { deletes++; true })
            }
        }
        assertEquals(3, deletes)
    }

    @Test fun providerFailureReportsIncompleteCopyIfCleanupFails() {
        val e = assertThrows(IllegalStateException::class.java) {
            writeVerifiedCopy(byteArrayOf(1), { error("Write denied") }, { error("Unexpected read") }, { false })
        }
        assertTrue(e.message!!.contains("output folder"))
    }

    @Test fun mixedBatchContinuesAfterFailureSkipsDuplicatesAndStopsBetweenFiles() = runBlocking {
        val results = mutableListOf<BatchResult>()
        val processed = mutableListOf<String>()
        runBatch(listOf("bad", "pdf", "ok", "ok", "last", "unstarted"), { results.size == 4 },
            process = { name ->
                processed.add(name)
                if (name == "bad") error("Access revoked")
                BatchResult(name, if (name == "pdf") BatchStatus.SKIPPED else BatchStatus.SAVED, "")
            }, fallbackName = { it }, onResult = { results.add(it) })
        assertEquals(listOf("bad", "pdf", "ok", "last"), processed)
        assertEquals(listOf(BatchStatus.FAILED, BatchStatus.SKIPPED, BatchStatus.SAVED, BatchStatus.SAVED),
            results.map { it.status })
    }
}
