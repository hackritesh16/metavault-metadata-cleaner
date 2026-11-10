package com.metavault.cleaner

import java.security.MessageDigest

enum class Mode { VIEW, EDIT, REMOVE }

data class CleanReport(
    val bytes: ByteArray,
    val mime: String,
    val sizeBefore: Int,
    val sizeAfter: Int,
    val hashBefore: String,
    val hashAfter: String,
    /** True when image data was copied byte-for-byte (pixels untouched). False when re-encoded. */
    val lossless: Boolean,
    val fieldsBefore: Int,
    /** Fields found when the output file was scanned again. */
    val remaining: List<MetaField>,
    val kept: List<String>,
    val notes: List<String>
) {
    val sizeChangePercent: Double get() = if (sizeBefore == 0) 0.0 else (sizeAfter - sizeBefore) * 100.0 / sizeBefore
}

object MetadataProcessor {
    fun isLossless(mime: String) = mime == "image/jpeg" || mime == "image/png"

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Lossless remove/edit for JPEG and PNG. Throws IllegalArgumentException for bad input. */
    fun process(input: ByteArray, mime: String, mode: Mode, edits: Map<String, String>): CleanReport {
        require(mode != Mode.VIEW) { "Nothing to process in view mode" }
        val before = MetadataReader.scan(input, mime)
        val applied = if (mode == Mode.EDIT) edits else emptyMap()
        MetadataEdits.validate(before.format, applied)?.let { throw IllegalArgumentException(it) }
        val notes = ArrayList<String>()
        val out: ByteArray = when (before.format) {
            "JPEG" -> {
                val r = JpegMetadataStripper.strip(input, true, applied)
                r.orientationKept?.let { notes += "Rotation (orientation $it) kept so the photo displays upright." }
                if (r.editedFields > 0) notes += "${r.editedFields} edited field(s) written."
                r.bytes
            }
            "PNG" -> {
                val r = PngMetadataStripper.strip(input, applied)
                if (r.editedFields > 0) notes += "${r.editedFields} edited field(s) written."
                r.bytes
            }
            else -> throw IllegalArgumentException("Lossless cleaning supports JPG and PNG only")
        }
        notes += "Image data copied byte-for-byte; pixels were not re-encoded."
        return report(input, out, mime, true, before.fields.size, notes)
    }

    /** Builds a report by hashing and re-scanning the real output. */
    fun report(before: ByteArray, after: ByteArray, mime: String, lossless: Boolean, fieldsBefore: Int, notes: List<String>): CleanReport {
        val scan = MetadataReader.scan(after, mime)
        return CleanReport(
            bytes = after, mime = mime, sizeBefore = before.size, sizeAfter = after.size,
            hashBefore = sha256(before), hashAfter = sha256(after), lossless = lossless,
            fieldsBefore = fieldsBefore, remaining = scan.fields, kept = scan.technical, notes = notes
        )
    }

    /** File name for the cleaned copy: optional custom base name, optional "processed_" prefix. */
    fun outputName(original: String, custom: String, prefix: Boolean, mime: String): String {
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val base = custom.trim().ifBlank { original.substringBeforeLast('.', original) }
        val safe = base.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim('.', ' ').ifBlank { "image" }.take(120)
        return (if (prefix) "processed_" else "") + safe + "." + ext
    }
}
