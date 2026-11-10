package com.metavault.cleaner.engine

internal data class ExifInfo(val orientation: Int?, val fields: List<MetadataField>)

internal object Exif {
    private const val MAX_ENTRIES = 1024

    /** Returns the TIFF structure, tolerating an optional "Exif\0\0" prefix. */
    fun payload(b: ByteArray, off: Int, len: Int): ByteArray {
        val prefixed = len >= 6 && b.hasAscii(off, "Exif") && b[off + 4] == 0.toByte() && b[off + 5] == 0.toByte()
        val start = if (prefixed) off + 6 else off
        return b.copyOfRange(start, off + len)
    }

    /** Big-endian TIFF containing only the Orientation tag (26 bytes). */
    fun minimalOrientation(orientation: Int): ByteArray = byteArrayOf(
        0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
        0x00, 0x01,
        0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    /** Null when the block cannot be parsed. Never throws. */
    fun parse(t: ByteArray): ExifInfo? = try {
        parseUnsafe(t)
    } catch (e: RuntimeException) {
        null
    }

    private fun parseUnsafe(t: ByteArray): ExifInfo? {
        if (t.size < 8) return null
        val le = when {
            t[0] == 0x49.toByte() && t[1] == 0x49.toByte() -> true
            t[0] == 0x4D.toByte() && t[1] == 0x4D.toByte() -> false
            else -> return null
        }
        fun u16(o: Int): Int {
            require(o >= 0 && o + 2 <= t.size)
            return if (le) t.le16(o) else t.be16(o)
        }
        fun u32(o: Int): Long {
            require(o >= 0 && o + 4 <= t.size)
            return if (le) t.le32(o) else t.be32(o)
        }
        if (u16(2) != 42) return null

        class Entry(val tag: Int, val type: Int, val count: Long, val at: Int)

        fun entries(ifd: Int): List<Entry> {
            val n = u16(ifd)
            require(n <= MAX_ENTRIES)
            return (0 until n).map { i ->
                val eo = ifd + 2 + i * 12
                Entry(u16(eo), u16(eo + 2), u32(eo + 4), eo + 8)
            }
        }

        fun valueOffset(e: Entry): Int {
            val unit = when (e.type) { 3 -> 2; 4 -> 4; 5 -> 8; else -> 1 }
            return if (unit.toLong() * e.count <= 4) e.at else u32(e.at).toInt()
        }

        fun ascii(e: Entry): String {
            val n = minOf(e.count, 256L).toInt()
            val o = valueOffset(e)
            require(o >= 0 && o + n <= t.size)
            return String(t, o, n, Charsets.ISO_8859_1)
                .trim { it == '\u0000' || it.isWhitespace() }
                .filter { it >= ' ' }
        }

        val fields = ArrayList<MetadataField>()
        var orientation: Int? = null
        var exifPtr = -1
        var gpsPtr = -1

        val ifd0 = u32(4).toInt()
        val ifd0Entries = entries(ifd0)
        for (e in ifd0Entries) {
            when (e.tag) {
                0x010E -> fields += MetadataField("EXIF", "Image description", ascii(e))
                0x010F -> fields += MetadataField("EXIF", "Camera make", ascii(e))
                0x0110 -> fields += MetadataField("EXIF", "Camera model", ascii(e))
                0x0112 -> {
                    val o = u16(valueOffset(e))
                    orientation = o
                    fields += MetadataField("EXIF", "Orientation", o.toString())
                }
                0x0131 -> fields += MetadataField("EXIF", "Software", ascii(e))
                0x0132 -> fields += MetadataField("EXIF", "Date/time", ascii(e))
                0x013B -> fields += MetadataField("EXIF", "Artist", ascii(e))
                0x8298 -> fields += MetadataField("EXIF", "Copyright", ascii(e))
                0x8769 -> exifPtr = u32(e.at).toInt()
                0x8825 -> gpsPtr = u32(e.at).toInt()
            }
        }
        if (exifPtr > 0) {
            for (e in entries(exifPtr)) {
                when (e.tag) {
                    0x9003 -> fields += MetadataField("EXIF", "Date/time original", ascii(e))
                    0x9004 -> fields += MetadataField("EXIF", "Date/time digitized", ascii(e))
                    0x927C -> fields += MetadataField("EXIF", "Maker note (vendor data)")
                    0x9286 -> fields += MetadataField("EXIF", "User comment")
                    0xA430 -> fields += MetadataField("EXIF", "Camera owner name", ascii(e))
                    0xA431 -> fields += MetadataField("EXIF", "Camera body serial number", ascii(e))
                    0xA434 -> fields += MetadataField("EXIF", "Lens model", ascii(e))
                    0xA435 -> fields += MetadataField("EXIF", "Lens serial number", ascii(e))
                }
            }
        }
        if (gpsPtr > 0 && entries(gpsPtr).isNotEmpty()) {
            fields += MetadataField("EXIF", "GPS location data")
        }
        val next = u32(ifd0 + 2 + ifd0Entries.size * 12)
        if (next > 0 && entries(next.toInt()).isNotEmpty()) {
            fields += MetadataField("EXIF", "Embedded EXIF thumbnail")
        }
        return ExifInfo(orientation, fields)
    }
}
