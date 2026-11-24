package com.metavault.cleaner

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal enum class BatchFormat(val extension: String, val mime: String) {
    JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp"),
    MP3("mp3", "audio/mpeg"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    companion object {
        fun resolve(name: String, mime: String?): BatchFormat? {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val byExtension = entries.find { it.extension == ext } ?: JPEG.takeIf { ext == "jpeg" }
            val byMime = entries.find { it.mime.equals(mime, ignoreCase = true) }
            if (byExtension != null && byMime != null && byExtension != byMime) return null
            return byExtension ?: byMime.takeIf { ext.isEmpty() }
        }
    }
}

internal data class PreparedCopy(val bytes: ByteArray, val scope: String)

internal fun prepareBatchCopy(
    format: BatchFormat, input: ByteArray, readJpegOrientation: (ByteArray) -> Int = MetadataEngine::jpegOrientation
): PreparedCopy {
    return when (format) {
        BatchFormat.JPEG -> {
            require(JpegSegmentStripper.looksLikeJpeg(input)) { "Expected a JPEG image" }
            val orientation = readJpegOrientation(input)
            val stripped = JpegSegmentStripper.strip(input, orientation)
            JpegSegmentStripper.verifyCleaned(stripped.bytes, orientation)
            PreparedCopy(stripped.bytes, "JPEG metadata blocks checked. Orientation and trailing data are kept.")
        }
        BatchFormat.PNG -> {
            val stripped = PngChunkStripper.strip(input)
            check(PngChunkStripper.scan(stripped.bytes).isEmpty())
            PreparedCopy(stripped.bytes, "Supported PNG metadata chunks checked. Trailing data is kept.")
        }
        BatchFormat.WEBP -> {
            val stripped = WebpChunkStripper.strip(input)
            check(WebpChunkStripper.scan(stripped.bytes).isEmpty())
            PreparedCopy(stripped.bytes, "Supported WebP metadata chunks checked. Trailing data is kept.")
        }
        BatchFormat.MP3 -> {
            val audio = MetadataEngine.cleanMp3(input).bytes
            require(audio.size >= 4 && audio[0] == 0xff.toByte() && (audio[1].toInt() and 0xe0) == 0xe0 &&
                (audio[1].toInt() and 0x06) == 0x02 && (audio[1].toInt() and 0x18) != 0x08 &&
                (audio[2].toInt() and 0xf0) != 0xf0 && (audio[2].toInt() and 0x0c) != 0x0c) {
                "Expected MPEG Layer III audio after the ID3 tags"
            }
            check(audio.size < 128 || !audio.copyOfRange(audio.size - 128, audio.size - 125)
                .contentEquals("TAG".toByteArray())) { "An ID3 tag remains" }
            PreparedCopy(audio, "ID3v1/v2 boundaries checked. Other tag types may remain.")
        }
        BatchFormat.DOCX, BatchFormat.XLSX, BatchFormat.PPTX -> {
            val bytes = MetadataEngine.cleanOffice(input).bytes
            val part = when (format) {
                BatchFormat.DOCX -> "word/document.xml"
                BatchFormat.XLSX -> "xl/workbook.xml"
                else -> "ppt/presentation.xml"
            }
            MetadataEngine.verifyOfficeCopy(bytes, part)
            PreparedCopy(bytes, "Selected Office properties checked. Dates, revisions and embedded content may remain.")
        }
    }
}

/** Throws unless [input] holds exactly [bytes]. */
internal fun verifyBytes(bytes: ByteArray, input: InputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var offset = 0
    while (offset < bytes.size) {
        val n = input.read(buffer, 0, minOf(buffer.size, bytes.size - offset))
        check(n > 0 && (0 until n).all { buffer[it] == bytes[offset + it] }) {
            "Saved copy differs from the prepared copy"
        }
        offset += n
    }
    check(input.read() == -1) { "Saved copy has unexpected trailing bytes" }
}

/** The destination must be a newly created document. Reopen it after closing the writer. */
internal fun writeVerifiedCopy(
    bytes: ByteArray, openWriter: () -> OutputStream, openReader: () -> InputStream, deleteNew: () -> Boolean
) {
    try {
        openWriter().use { it.write(bytes) }
        openReader().use { verifyBytes(bytes, it) }
    } catch (failure: Exception) {
        if (!runCatching(deleteNew).getOrDefault(false)) {
            throw IllegalStateException("Could not verify or delete the new file. Check the output folder.", failure)
        }
        throw failure
    }
}

internal enum class BatchStatus { SAVED, SKIPPED, FAILED }
internal data class BatchResult(val name: String, val status: BatchStatus, val detail: String)

internal suspend fun <T> runBatch(
    sources: List<T>, shouldStop: () -> Boolean, process: suspend (T) -> BatchResult,
    fallbackName: (T) -> String, onResult: (BatchResult) -> Unit
) {
    for (source in sources.distinct()) {
        if (shouldStop()) break
        val result = try {
            process(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BatchResult(fallbackName(source), BatchStatus.FAILED, e.localizedMessage ?: "Could not process file")
        }
        onResult(result)
    }
}
