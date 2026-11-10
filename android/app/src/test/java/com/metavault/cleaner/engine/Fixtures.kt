package com.metavault.cleaner.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.imageio.ImageIO

/** Synthetic fixtures only; no real user media. */
object Fixtures {
    private fun image(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) {
            img.setRGB(x, y, (((x * 13) and 0xFF) shl 16) or (((y * 29) and 0xFF) shl 8) or (((x + y) * 7) and 0xFF))
        }
        return img
    }

    fun jpeg(w: Int = 16, h: Int = 8): ByteArray {
        val o = ByteArrayOutputStream()
        check(ImageIO.write(image(w, h), "jpg", o))
        return o.toByteArray()
    }

    fun png(w: Int = 16, h: Int = 8): ByteArray {
        val o = ByteArrayOutputStream()
        check(ImageIO.write(image(w, h), "png", o))
        return o.toByteArray()
    }

    fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    fun injectAfterSoi(jpeg: ByteArray, vararg segs: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + segs.fold(ByteArray(0)) { a, s -> a + s } + jpeg.copyOfRange(2, jpeg.size)

    /** Big-endian TIFF with Make, Orientation, ExifIFD(DateTimeOriginal) and a GPS IFD. 124 bytes. */
    fun exifTiff(orientation: Int): ByteArray {
        val bb = ByteBuffer.allocate(124).order(ByteOrder.BIG_ENDIAN)
        fun s(v: Int) = bb.putShort(v.toShort())
        bb.put('M'.code.toByte()); bb.put('M'.code.toByte()); s(42); bb.putInt(8)
        s(4)
        s(0x010F); s(2); bb.putInt(6); bb.putInt(62)
        s(0x0112); s(3); bb.putInt(1); s(orientation); s(0)
        s(0x8769); s(4); bb.putInt(1); bb.putInt(68)
        s(0x8825); s(4); bb.putInt(1); bb.putInt(106)
        bb.putInt(0)
        bb.put("Canon\u0000".toByteArray())
        s(1)
        s(0x9003); s(2); bb.putInt(20); bb.putInt(86)
        bb.putInt(0)
        bb.put("2020:01:01 10:00:00\u0000".toByteArray())
        s(1)
        s(0x0001); s(2); bb.putInt(2); bb.put('N'.code.toByte()); bb.put(0); bb.put(0); bb.put(0)
        bb.putInt(0)
        check(bb.position() == 124) { "fixture layout is wrong: ${bb.position()}" }
        return bb.array()
    }

    fun exifSegment(orientation: Int): ByteArray =
        jpegSegment(0xE1, "Exif\u0000\u0000".toByteArray() + exifTiff(orientation))

    fun xmpSegment(): ByteArray =
        jpegSegment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray())

    fun iptcSegment(): ByteArray = jpegSegment(0xED, "Photoshop 3.0\u0000secret".toByteArray())

    fun commentSegment(): ByteArray = jpegSegment(0xFE, "secret comment".toByteArray())

    fun iccSegment(): ByteArray = jpegSegment(0xE2, "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1, 9, 9, 9, 9))

    fun pngChunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray()
        val crc = CRC32().apply { update(t); update(data) }
        return ByteBuffer.allocate(12 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(data.size).put(t).put(data).putInt(crc.value.toInt()).array()
    }

    /** Inserts chunks immediately before IEND (the last 12 bytes). */
    fun pngInject(png: ByteArray, vararg chunks: ByteArray): ByteArray =
        png.copyOfRange(0, png.size - 12) + chunks.fold(ByteArray(0)) { a, c -> a + c } + png.copyOfRange(png.size - 12, png.size)

    fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
    private fun le24(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte())

    fun webpChunk(fourcc: String, data: ByteArray): ByteArray =
        fourcc.toByteArray() + le32(data.size) + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold(ByteArray(0)) { a, c -> a + c }
        return "RIFF".toByteArray() + le32(4 + body.size) + "WEBP".toByteArray() + body
    }

    /**
     * Structural stand-in for a lossless WebP image payload: a valid VP8L header declaring 1x1 followed by opaque bytes.
     * It is NOT a decodable image; WebP tests therefore check structure and byte preservation only.
     */
    val vp8lPayload: ByteArray = byteArrayOf(0x2F, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8)

    fun vp8x(flags: Int, w: Int, h: Int): ByteArray =
        webpChunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0) + le24(w - 1) + le24(h - 1))
}
