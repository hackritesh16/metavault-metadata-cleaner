package com.metavault.cleaner

/** One piece of metadata found in a file. [key] is non-null when the user can edit it. */
data class MetaField(val group: String, val name: String, val value: String, val key: String? = null)

data class MetadataScan(
    val format: String,
    val fields: List<MetaField>,
    /** Structural data kept on purpose because removing it would change how the image displays. */
    val technical: List<String> = emptyList(),
    val hasGps: Boolean = false
) {
    fun valueOf(key: String): String? = fields.firstOrNull { it.key == key }?.value
}

object MetadataEdits {
    const val MAX_LEN = 1000
    private val pngKeys = listOf("Title", "Author", "Description", "Copyright", "Software", "Comment")

    /** Editable field keys for a format, in display order. Empty when editing is unsupported. */
    fun keys(format: String): List<String> = when (format) {
        "JPEG" -> ExifTags.editable.keys.toList()
        "PNG" -> pngKeys
        else -> emptyList()
    }

    fun validate(format: String, edits: Map<String, String>): String? {
        for ((k, v) in edits) {
            if (k !in keys(format)) return "\"$k\" cannot be edited in $format files."
            if (v.length > MAX_LEN) return "$k is too long (max $MAX_LEN characters)."
            if (format == "JPEG" && k == "DateTime" && v.isNotBlank() &&
                !Regex("""\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}""").matches(v.trim())) {
                return "DateTime must look like 2024:05:17 14:30:00."
            }
        }
        return null
    }
}

/** Minimal bounds-checked TIFF/EXIF reader. */
internal class TiffReader(private val d: ByteArray, private val base: Int, private val end: Int) {
    private var big = true

    fun valid(): Boolean {
        if (base < 0 || end > d.size || end - base < 8) return false
        big = when {
            d[base] == 'M'.code.toByte() && d[base + 1] == 'M'.code.toByte() -> true
            d[base] == 'I'.code.toByte() && d[base + 1] == 'I'.code.toByte() -> false
            else -> return false
        }
        return s(base + 2) == 42
    }

    fun s(i: Int): Int {
        if (i < base || i + 2 > end) return -1
        val a = d[i].toInt() and 0xFF; val b = d[i + 1].toInt() and 0xFF
        return if (big) (a shl 8) or b else (b shl 8) or a
    }

    fun l(i: Int): Long {
        if (i < base || i + 4 > end) return -1
        val b = IntArray(4) { d[i + it].toInt() and 0xFF }
        return if (big) (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        else (b[3].toLong() shl 24) or (b[2].toLong() shl 16) or (b[1].toLong() shl 8) or b[0].toLong()
    }

    fun firstIfd(): Long = l(base + 4)

    private fun typeSize(type: Int) = when (type) { 1, 2, 7 -> 1; 3 -> 2; 4, 9 -> 4; 5, 10 -> 8; else -> 0 }

    /** Calls [visit] for each entry: tag, type, count, absolute value position (-1 if out of bounds). */
    fun walkIfd(offset: Long, visit: (tag: Int, type: Int, count: Long, valuePos: Int) -> Unit) {
        if (offset < 8 || offset > Int.MAX_VALUE) return
        val b = base + offset.toInt()
        val n = s(b)
        if (n <= 0) return
        for (i in 0 until minOf(n, 256)) {
            val e = b + 2 + i * 12
            if (e + 12 > end) return
            val type = s(e + 2); val count = l(e + 4)
            val total = count * typeSize(type)
            val pos = when {
                typeSize(type) == 0 || count < 0 || total > end -> -1
                total <= 4 -> e + 8
                else -> { val o = l(e + 8); if (o >= 0 && base + o + total <= end) (base + o).toInt() else -1 }
            }
            visit(s(e), type, count, pos)
        }
    }

    fun sub(offset: Long): Int = if (offset in 8..Int.MAX_VALUE.toLong()) base + offset.toInt() else -1

    fun format(tag: Int, type: Int, count: Long, pos: Int, gps: Boolean): String? {
        if (pos < 0) return null
        val size = typeSize(type); val total = (count * size).toInt()
        fun r(i: Int): Pair<Long, Long> = if (type == 5) l(pos + i * 8) to l(pos + i * 8 + 4) else {
            val a = l(pos + i * 8).toInt().toLong(); val b = l(pos + i * 8 + 4).toInt().toLong(); a to b
        }
        fun rat(i: Int): Double { val (a, b) = r(i); return if (b == 0L) Double.NaN else a.toDouble() / b }
        return when (type) {
            2 -> clean(String(d, pos, total, Charsets.UTF_8))
            1, 7 -> {
                var s = d.copyOfRange(pos, pos + total)
                if (tag == 0x9286 && s.size > 8) s = s.copyOfRange(8, s.size) // UserComment charset prefix
                if (s.size <= 64 && s.all { (it.toInt() and 0xFF) in 32..126 || it.toInt() == 0 }) clean(String(s, Charsets.ISO_8859_1)).ifBlank { "(${total} bytes)" }
                else "($total bytes)"
            }
            3, 4, 9 -> (0 until minOf(count.toInt(), 8)).joinToString(", ") {
                when (type) { 3 -> s(pos + it * 2).toString(); 4 -> l(pos + it * 4).toString(); else -> l(pos + it * 4).toInt().toString() }
            } + if (count > 8) ", …" else ""
            5, 10 -> if (gps && count == 3L && (tag == 2 || tag == 4)) {
                val deg = rat(0) + rat(1) / 60.0 + rat(2) / 3600.0
                String.format(java.util.Locale.US, "%.6f", deg)
            } else (0 until minOf(count.toInt(), 4)).joinToString(", ") {
                val v = rat(it)
                if (v.isNaN()) "n/a" else String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
            }
            else -> null
        }
    }

    private fun clean(s: String) = s.trimEnd('\u0000').filter { it >= ' ' || it == '\n' }.trim().take(300)
}

object MetadataReader {
    private val ifd0Names = mapOf(
        0x010E to "ImageDescription", 0x010F to "Make", 0x0110 to "Model", 0x0112 to "Orientation",
        0x011A to "XResolution", 0x011B to "YResolution", 0x0128 to "ResolutionUnit", 0x0131 to "Software",
        0x0132 to "DateTime", 0x013B to "Artist", 0x8298 to "Copyright", 0x0213 to "YCbCrPositioning"
    )
    private val exifNames = mapOf(
        0x829A to "ExposureTime", 0x829D to "FNumber", 0x8827 to "ISO", 0x9000 to "ExifVersion",
        0x9003 to "DateTimeOriginal", 0x9004 to "DateTimeDigitized", 0x9209 to "Flash", 0x920A to "FocalLength",
        0x927C to "MakerNote", 0x9286 to "UserComment", 0xA002 to "PixelXDimension", 0xA003 to "PixelYDimension",
        0xA420 to "ImageUniqueID", 0xA430 to "CameraOwnerName", 0xA431 to "BodySerialNumber",
        0xA433 to "LensMake", 0xA434 to "LensModel", 0xA435 to "LensSerialNumber"
    )
    private val gpsNames = mapOf(
        1 to "GPSLatitudeRef", 2 to "GPSLatitude", 3 to "GPSLongitudeRef", 4 to "GPSLongitude",
        5 to "GPSAltitudeRef", 6 to "GPSAltitude", 7 to "GPSTimeStamp", 0x1D to "GPSDateStamp"
    )

    fun scan(data: ByteArray, mime: String): MetadataScan = when {
        JpegMetadataStripper.looksLikeJpeg(data) -> scanJpeg(data)
        isPng(data) -> scanPng(data)
        isWebp(data) -> scanWebp(data)
        else -> MetadataScan(
            "Unsupported", emptyList(),
            listOf("This format is not readable yet (supported: JPG, PNG, WEBP).")
        )
    }

    fun isPng(d: ByteArray) = d.size > 8 && d[0] == 0x89.toByte() && d[1] == 'P'.code.toByte() && d[2] == 'N'.code.toByte() && d[3] == 'G'.code.toByte()
    fun isWebp(d: ByteArray) = d.size > 12 && String(d, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(d, 8, 4, Charsets.ISO_8859_1) == "WEBP"

    /** Reads a TIFF block; returns true if GPS data was found. */
    private fun readTiff(d: ByteArray, from: Int, to: Int, out: MutableList<MetaField>, editableIfd0: Boolean): Boolean {
        val t = TiffReader(d, from, to)
        if (!t.valid()) return false
        var gps = false
        fun ifd(off: Long, group: String, depth: Int, names: Map<Int, String>) {
            if (depth > 3) return
            val pointers = ArrayList<Pair<Int, Long>>()
            t.walkIfd(off) { tag, type, count, pos ->
                if (group == "Camera" && (tag == 0x8769 || tag == 0x8825)) { pointers += tag to t.l(pos.takeIf { it >= 0 } ?: return@walkIfd) ; return@walkIfd }
                if (tag == 0xA005 || tag == 0x0201 || tag == 0x0202) return@walkIfd
                val value = t.format(tag, type, count, pos, group == "GPS") ?: return@walkIfd
                val name = names[tag] ?: "Tag 0x%04X".format(tag)
                val key = if (group == "Camera" && editableIfd0 && ExifTags.editable[name] == tag) name else null
                out += MetaField(group, name, value, key)
            }
            for ((tag, ptr) in pointers) {
                if (tag == 0x8769) ifd(ptr, "Exif", depth + 1, exifNames)
                else { val before = out.size; ifd(ptr, "GPS", depth + 1, gpsNames); if (out.size > before) gps = true }
            }
        }
        ifd(t.firstIfd(), "Camera", 0, ifd0Names)
        return gps
    }

    private fun scanJpeg(d: ByteArray): MetadataScan {
        val fields = ArrayList<MetaField>(); val tech = ArrayList<String>(); var gps = false
        var pos = 2
        fun u(i: Int) = d[i].toInt() and 0xFF
        fun startsWith(from: Int, to: Int, s: String) = to - from >= s.length && String(d, from, s.length, Charsets.ISO_8859_1) == s
        loop@ while (pos < d.size) {
            if (u(pos) != 0xFF) break
            while (pos < d.size && u(pos) == 0xFF) pos++
            if (pos >= d.size) break
            val marker = u(pos++)
            if (marker == 0xD9) { val trailing = d.size - pos; if (trailing > 0) fields += MetaField("Trailing data", "Bytes after image end", "$trailing bytes (may hide extra images or video)"); break }
            if (marker == 0x01 || marker in 0xD0..0xD8) continue
            if (pos + 2 > d.size) break
            val len = (u(pos) shl 8) or u(pos + 1)
            if (len < 2 || pos + len > d.size) break
            val start = pos + 2; val end = pos + len
            when {
                marker == 0xE0 -> tech += "JFIF header"
                marker == 0xEE -> tech += "Adobe colour transform"
                marker == 0xFE -> fields += MetaField("Comment", "JPEG comment", String(d, start, end - start, Charsets.UTF_8).trim().take(300))
                marker == 0xE1 && startsWith(start, end, "Exif\u0000\u0000") ->
                    if (readTiff(d, start + 6, end, fields, true)) gps = true
                marker == 0xE1 && startsWith(start, end, "http://ns.adobe.com/xap/1.0/") -> fields += MetaField("XMP", "XMP packet", "${end - start} bytes")
                marker == 0xE2 && startsWith(start, end, "ICC_PROFILE") -> tech += "ICC colour profile"
                marker == 0xED -> fields += MetaField("IPTC / Photoshop", "Photoshop resources", "${end - start} bytes")
                marker in 0xE1..0xEF -> fields += MetaField("Other", "APP${marker - 0xE0} segment", "${end - start} bytes")
            }
            pos = end
            if (marker == 0xDA) pos = JpegMetadataStripper.skipScan(d, pos)
        }
        return MetadataScan("JPEG", fields, tech.distinct(), gps)
    }

    private fun scanPng(d: ByteArray): MetadataScan {
        val fields = ArrayList<MetaField>(); val tech = ArrayList<String>(); var gps = false
        val keys = MetadataEdits.keys("PNG")
        fun be(i: Int) = ((d[i].toInt() and 0xFF).toLong() shl 24) or ((d[i + 1].toInt() and 0xFF).toLong() shl 16) or
            ((d[i + 2].toInt() and 0xFF).toLong() shl 8) or (d[i + 3].toInt() and 0xFF).toLong()
        var pos = 8
        while (pos + 12 <= d.size) {
            val len = be(pos); val type = String(d, pos + 4, 4, Charsets.ISO_8859_1)
            if (len < 0 || pos + 12 + len > d.size) break
            val s = pos + 8; val e = s + len.toInt()
            when (type) {
                "tEXt" -> { val z = (s until e).firstOrNull { d[it].toInt() == 0 }
                    if (z != null) { val k = String(d, s, z - s, Charsets.ISO_8859_1); fields += MetaField("Text", k, String(d, z + 1, e - z - 1, Charsets.ISO_8859_1).take(300), k.takeIf { it in keys }) } }
                "iTXt" -> { val z = (s until e).firstOrNull { d[it].toInt() == 0 }
                    if (z != null) { val k = String(d, s, z - s, Charsets.ISO_8859_1)
                        if (k == "XML:com.adobe.xmp") fields += MetaField("XMP", "XMP packet", "${e - s} bytes")
                        else { val compressed = z + 1 < e && d[z + 1].toInt() != 0
                            var p = z + 3; var nulls = 0
                            while (p < e && nulls < 2) { if (d[p].toInt() == 0) nulls++; p++ }
                            fields += MetaField("Text", k, if (compressed) "(compressed text)" else String(d, p.coerceAtMost(e), (e - p).coerceAtLeast(0), Charsets.UTF_8).take(300), k.takeIf { it in keys && !compressed }) } } }
                "zTXt" -> { val z = (s until e).firstOrNull { d[it].toInt() == 0 }; if (z != null) fields += MetaField("Text", String(d, s, z - s, Charsets.ISO_8859_1), "(compressed text)") }
                "eXIf" -> if (readTiff(d, s, e, fields, false)) gps = true
                "tIME" -> if (len == 7L) fields += MetaField("Timestamp", "Last modified", "%04d-%02d-%02d %02d:%02d:%02d".format(((d[s].toInt() and 0xFF) shl 8) or (d[s + 1].toInt() and 0xFF), d[s + 2].toInt(), d[s + 3].toInt(), d[s + 4].toInt(), d[s + 5].toInt(), d[s + 6].toInt()))
                "IHDR", "PLTE", "IDAT", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs", "acTL", "fcTL", "fdAT", "cICP" -> if (type != "IDAT" && type != "IHDR" && type != "PLTE" && type != "fdAT" && type != "fcTL") tech += type
                "IEND" -> { val trailing = d.size - (e + 4); if (trailing > 0) fields += MetaField("Trailing data", "Bytes after image end", "$trailing bytes"); break }
                else -> if (type[0].isLowerCase()) fields += MetaField("Other", "Chunk '$type'", "$len bytes")
            }
            pos = e + 4
        }
        return MetadataScan("PNG", fields, tech.distinct(), gps)
    }

    private fun scanWebp(d: ByteArray): MetadataScan {
        val fields = ArrayList<MetaField>(); val tech = ArrayList<String>(); var gps = false
        var pos = 12
        fun le(i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or ((d[i + 2].toInt() and 0xFF) shl 16) or ((d[i + 3].toInt() and 0xFF) shl 24)
        while (pos + 8 <= d.size) {
            val type = String(d, pos, 4, Charsets.ISO_8859_1); val len = le(pos + 4)
            val s = pos + 8; val e = s + len
            if (len < 0 || e > d.size) break
            when (type) {
                "EXIF" -> { val off = if (e - s >= 6 && String(d, s, 4, Charsets.ISO_8859_1) == "Exif") 6 else 0
                    if (readTiff(d, s + off, e, fields, false)) gps = true }
                "XMP " -> fields += MetaField("XMP", "XMP packet", "$len bytes")
                "ICCP" -> tech += "ICC colour profile"
            }
            pos = e + (len and 1)
        }
        return MetadataScan("WEBP", fields, tech.distinct(), gps)
    }
}
