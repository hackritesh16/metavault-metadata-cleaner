package com.metavault.cleaner.engine

enum class PhotoFormat(val label: String, val mime: String, val extension: String) {
    JPEG("JPEG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png"),
    WEBP("WebP", "image/webp", "webp")
}

/** One piece of metadata that was actually read from the file. [value] is null when it is deliberately not shown (e.g. GPS). */
data class MetadataField(val category: String, val name: String, val value: String? = null)

data class InspectionResult(
    val format: PhotoFormat?,
    val width: Int?,
    val height: Int?,
    val fields: List<MetadataField>,
    val error: String?
) {
    val ok: Boolean get() = error == null
}

/**
 * Truthful outcome of a cleaning run. [output] is non-null only when [verified] is true.
 * [removed] and [remaining] are measured by re-inspecting the output, not assumed.
 */
data class CleaningResult(
    val engine: String,
    val format: PhotoFormat?,
    val output: ByteArray?,
    val sourceSize: Int,
    val outputSize: Int,
    val width: Int,
    val height: Int,
    val removed: List<String>,
    val remaining: List<MetadataField>,
    val unverified: List<String>,
    val notes: List<String>,
    val verified: Boolean,
    val checks: List<String>,
    val failure: String?
)

internal fun failedResult(engine: String, format: PhotoFormat?, sourceSize: Int, reason: String) = CleaningResult(
    engine = engine, format = format, output = null, sourceSize = sourceSize, outputSize = 0,
    width = 0, height = 0, removed = emptyList(), remaining = emptyList(), unverified = emptyList(),
    notes = emptyList(), verified = false, checks = emptyList(), failure = reason
)

interface PhotoFormatEngine {
    val format: PhotoFormat
    val engineName: String
    fun inspect(bytes: ByteArray): InspectionResult
    fun clean(bytes: ByteArray): CleaningResult
}

internal class Analysis(
    val width: Int,
    val height: Int,
    val fields: List<MetadataField>,
    val orientation: Int?,
    val payloadDigest: ByteArray
)

/** Entry point. Format is decided from file content (magic bytes), never from name or MIME type. */
object PhotoEngine {
    /** Files are processed in memory; anything larger is refused rather than risking OOM. */
    const val MAX_BYTES = 64 * 1024 * 1024

    val supportedFormats: List<PhotoFormat> = PhotoFormat.values().toList()

    fun detect(bytes: ByteArray): PhotoFormat? = when {
        bytes.size >= 3 && bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8 && bytes.u8(2) == 0xFF -> PhotoFormat.JPEG
        bytes.size >= 8 && bytes.u8(0) == 0x89 && bytes.hasAscii(1, "PNG") &&
            bytes.u8(4) == 0x0D && bytes.u8(5) == 0x0A && bytes.u8(6) == 0x1A && bytes.u8(7) == 0x0A -> PhotoFormat.PNG
        bytes.size >= 12 && bytes.hasAscii(0, "RIFF") && bytes.hasAscii(8, "WEBP") -> PhotoFormat.WEBP
        else -> null
    }

    private fun engineFor(format: PhotoFormat): PhotoFormatEngine = when (format) {
        PhotoFormat.JPEG -> JpegEngine
        PhotoFormat.PNG -> PngEngine
        PhotoFormat.WEBP -> WebpEngine
    }

    fun inspect(bytes: ByteArray): InspectionResult {
        val f = detect(bytes) ?: return InspectionResult(null, null, null, emptyList(), UNSUPPORTED)
        return engineFor(f).inspect(bytes)
    }

    fun clean(bytes: ByteArray): CleaningResult {
        val f = detect(bytes) ?: return failedResult("none", null, bytes.size, UNSUPPORTED)
        return engineFor(f).clean(bytes)
    }

    private const val UNSUPPORTED = "Unsupported file. MetaVault currently cleans JPEG, PNG and WebP images only (detected from file content)."
}

/** Shared pipeline: analyze → rewrite → re-analyze output → compare. No output is returned unless every check passes. */
internal abstract class BaseEngine : PhotoFormatEngine {
    protected abstract val unverifiedNotes: List<String>
    protected abstract val keptNotes: List<String>

    abstract fun analyze(b: ByteArray): Analysis
    protected abstract fun rewrite(b: ByteArray, a: Analysis): ByteArray

    override fun inspect(bytes: ByteArray): InspectionResult = try {
        require(bytes.size <= PhotoEngine.MAX_BYTES) { "File is larger than the 64 MiB safety limit" }
        val a = analyze(bytes)
        InspectionResult(format, a.width, a.height, a.fields, null)
    } catch (e: IllegalArgumentException) {
        InspectionResult(format, null, null, emptyList(), e.message ?: "Malformed file")
    } catch (e: RuntimeException) {
        InspectionResult(format, null, null, emptyList(), "Could not read file (${e.javaClass.simpleName})")
    }

    override fun clean(bytes: ByteArray): CleaningResult = try {
        require(bytes.size <= PhotoEngine.MAX_BYTES) { "File is larger than the 64 MiB safety limit" }
        val a = analyze(bytes)
        val out = rewrite(bytes, a)
        val b = analyze(out)

        val checks = ArrayList<String>()
        check(b.width == a.width && b.height == a.height) {
            "dimensions changed (${a.width}x${a.height} -> ${b.width}x${b.height})"
        }
        checks += "Output re-parsed; dimensions unchanged (${b.width}x${b.height})"
        check(b.payloadDigest.contentEquals(a.payloadDigest)) { "image data differs from the source" }
        checks += "Image data and rendering headers are byte-identical to the source (SHA-256 compared)"
        val leftover = b.fields.filterNot { it.category == "EXIF" && it.name == "Orientation" }
        check(leftover.isEmpty()) { "metadata still present: ${leftover.joinToString { it.name }}" }
        checks += "Re-inspection of the output finds no metadata beyond the orientation tag"
        check((a.orientation ?: 1) == (b.orientation ?: 1)) { "image orientation would change" }
        checks += "Display orientation preserved"

        val remainingKeys = b.fields.map { it.category to it.name }.toSet()
        val removed = a.fields.filter { (it.category to it.name) !in remainingKeys }
            .map { "${it.category} · ${it.name}" }.distinct()
        val notes = ArrayList(keptNotes)
        if (b.orientation != null) {
            notes += "The orientation tag was kept in a minimal EXIF block so the image still displays upright. Nothing else from EXIF was kept."
        }
        CleaningResult(
            engine = engineName, format = format, output = out, sourceSize = bytes.size, outputSize = out.size,
            width = b.width, height = b.height, removed = removed, remaining = b.fields,
            unverified = unverifiedNotes, notes = notes, verified = true, checks = checks, failure = null
        )
    } catch (e: IllegalStateException) {
        failedResult(engineName, format, bytes.size, "Verification failed, so no output was produced: ${e.message}")
    } catch (e: IllegalArgumentException) {
        failedResult(engineName, format, bytes.size, e.message ?: "Malformed file")
    } catch (e: RuntimeException) {
        failedResult(engineName, format, bytes.size, "Unexpected error while processing (${e.javaClass.simpleName})")
    }
}
