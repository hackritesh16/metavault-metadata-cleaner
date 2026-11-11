package com.metavault.cleaner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class MetaField(val key: String, val value: String)

/** [removed] = fields actually removed, [remaining] = known fields still present after cleaning (re-checked). */
class CleanResult(val bytes: ByteArray, val removed: Int, val remaining: Int, val note: String)

/**
 * On-device metadata engine. Everything here is local; nothing is uploaded.
 * Images (no re-encoding, pixels untouched):
 *  - JPG: all EXIF (incl. GPS/thumbnail), XMP, IPTC, C2PA, comments and other app blocks.
 *  - PNG: text chunks (AI prompts/parameters), XMP, eXIf, tIME, C2PA.
 *  - WEBP: EXIF, XMP and C2PA chunks.
 * Other formats: MP3 (ID3v2/ID3v1), DOCX/XLSX/PPTX (docProps).
 * View-only: other audio/video via MediaMetadataRetriever.
 * Not implemented: PDF, video/audio cleaning other than MP3, invisible pixel watermarks (not metadata).
 */
object MetadataEngine {
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_UNZIPPED = 512L * 1024 * 1024

    enum class Kind { IMAGE, MP3, OFFICE, OTHER_MEDIA, UNSUPPORTED }

    fun kindOf(name: String, mime: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mime in setOf("image/jpeg", "image/png", "image/webp") || ext in setOf("jpg", "jpeg", "png", "webp") -> Kind.IMAGE
            mime == "audio/mpeg" || ext == "mp3" -> Kind.MP3
            ext in setOf("docx", "xlsx", "pptx") -> Kind.OFFICE
            mime.startsWith("audio/") || mime.startsWith("video/") -> Kind.OTHER_MEDIA
            else -> Kind.UNSUPPORTED
        }
    }

    // ---------- Image (EXIF) ----------
    private val DISPLAY_TAGS = listOf(
        "Camera make" to ExifInterface.TAG_MAKE,
        "Camera model" to ExifInterface.TAG_MODEL,
        "Software" to ExifInterface.TAG_SOFTWARE,
        "Date" to ExifInterface.TAG_DATETIME,
        "Date taken" to ExifInterface.TAG_DATETIME_ORIGINAL,
        "Date digitized" to ExifInterface.TAG_DATETIME_DIGITIZED,
        "Artist" to ExifInterface.TAG_ARTIST,
        "Copyright" to ExifInterface.TAG_COPYRIGHT,
        "Description" to ExifInterface.TAG_IMAGE_DESCRIPTION,
        "User comment" to ExifInterface.TAG_USER_COMMENT,
        "Owner name" to ExifInterface.TAG_CAMERA_OWNER_NAME,
        "Body serial" to ExifInterface.TAG_BODY_SERIAL_NUMBER,
        "Lens make" to ExifInterface.TAG_LENS_MAKE,
        "Lens model" to ExifInterface.TAG_LENS_MODEL,
        "Lens serial" to ExifInterface.TAG_LENS_SERIAL_NUMBER,
        "Image unique ID" to ExifInterface.TAG_IMAGE_UNIQUE_ID,
        "Exposure time" to ExifInterface.TAG_EXPOSURE_TIME,
        "Aperture" to ExifInterface.TAG_F_NUMBER,
        "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        "Focal length" to ExifInterface.TAG_FOCAL_LENGTH,
        "Flash" to ExifInterface.TAG_FLASH
    )
    private val DISPLAY_TAG_SET = DISPLAY_TAGS.map { it.second }.toSet()
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_SPEED
    )
    // Used only by the fallback cleaner for files whose structure cannot be parsed. Orientation is kept on purpose.
    private val REMOVE_TAGS = DISPLAY_TAGS.map { it.second } + GPS_TAGS + ExifInterface.TAG_MAKER_NOTE

    /** Values ExifInterface derives from the image itself; they are not privacy metadata and are never counted as leftovers. */
    private val TECHNICAL_TAGS = setOf(ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH)

    /** Every EXIF tag ExifInterface knows (label to tag name), found from its public TAG_* constants. */
    private val ALL_EXIF: List<Pair<String, String>> by lazy {
        ExifInterface::class.java.fields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.startsWith("TAG_") }
            .filter { it.name != "TAG_MAKER_NOTE" && it.name != "TAG_XMP" }
            .mapNotNull { f ->
                val tag = runCatching { f.get(null) as? String }.getOrNull() ?: return@mapNotNull null
                f.name.removePrefix("TAG_").lowercase(Locale.ROOT).replace('_', ' ')
                    .replaceFirstChar { it.titlecase(Locale.ROOT) } to tag
            }
            .distinctBy { it.second }
    }

    /** Fields the UI may let the user edit. Value null/blank removes the tag. */
    val EDITABLE_IMAGE_TAGS = linkedMapOf(
        "Artist" to ExifInterface.TAG_ARTIST,
        "Copyright" to ExifInterface.TAG_COPYRIGHT,
        "Description" to ExifInterface.TAG_IMAGE_DESCRIPTION,
        "Software" to ExifInterface.TAG_SOFTWARE,
        "Date taken" to ExifInterface.TAG_DATETIME_ORIGINAL
    )

    private fun readExif(bytes: ByteArray): ExifInterface? =
        try { ExifInterface(ByteArrayInputStream(bytes)) } catch (e: Exception) { null }

    private fun readable(v: String?): String? {
        val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.any { it.code < 32 && it != '\n' && it != '\t' }) return "(binary data)"
        return t.take(300)
    }

    /** Number of privacy-relevant EXIF values in the file (technical tags excluded). */
    private fun exifFieldCount(bytes: ByteArray): Int {
        val exif = readExif(bytes) ?: return 0
        return ALL_EXIF.count { (_, tag) -> tag !in TECHNICAL_TAGS && !exif.getAttribute(tag).isNullOrBlank() }
    }

    /** Blocks that live outside EXIF: XMP, IPTC, comments, PNG text chunks (AI prompts), C2PA, ... */
    private fun blockFields(bytes: ByteArray): List<MetaField> {
        val out = mutableListOf<MetaField>()
        fun add(label: String, value: String, details: List<Pair<String, String>>) {
            out += MetaField(label, value)
            details.forEach { (k, v) -> out += MetaField("XMP \u00B7 $k", v) }
        }
        try {
            when {
                JpegSegmentStripper.looksLikeJpeg(bytes) ->
                    JpegSegmentStripper.scan(bytes).forEach { add(it.label, it.summary, it.details) }
                PngChunkStripper.looksLikePng(bytes) ->
                    PngChunkStripper.scan(bytes).forEach { add(it.label, it.value, it.details) }
                WebpChunkStripper.looksLikeWebp(bytes) ->
                    WebpChunkStripper.scan(bytes).forEach { add(it.label, it.value, it.details) }
            }
        } catch (e: IllegalArgumentException) {
            out += MetaField("Embedded blocks", "Could not be listed: the file structure is unusual")
        }
        return out
    }

    private fun inspectImage(bytes: ByteArray): List<MetaField> {
        val out = mutableListOf<MetaField>()
        val exif = readExif(bytes)
        if (exif != null) {
            DISPLAY_TAGS.forEach { (label, tag) ->
                exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { out += MetaField(label, it) }
            }
            exif.latLong?.let { out += MetaField("GPS position", String.format(Locale.US, "%.6f, %.6f", it[0], it[1])) }
            // Everything else ExifInterface can read, so the list matches what online EXIF viewers show.
            ALL_EXIF.forEach { (label, tag) ->
                if (tag in DISPLAY_TAG_SET) return@forEach
                readable(exif.getAttribute(tag))?.let { out += MetaField(label, it) }
            }
        }
        out += blockFields(bytes)
        return out
    }

    private fun removeKnownTags(exif: ExifInterface): Int {
        var removed = 0
        REMOVE_TAGS.forEach { tag ->
            if (exif.getAttribute(tag) != null) { exif.setAttribute(tag, null); removed++ }
        }
        return removed
    }

    private fun withExifFile(context: Context, bytes: ByteArray, block: (ExifInterface, File) -> Int): CleanResult {
        val tmp = File.createTempFile("mv_", ".img", context.cacheDir)
        try {
            tmp.writeBytes(bytes)
            val exif = ExifInterface(tmp.absolutePath)
            val changed = block(exif, tmp)
            exif.saveAttributes()
            val after = ExifInterface(tmp.absolutePath)
            val left = REMOVE_TAGS.count { after.getAttribute(it) != null }
            return CleanResult(tmp.readBytes(), changed, left, "Pixels are not re-encoded.")
        } finally {
            tmp.delete()
        }
    }

    /** Last resort for files whose container cannot be parsed: clears known EXIF tags only, and says so. */
    private fun legacyExifClean(context: Context, bytes: ByteArray, cause: IllegalArgumentException): CleanResult {
        val r = withExifFile(context, bytes) { exif, _ -> removeKnownTags(exif) }
        return CleanResult(
            r.bytes, r.removed, r.remaining,
            "Only known EXIF tags could be cleared because the file structure is unusual (${cause.message}). " +
                "XMP, text chunks and other embedded blocks may still be present."
        )
    }

    private fun imageNote(base: String, trailing: Int): String {
        var note = "Pixels are not re-encoded. $base"
        if (trailing > 0) note += " $trailing bytes stored after the image end (for example a motion-photo video) were kept."
        return note
    }

    private fun finishImage(
        before: ByteArray, after: ByteArray, exifBlocks: Int, otherBlocks: Int,
        leftOther: Int, trailing: Int, base: String
    ): CleanResult {
        val exifBefore = exifFieldCount(before)
        val removed = (if (exifBefore > 0) exifBefore else exifBlocks) + otherBlocks
        val left = exifFieldCount(after) + leftOther
        return CleanResult(after, removed, left, imageNote(base, trailing))
    }

    private fun cleanJpeg(context: Context, bytes: ByteArray): CleanResult {
        val orientation = readExif(bytes)?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
        val s = try {
            JpegSegmentStripper.strip(bytes, orientation)
        } catch (e: IllegalArgumentException) {
            return legacyExifClean(context, bytes, e)
        }
        return finishImage(
            bytes, s.bytes, s.removed.count { it.isExif }, s.removed.count { !it.isExif },
            JpegSegmentStripper.scan(s.bytes).count { !it.isExif }, s.trailingBytes,
            "Removed EXIF (including GPS and thumbnail), XMP, IPTC, C2PA credentials, comments and other embedded blocks. " +
                "Photo orientation was kept so the picture does not appear rotated."
        )
    }

    private fun cleanPng(context: Context, bytes: ByteArray): CleanResult {
        val s = try {
            PngChunkStripper.strip(bytes)
        } catch (e: IllegalArgumentException) {
            return legacyExifClean(context, bytes, e)
        }
        return finishImage(
            bytes, s.bytes, s.removed.count { it.isExif }, s.removed.count { !it.isExif },
            PngChunkStripper.scan(s.bytes).count { !it.isExif }, s.trailingBytes,
            "Removed text chunks (AI prompts and parameters), XMP, EXIF, C2PA credentials and timestamps. " +
                "Transparency, colour and animation data were kept."
        )
    }

    private fun cleanWebp(context: Context, bytes: ByteArray): CleanResult {
        val s = try {
            WebpChunkStripper.strip(bytes)
        } catch (e: IllegalArgumentException) {
            return legacyExifClean(context, bytes, e)
        }
        return finishImage(
            bytes, s.bytes, s.removed.count { it.isExif }, s.removed.count { !it.isExif },
            WebpChunkStripper.scan(s.bytes).count { !it.isExif }, s.trailingBytes,
            "Removed EXIF, XMP and C2PA chunks. Transparency, colour profile and animation data were kept."
        )
    }

    private fun cleanImage(context: Context, bytes: ByteArray): CleanResult = when {
        JpegSegmentStripper.looksLikeJpeg(bytes) -> cleanJpeg(context, bytes)
        PngChunkStripper.looksLikePng(bytes) -> cleanPng(context, bytes)
        WebpChunkStripper.looksLikeWebp(bytes) -> cleanWebp(context, bytes)
        else -> legacyExifClean(context, bytes, IllegalArgumentException("unrecognised image format"))
    }

    /** Edit selected tags; a null or blank value removes that tag. Keys are ExifInterface tag names. */
    fun editImage(context: Context, uri: Uri, edits: Map<String, String?>): CleanResult =
        withExifFile(context, load(context, uri)) { exif, _ ->
            edits.forEach { (tag, value) -> exif.setAttribute(tag, value?.takeIf { it.isNotBlank() }) }
            edits.size
        }

    // ---------- MP3 ----------
    internal fun cleanMp3(bytes: ByteArray): CleanResult {
        var start = 0
        var end = bytes.size
        var removed = 0
        if (end >= 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val size = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
            val footer = if ((bytes[5].toInt() and 0x10) != 0) 10 else 0
            val total = 10 + size + footer
            if (total in 10..end) { start = total; removed++ }
        }
        if (end - start >= 128 && bytes[end - 128] == 'T'.code.toByte() &&
            bytes[end - 127] == 'A'.code.toByte() && bytes[end - 126] == 'G'.code.toByte()) {
            end -= 128; removed++
        }
        return CleanResult(
            bytes.copyOfRange(start, end), removed, 0,
            "Removed ID3 tags (title, artist, album, cover art). Audio frames are untouched. APE tags are not covered yet."
        )
    }

    // ---------- Office (docx/xlsx/pptx) ----------
    private val CORE_TAGS = listOf("dc:creator", "cp:lastModifiedBy", "dc:title", "dc:subject", "cp:keywords", "dc:description", "cp:category")
    private val APP_TAGS = listOf("Company", "Manager")
    private const val CORE = "docProps/core.xml"
    private const val APP = "docProps/app.xml"

    private fun tagRegex(tag: String) = Regex("<$tag(\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /**
     * Iterates every entry in the ZIP archive, passing the original [ZipEntry] and its raw bytes to [block].
     * The original entry is provided so that callers can preserve fields such as the compression method,
     * extra data and comment — omitting these can corrupt ZIP-based formats like DOCX.
     */
    private fun forEachZipEntry(bytes: ByteArray, block: (ZipEntry, ByteArray) -> Unit) {
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val data = zin.readBytes()
                total += data.size
                check(total <= MAX_UNZIPPED) { "Archive is too large to process safely" }
                block(e, data)
                e = zin.nextEntry
            }
        }
    }

    private fun inspectOffice(bytes: ByteArray): List<MetaField> {
        val out = mutableListOf<MetaField>()
        forEachZipEntry(bytes) { entry, data ->
            val tags = when (entry.name) {
                CORE -> CORE_TAGS + listOf("dcterms:created", "dcterms:modified")
                APP -> APP_TAGS + listOf("Application", "AppVersion")
                else -> return@forEachZipEntry
            }
            val xml = String(data, Charsets.UTF_8)
            tags.forEach { tag ->
                tagRegex(tag).find(xml)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
                    ?.let { out += MetaField(tag.substringAfter(':'), unescape(it)) }
            }
        }
        return out
    }

    internal fun cleanOffice(bytes: ByteArray): CleanResult {
        var removed = 0
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            forEachZipEntry(bytes) { srcEntry, data ->
                val tags = when (srcEntry.name) { CORE -> CORE_TAGS; APP -> APP_TAGS; else -> emptyList() }
                var payload = data
                if (tags.isNotEmpty()) {
                    var xml = String(data, Charsets.UTF_8)
                    tags.forEach { tag ->
                        xml = tagRegex(tag).replace(xml) { m ->
                            if (m.groupValues[2].isNotBlank()) removed++
                            "<$tag${m.groupValues[1]}></$tag>"
                        }
                    }
                    payload = xml.toByteArray(Charsets.UTF_8)
                }
                // Preserve the original entry's compression method (STORED vs DEFLATED).
                // Creating a fresh ZipEntry(name) loses this, which can corrupt ZIP-based
                // Office formats that require certain entries (e.g. [Content_Types].xml) to
                // remain uncompressed.
                val outEntry = ZipEntry(srcEntry.name).apply {
                    method = srcEntry.method
                    // For STORED entries the size and CRC must be set before writing.
                    if (srcEntry.method == ZipEntry.STORED) {
                        size = payload.size.toLong()
                        compressedSize = payload.size.toLong()
                        crc = java.util.zip.CRC32().also { it.update(payload) }.value
                    }
                }
                zout.putNextEntry(outEntry)
                zout.write(payload)
                zout.closeEntry()
            }
        }
        return CleanResult(
            out.toByteArray(), removed, 0,
            "Cleared author, last-modified-by, title, subject, keywords, company and manager. Created/modified dates and revision history inside the document are kept."
        )
    }

    // ---------- Other audio/video (view only) ----------
    private fun inspectMedia(context: Context, uri: Uri): List<MetaField> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            return listOf(
                "Title" to MediaMetadataRetriever.METADATA_KEY_TITLE,
                "Artist" to MediaMetadataRetriever.METADATA_KEY_ARTIST,
                "Album" to MediaMetadataRetriever.METADATA_KEY_ALBUM,
                "Album artist" to MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                "Author" to MediaMetadataRetriever.METADATA_KEY_AUTHOR,
                "Composer" to MediaMetadataRetriever.METADATA_KEY_COMPOSER,
                "Writer" to MediaMetadataRetriever.METADATA_KEY_WRITER,
                "Genre" to MediaMetadataRetriever.METADATA_KEY_GENRE,
                "Year" to MediaMetadataRetriever.METADATA_KEY_YEAR,
                "Date" to MediaMetadataRetriever.METADATA_KEY_DATE,
                "Location" to MediaMetadataRetriever.METADATA_KEY_LOCATION,
                "Duration (ms)" to MediaMetadataRetriever.METADATA_KEY_DURATION,
                "Bitrate" to MediaMetadataRetriever.METADATA_KEY_BITRATE
            ).mapNotNull { (label, key) ->
                r.extractMetadata(key)?.takeIf { it.isNotBlank() }?.let { MetaField(label, it) }
            }
        } finally {
            r.release()
        }
    }

    // ---------- Public API ----------
    fun load(context: Context, uri: Uri): ByteArray {
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        check(size < 0 || size <= MAX_BYTES) { "File is larger than ${MAX_BYTES / (1024 * 1024)} MB limit" }
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read this file")
    }

    /** Returns the metadata currently inside the file. An empty list means none found, not that the format is unsupported. */
    fun inspect(context: Context, uri: Uri, name: String, mime: String): List<MetaField> =
        when (kindOf(name, mime)) {
            Kind.IMAGE -> inspectImage(load(context, uri))
            Kind.OFFICE -> inspectOffice(load(context, uri))
            Kind.MP3, Kind.OTHER_MEDIA -> inspectMedia(context, uri)
            Kind.UNSUPPORTED -> emptyList()
        }

    /** Returns null when cleaning is not implemented for this format; the file is never touched in that case. */
    fun clean(context: Context, uri: Uri, name: String, mime: String): CleanResult? =
        when (kindOf(name, mime)) {
            Kind.IMAGE -> cleanImage(context, load(context, uri))
            Kind.MP3 -> cleanMp3(load(context, uri))
            Kind.OFFICE -> cleanOffice(load(context, uri))
            else -> null
        }
}
