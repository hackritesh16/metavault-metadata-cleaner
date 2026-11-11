package com.metavault.cleaner

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lossless container-level metadata handling for JPEG, PNG and WebP.
 * Image payloads and ICC colour profiles are retained. Unknown chunks/segments
 * are retained unless they are known metadata-bearing application containers.
 */
internal object ImageContainerMetadata {
    data class Finding(val label: String, val detail: String)

    private val jpegNames = mapOf(
        0xE1 to "EXIF / XMP",
        0xED to "IPTC / Photoshop",
        0xEB to "C2PA / JUMBF or vendor metadata",
        0xFE to "JPEG comment"
    )

    fun inspect(data: ByteArray): List<Finding> = when {
        isJpeg(data) -> inspectJpeg(data)
        isPng(data) -> inspectPng(data)
        isWebp(data) -> inspectWebp(data)
        else -> emptyList()
    }

    fun clean(data: ByteArray): ByteArray = when {
        isJpeg(data) -> cleanJpeg(data)
        isPng(data) -> cleanPng(data)
        isWebp(data) -> cleanWebp(data)
        else -> data
    }

    private fun isJpeg(b: ByteArray) = b.size >= 4 && u(b[0]) == 0xFF && u(b[1]) == 0xD8
    private fun isPng(b: ByteArray) = b.size >= 8 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
    private fun isWebp(b: ByteArray) = b.size >= 12 && ascii(b,0,4) == "RIFF" && ascii(b,8,4) == "WEBP"
    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun ascii(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)

    private fun inspectJpeg(b: ByteArray): List<Finding> {
        val out = mutableListOf<Finding>()
        var p = 2
        while (p + 1 < b.size && u(b[p]) == 0xFF) {
            while (p < b.size && u(b[p]) == 0xFF) p++
            if (p >= b.size) break
            val marker = u(b[p++])
            if (marker == 0xDA || marker == 0xD9) break
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (p + 2 > b.size) break
            val len = (u(b[p]) shl 8) or u(b[p+1])
            if (len < 2 || p + len > b.size) break
            val label = jpegNames[marker]
            if (label != null) out += Finding(label, "JPEG APP/COM marker 0x%02X".format(marker))
            else if (marker in 0xE1..0xEF && marker != 0xE2 && marker != 0xEE)
                out += Finding("Application metadata", "JPEG APP%X segment".format(marker - 0xE0))
            p += len
        }
        return out
    }

    private fun cleanJpeg(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var p = 2
        while (p < b.size) {
            if (u(b[p]) != 0xFF) { out.write(b, p, b.size-p); break }
            val markerStart = p
            while (p < b.size && u(b[p]) == 0xFF) p++
            if (p >= b.size) break
            val marker = u(b[p++])
            if (marker == 0xDA) { // SOS: preserve compressed image stream byte-for-byte
                out.write(b, markerStart, b.size-markerStart)
                break
            }
            if (marker == 0xD9) { out.write(b, markerStart, p-markerStart); break }
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                out.write(b, markerStart, p-markerStart); continue
            }
            if (p + 2 > b.size) { out.write(b, markerStart, b.size-markerStart); break }
            val len = (u(b[p]) shl 8) or u(b[p+1])
            if (len < 2 || p + len > b.size) { out.write(b, markerStart, b.size-markerStart); break }
            val end = p + len
            val isIcc = marker == 0xE2 && len >= 14 && ascii(b, p+2, 12) == "ICC_PROFILE\u0000"
            val isAdobe = marker == 0xEE
            val keep = marker !in 0xE1..0xEF && marker != 0xFE || isIcc || isAdobe
            if (keep) out.write(b, markerStart, end-markerStart)
            p = end
        }
        return out.toByteArray()
    }

    private fun inspectPng(b: ByteArray): List<Finding> {
        val out = mutableListOf<Finding>()
        var p = 8
        while (p + 12 <= b.size) {
            val len = readIntBE(b,p)
            if (len < 0 || len.toLong() + p + 12 > b.size) break
            val type = ascii(b,p+4,4)
            if (type in setOf("eXIf","tEXt","zTXt","iTXt","tIME","caBX"))
                out += Finding(if (type == "caBX") "C2PA Content Credentials" else "PNG $type metadata", "PNG chunk $type")
            p += 12 + len
            if (type == "IEND") break
        }
        return out
    }

    private fun cleanPng(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b,0,8)
        var p = 8
        while (p + 12 <= b.size) {
            val len = readIntBE(b,p)
            if (len < 0 || len.toLong() + p + 12 > b.size) {
                out.write(b,p,b.size-p); break
            }
            val type = ascii(b,p+4,4)
            val end = p + 12 + len
            if (type !in setOf("eXIf","tEXt","zTXt","iTXt","tIME","caBX"))
                out.write(b,p,end-p)
            p = end
            if (type == "IEND") break
        }
        if (p < b.size) out.write(b,p,b.size-p)
        return out.toByteArray()
    }

    private fun inspectWebp(b: ByteArray): List<Finding> {
        val out = mutableListOf<Finding>()
        var p = 12
        while (p + 8 <= b.size) {
            val type = ascii(b,p,4)
            val len = readIntLE(b,p+4)
            if (len < 0 || len.toLong() + p + 8 > b.size) break
            if (type == "EXIF") out += Finding("WebP EXIF", "RIFF EXIF chunk")
            if (type == "XMP ") out += Finding("WebP XMP", "RIFF XMP chunk")
            p += 8 + len + (len and 1)
        }
        return out
    }

    private fun cleanWebp(b: ByteArray): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var p = 12
        while (p + 8 <= b.size) {
            val len = readIntLE(b,p+4)
            if (len < 0 || len.toLong() + p + 8 > b.size) return b
            val type = ascii(b,p,4)
            val end = p + 8 + len + (len and 1)
            if (type != "EXIF" && type != "XMP ") chunks += b.copyOfRange(p,end)
            p = end
        }
        if (p != b.size) return b
        val body = ByteArrayOutputStream()
        chunks.forEach { body.write(it) }
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(out, payload.size + 4)
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun readIntBE(b: ByteArray, p: Int): Int =
        (u(b[p]) shl 24) or (u(b[p+1]) shl 16) or (u(b[p+2]) shl 8) or u(b[p+3])
    private fun readIntLE(b: ByteArray, p: Int): Int =
        u(b[p]) or (u(b[p+1]) shl 8) or (u(b[p+2]) shl 16) or (u(b[p+3]) shl 24)
    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 255); out.write((v ushr 8) and 255)
        out.write((v ushr 16) and 255); out.write((v ushr 24) and 255)
    }
}