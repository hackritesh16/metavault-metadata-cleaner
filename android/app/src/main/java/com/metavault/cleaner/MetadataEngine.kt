package com.metavault.cleaner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class MetaField(val key: String, val value: String)

/** [removed] = fields actually removed, [remaining] = known fields still present after cleaning (re-checked). */
class CleanResult(val bytes: ByteArray, val removed: Int, val remaining: Int, val note: String)

/**
 * On-device metadata engine. Everything here is local; nothing is uploaded.
 * Supported: JPG/PNG/WEBP (EXIF via ExifInterface, no re-encoding), MP3 (ID3v2/ID3v1),
 * DOCX/XLSX/PPTX (docProps), PDF (Info dictionary + XMP via PdfBox-Android, experimental).
 * View-only: other audio/video via MediaMetadataRetriever.
 * Not implemented: video/audio cleaning other than MP3, XMP/IPTC blocks inside images.
 */
object MetadataEngine {
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_UNZIPPED = 512L * 1024 * 1024

    enum class Kind { IMAGE, MP3, OFFICE, PDF, OTHER_MEDIA, UNSUPPORTED }

    fun kindOf(name: String, mime: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mime in setOf("image/jpeg", "image/png", "image/webp") || ext in setOf("jpg", "jpeg", "png", "webp") -> Kind.IMAGE
            mime == "audio/mpeg" || ext == "mp3" -> Kind.MP3
            ext in setOf("docx", "xlsx", "pptx") -> Kind.OFFICE
            mime == "application/pdf" || ext == "pdf" -> Kind.PDF
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
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_SPEED
    )
    // MAKER_NOTE is removed but not displayed (binary). Orientation is kept on purpose so the photo is not rotated.
    private val REMOVE_TAGS = DISPLAY_TAGS.map { it.second } + GPS_TAGS + ExifInterface.TAG_MAKER_NOTE

    /** Fields the UI may let the user edit. Value null/blank removes the tag. */
    val EDITABLE_IMAGE_TAGS = linkedMapOf(
        "Artist" to ExifInterface.TAG_ARTIST,
        "Copyright" to ExifInterface.TAG_COPYRIGHT,
        "Description" to ExifInterface.TAG_IMAGE_DESCRIPTION,
        "Software" to ExifInterface.TAG_SOFTWARE,
        "Date taken" to ExifInterface.TAG_DATETIME_ORIGINAL
    )

    private fun inspectImage(bytes: ByteArray): List<MetaField> {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val out = mutableListOf<MetaField>()
        DISPLAY_TAGS.forEach { (label, tag) ->
            exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { out += MetaField(label, it) }
        }
        exif.latLong?.let { out += MetaField("GPS position", String.format(Locale.US, "%.6f, %.6f", it[0], it[1])) }
        return out
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
            return CleanResult(
                tmp.readBytes(), changed, left,
                "Pixels are not re-encoded. XMP/IPTC blocks, if present, are not covered yet."
            )
        } finally {
            tmp.delete()
        }
    }

    private fun cleanImage(context: Context, bytes: ByteArray): CleanResult =
        withExifFile(context, bytes) { exif, _ ->
            var removed = 0
            REMOVE_TAGS.forEach { tag ->
                if (exif.getAttribute(tag) != null) { exif.setAttribute(tag, null); removed++ }
            }
            removed
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

    private fun forEachZipEntry(bytes: ByteArray, block: (String, ByteArray) -> Unit) {
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val data = zin.readBytes()
                total += data.size
                check(total <= MAX_UNZIPPED) { "Archive is too large to process safely" }
                block(e.name, data)
                e = zin.nextEntry
            }
        }
    }

    private fun inspectOffice(bytes: ByteArray): List<MetaField> {
        val out = mutableListOf<MetaField>()
        forEachZipEntry(bytes) { name, data ->
            val tags = when (name) {
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
            forEachZipEntry(bytes) { name, data ->
                val tags = when (name) { CORE -> CORE_TAGS; APP -> APP_TAGS; else -> emptyList() }
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
                zout.putNextEntry(ZipEntry(name))
                zout.write(payload)
                zout.closeEntry()
            }
        }
        return CleanResult(
            out.toByteArray(), removed, 0,
            "Cleared author, last-modified-by, title, subject, keywords, company and manager. Created/modified dates and revision history inside the document are kept."
        )
    }

    // ---------- PDF (experimental) ----------
    private val STANDARD_PDF_KEYS = setOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped")

    private fun pdfDate(c: Calendar?): String? =
        c?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(it.time) }

    private fun <T> withPdf(context: Context, bytes: ByteArray, block: (PDDocument) -> T): T {
        PDFBoxResourceLoader.init(context.applicationContext)
        val doc = try {
            PDDocument.load(ByteArrayInputStream(bytes))
        } catch (e: InvalidPasswordException) {
            error("This PDF is password-protected, so MetaVault cannot read or clean it")
        }
        return doc.use(block)
    }

    private fun inspectPdf(context: Context, bytes: ByteArray): List<MetaField> = withPdf(context, bytes) { doc ->
        val info = doc.documentInformation
        val out = mutableListOf<MetaField>()
        fun add(key: String, value: String?) { if (!value.isNullOrBlank()) out += MetaField(key, value) }
        add("Title", info.title)
        add("Author", info.author)
        add("Subject", info.subject)
        add("Keywords", info.keywords)
        add("Creator", info.creator)
        add("Producer", info.producer)
        add("Created", pdfDate(info.creationDate))
        add("Modified", pdfDate(info.modificationDate))
        info.metadataKeys.filter { it !in STANDARD_PDF_KEYS }.forEach { add(it, info.getCustomMetadataValue(it)) }
        if (doc.documentCatalog.metadata != null) out += MetaField("XMP metadata", "present")
        out
    }

    private fun cleanPdf(context: Context, bytes: ByteArray): CleanResult {
        val (cleaned, removed) = withPdf(context, bytes) { doc ->
            val info = doc.documentInformation
            var count = listOf(info.title, info.author, info.subject, info.keywords, info.creator, info.producer).count { !it.isNullOrBlank() }
            if (info.creationDate != null) count++
            if (info.modificationDate != null) count++
            count += info.metadataKeys.count { it !in STANDARD_PDF_KEYS }
            if (doc.documentCatalog.metadata != null) count++
            doc.documentInformation = PDDocumentInformation()
            doc.documentCatalog.metadata = null
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray() to count
        }
        val remaining = inspectPdf(context, cleaned).size
        return CleanResult(
            cleaned, removed, remaining,
            "PDF Info fields and XMP were removed and the file was rewritten. Text, images and the document ID inside the pages are not touched. PDF cleaning is experimental."
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
            Kind.PDF -> inspectPdf(context, load(context, uri))
            Kind.MP3, Kind.OTHER_MEDIA -> inspectMedia(context, uri)
            Kind.UNSUPPORTED -> emptyList()
        }

    /** Returns null when cleaning is not implemented for this format; the file is never touched in that case. */
    fun clean(context: Context, uri: Uri, name: String, mime: String): CleanResult? =
        when (kindOf(name, mime)) {
            Kind.IMAGE -> cleanImage(context, load(context, uri))
            Kind.MP3 -> cleanMp3(load(context, uri))
            Kind.OFFICE -> cleanOffice(load(context, uri))
            Kind.PDF -> cleanPdf(context, load(context, uri))
            else -> null
        }
}
