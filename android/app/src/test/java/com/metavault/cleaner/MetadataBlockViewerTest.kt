package com.metavault.cleaner

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.DeflaterOutputStream

class MetadataBlockViewerTest {
    @get:Rule val directory = TemporaryFolder()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun data(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun int32(n: Int, order: ByteOrder) = ByteBuffer.allocate(4).order(order).putInt(n).array()
    private fun marker(id: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return bytes(255, id, len shr 8, len) + payload
    }
    private fun pngChunk(name: String, payload: ByteArray) =
        int32(payload.size, ByteOrder.BIG_ENDIAN) + data(name) + payload + ByteArray(4)
    private val png = bytes(137, 80, 78, 71, 13, 10, 26, 10)
    private fun webpChunk(name: String, payload: ByteArray) =
        data(name) + int32(payload.size, ByteOrder.LITTLE_ENDIAN) + payload + ByteArray(payload.size % 2)
    private fun webp(chunks: ByteArray) = data("RIFF") + int32(chunks.size + 4, ByteOrder.LITTLE_ENDIAN) + data("WEBP") + chunks

    private fun inspect(source: ByteArray, test: (FileChannel, BlockIndex) -> Unit) {
        snapshotChannel(directory.root).use { file ->
            file.snapshot(source.inputStream(), {})
            test(file, MetadataBlockIndex(file).scan())
            assertArrayEquals(source, file.readRange(0, source.size))
        }
        assertEquals(0, directory.root.listFiles()!!.size)
    }

    @Test fun indexesAllAppMarkersCommentsAndRepeatedSegmentsAcrossScans() {
        val out = ByteArrayOutputStream()
        out.write(bytes(255, 216))
        repeat(16) { out.write(marker(224 + it, bytes(it))) }
        out.write(marker(218, byteArrayOf()))
        out.write(bytes(1, 255, 0, 226, 255, 209, 8, 255)) // stuffing, restart, marker fill
        out.write(marker(225, data("Exif\u0000\u0000")))
        out.write(marker(225, data("http://ns.adobe.com/xap/1.0/\u0000<xml/>")))
        out.write(marker(226, data("ICC_PROFILE\u0000") + bytes(2, 3)))
        out.write(marker(254, data("hello")))
        out.write(bytes(255, 217))
        inspect(out.toByteArray()) { file, index ->
            assertNull(index.warning)
            assertEquals((0..15).map { "APP$it" }, index.blocks.take(16).map { it.type })
            assertTrue(index.blocks.take(16).all { it.name == "Unknown Block" })
            assertEquals(listOf("EXIF", "XMP", "ICC Profile", "Comment"), index.blocks.drop(16).map { it.name })
            assertEquals("<xml/>", file.blockPreview(index.blocks[17]))
            assertEquals("ICC segment 2 of 3", file.blockPreview(index.blocks[18]))
            assertEquals("hello", file.blockPreview(index.blocks[19]))
        }
    }

    @Test fun decodesExifWithinItsOwnBlockAndRejectsInvalidOffsets() {
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val tiff = ByteBuffer.allocate(35).order(order).apply {
                putShort(if (order == ByteOrder.BIG_ENDIAN) 0x4d4d else 0x4949)
                putShort(42); putInt(8); putShort(1)
                putShort(0x010f); putShort(2); putInt(9); putInt(26); putInt(0)
                put(data("MetaCam!\u0000"))
            }.array()
            val bad = tiff.copyOf().also { ByteBuffer.wrap(it).order(order).putInt(18, -1) }
            inspect(png + pngChunk("eXIf", tiff) + pngChunk("eXIf", bad) + pngChunk("IEND", byteArrayOf())) { file, index ->
                assertNull(index.warning)
                assertEquals("Camera make: MetaCam!", file.blockPreview(index.blocks[0]))
                assertNull(file.blockPreview(index.blocks[1]))
            }
        }
    }

    @Test fun pngTextCompressionIsBoundedAndImageDataExcluded() {
        val packed = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(ByteArray(2_000_000) { 65 }) }
        }.toByteArray()
        inspect(png + pngChunk("IDAT", bytes(1, 2)) + pngChunk("zTXt", data("Note\u0000\u0000") + packed) +
            pngChunk("iTXt", data("XML:com.adobe.xmp\u0000\u0000\u0000en\u0000name\u0000Hi")) +
            pngChunk("vEND", bytes(0, 255)) + pngChunk("IEND", byteArrayOf())) { file, index ->
            assertNull(index.warning)
            assertEquals(listOf("PNG text", "XMP", "Unknown Block"), index.blocks.map { it.name })
            assertEquals("Note\n" + "A".repeat(4096), file.blockPreview(index.blocks.first()))
            assertEquals("XML:com.adobe.xmp\nHi", file.blockPreview(index.blocks[1]))
            assertEquals("00 FF", file.readRange(index.blocks.last().offset, 2).blockDump(false))
        }
    }

    @Test fun webpPagingAndExportCoverEveryByteWithoutPaddingOrChangingTheOriginal() {
        val payload = ByteArray(2_000_003) { (it * 31).toByte() }
        inspect(webp(webpChunk("VP8 ", bytes(12)) + webpChunk("vEND", payload) + webpChunk("XMP ", data("text")))) { file, index ->
            assertNull(index.warning)
            assertEquals(listOf("Unknown Block", "XMP"), index.blocks.map { it.name })
            val block = index.blocks.first()
            assertEquals(payload.size.toLong(), block.size)
            for (offset in listOf(0, BLOCK_PAGE, payload.size - 3)) {
                val count = minOf(BLOCK_PAGE, payload.size - offset)
                assertArrayEquals(payload.copyOfRange(offset, offset + count), file.readRange(block.offset + offset, count))
            }
            val out = ByteArrayOutputStream()
            file.exportBlock(block, out) {}
            assertArrayEquals(payload, out.toByteArray())
            assertEquals("text", file.blockPreview(index.blocks.last()))
        }
        assertEquals("A.\n.~", bytes(65, 255, 0, 126).blockDump(true, 2))
    }

    @Test fun exposesAppendedBytesAfterEachContainersLogicalEnd() {
        val tail = bytes(222, 173, 0, 255)
        for (container in listOf(bytes(255, 216, 255, 217), png + pngChunk("IEND", byteArrayOf()), webp(byteArrayOf()))) {
            inspect(container + tail) { file, index ->
                assertNull(index.warning)
                val block = index.blocks.single()
                assertEquals("Trailing data", block.name)
                assertArrayEquals(tail, file.readRange(block.offset, block.size.toInt()))
            }
        }
    }

    @Test fun damagedLengthsKeepEarlierCompleteRecords() {
        val good = marker(224, data("JFIF\u0000") + ByteArray(9))
        for (bad in listOf(bytes(255, 225, 0, 1), bytes(255, 225, 255, 255), bytes(255))) {
            inspect(bytes(255, 216) + good + bad) { _, index ->
                assertNotNull(index.warning)
                assertEquals("JFIF", index.blocks.single().name)
            }
        }
        inspect(png + pngChunk("tEXt", bytes(65)) + bytes(255, 255, 255, 255) + data("xxxx") + ByteArray(4)) { _, index ->
            assertNotNull(index.warning)
            assertEquals(1, index.blocks.size)
        }
        inspect(webp(webpChunk("XMP ", bytes(1))).dropLast(1).toByteArray()) { _, index ->
            assertNotNull(index.warning)
            assertTrue(index.blocks.isEmpty())
        }
    }

    @Test fun oversizedSourcesBlockFloodsAndUnknownSignaturesHaveExplicitLimits() {
        snapshotChannel(directory.root).use { file ->
            assertThrows(IllegalArgumentException::class.java) { file.snapshot(ByteArray(100).inputStream(), {}, 99) }
            assertEquals(0, file.size())
        }
        inspect(data("arbitrary input")) { _, index -> assertNotNull(index.warning) }
        val flood = ByteArrayOutputStream().apply {
            write(bytes(255, 216))
            repeat(8200) { write(marker(239, byteArrayOf())) }
        }.toByteArray()
        inspect(flood) { _, index ->
            assertNotNull(index.warning)
            assertEquals(8192, index.blocks.size)
        }
    }
}
