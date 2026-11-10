package com.metavault.cleaner.engine

import java.security.MessageDigest

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
internal fun ByteArray.be16(o: Int): Int = (u8(o) shl 8) or u8(o + 1)
internal fun ByteArray.be32(o: Int): Long =
    (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()
internal fun ByteArray.le16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.le24(o: Int): Int = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16)
internal fun ByteArray.le32(o: Int): Long =
    u8(o).toLong() or (u8(o + 1).toLong() shl 8) or (u8(o + 2).toLong() shl 16) or (u8(o + 3).toLong() shl 24)

internal fun ByteArray.hasAscii(o: Int, s: String): Boolean {
    if (o < 0 || o + s.length > size) return false
    for (i in s.indices) if (this[o + i] != s[i].code.toByte()) return false
    return true
}

/** SHA-256 over tagged, length-prefixed byte ranges, so different splits cannot collide. */
internal class Digest {
    private val md = MessageDigest.getInstance("SHA-256")

    fun add(tag: String, b: ByteArray, off: Int, len: Int) {
        md.update(tag.toByteArray(Charsets.ISO_8859_1))
        md.update(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        md.update(b, off, len)
    }

    fun addInt(tag: String, v: Int) = add(tag, byteArrayOf(v.toByte()), 0, 1)

    fun finish(): ByteArray = md.digest()
}
