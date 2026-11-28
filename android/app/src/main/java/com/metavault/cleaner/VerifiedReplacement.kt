package com.metavault.cleaner

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Copies a backup with bounded memory and returns the digest of the bytes read. */
internal fun backupDigest(input: InputStream, output: OutputStream? = null): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        check(count > 0) { "File could not be read" }
        total += count
        check(total <= MetadataEngine.MAX_BYTES) { "File exceeds the backup size limit" }
        output?.write(buffer, 0, count)
        digest.update(buffer, 0, count)
    }
    return digest.digest()
}

internal fun writeWithRecovery(
    bytes: ByteArray, backup: File, openWriter: () -> OutputStream, openReader: () -> InputStream
) {
    writeWithRecovery(backup, openWriter, openReader, write = { it.write(bytes) },
        verify = { verifySavedBytes(bytes, openReader) })
}

internal fun writeWithRecovery(
    backup: File, openWriter: () -> OutputStream, openReader: () -> InputStream,
    write: (OutputStream) -> Unit, verify: () -> Unit
) {
    // Opening a writer can require permission; only a successfully opened writer may have changed bytes.
    val writer = openWriter()
    try {
        writer.use(write)
        verify()
    } catch (e: Exception) {
        val restored = runCatching {
            val digest = backup.inputStream().use { input -> openWriter().use { backupDigest(input, it) } }
            check(openReader().use { backupDigest(it) }.contentEquals(digest))
        }.isSuccess
        throw IllegalStateException(if (restored) "Save failed. Previous contents restored."
            else "Save failed. Recover the previous file from History.", e)
    }
}
