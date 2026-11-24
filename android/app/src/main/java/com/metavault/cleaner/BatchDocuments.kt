package com.metavault.cleaner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

internal fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

internal fun sourceName(context: Context, uri: Uri): String =
    runCatching { displayName(context, uri) }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"

internal fun documentKey(uri: Uri): Pair<String?, String> {
    val id = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: IllegalArgumentException) {
        uri.toString()
    }
    return uri.authority to id
}

/** A source file after cleaning, before anything is written. */
internal sealed interface Prepared {
    class Ready(val name: String, val format: BatchFormat, val copy: PreparedCopy) : Prepared
    class Finished(val result: BatchResult) : Prepared
}

internal fun prepareBatchDocument(context: Context, source: Uri): Prepared {
    val name = sourceName(context, source)
    return try {
        val format = BatchFormat.resolve(name, context.contentResolver.getType(source))
            ?: return Prepared.Finished(BatchResult(name, BatchStatus.SKIPPED, "Unsupported file format"))
        Prepared.Ready(name, format, prepareBatchCopy(format, MetadataEngine.load(context, originalUri(context, source))))
    } catch (e: Exception) {
        Prepared.Finished(BatchResult(name, BatchStatus.FAILED, e.localizedMessage ?: "Could not process file"))
    }
}

/** Existing files in the output folder, by lower-case name. Kept up to date as the batch creates files. */
internal fun listFolder(context: Context, tree: Uri): MutableMap<String, Uri> {
    val treeId = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
    val found = HashMap<String, Uri>()
    context.contentResolver.query(
        children,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getString(0) ?: continue
            val name = cursor.getString(1) ?: continue
            found[nameKey(name)] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        }
    }
    return found
}

/**
 * Replaces the contents of [target] with [bytes] after keeping a restorable backup in the app.
 * The new contents are read back and compared. If they differ, the backup is written back.
 */
internal fun replaceWithBackup(context: Context, target: Uri, bytes: ByteArray, backupName: String) {
    val resolver = context.contentResolver
    val backup: File = backupOriginal(context, originalUri(context, target), backupName)
    try {
        try {
            resolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: error("Could not open the file for writing")
        } catch (e: SecurityException) {
            throw IllegalStateException("Android did not allow writing to this file. The original is unchanged.", e)
        } catch (e: FileNotFoundException) {
            val denied = (e.message ?: "").let { it.contains("denied", true) || it.contains("EACCES") }
            if (denied) throw IllegalStateException("Android did not allow writing to this file. The original is unchanged.", e)
            throw e
        }
        resolver.openInputStream(target)?.use { verifyBytes(bytes, it) } ?: error("Could not reopen the file to check it")
    } catch (failure: Exception) {
        // The write may have started, so put the backed-up bytes back.
        runCatching {
            resolver.openOutputStream(target, "wt")?.use { it.write(backup.readBytes()) }
        }
        throw failure
    }
}

/** Saves [prepared] as a new document in [tree], or replaces the file already there when [target] says so. */
internal fun saveToFolder(
    context: Context, tree: Uri, index: MutableMap<String, Uri>, prepared: Prepared.Ready,
    target: SaveTarget.Write, selectedKeys: Set<Pair<String?, String>>
): BatchResult {
    val resolver = context.contentResolver
    if (target.replaceExisting) {
        val existing = index[nameKey(target.name)] ?: error("The file to replace is no longer in the folder")
        replaceWithBackup(context, existing, prepared.copy.bytes, target.name)
        return BatchResult(prepared.name, BatchStatus.SAVED,
            "${target.name} replaced · backup kept in History · ${prepared.copy.scope}")
    }
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val output = DocumentsContract.createDocument(resolver, parent, prepared.format.mime, target.name)
        ?: error("Folder could not create a new document")
    check(documentKey(output) !in selectedKeys) { "Provider returned a selected original as the destination" }
    writeVerifiedCopy(prepared.copy.bytes,
        openWriter = { resolver.openOutputStream(output) ?: error("Could not write to the new file") },
        openReader = { resolver.openInputStream(output) ?: error("Could not reopen the new file") },
        deleteNew = { DocumentsContract.deleteDocument(resolver, output) }
    )
    val savedName = runCatching { displayName(context, output) }.getOrNull() ?: target.name
    index[nameKey(savedName)] = output
    return BatchResult(prepared.name, BatchStatus.SAVED, "$savedName · ${prepared.copy.scope}")
}

/** Cleans a file in place. The original is backed up first and the result is read back. */
internal fun overwriteOriginalDocument(context: Context, source: Uri, prepared: Prepared.Ready): BatchResult {
    replaceWithBackup(context, source, prepared.copy.bytes, prepared.name)
    return BatchResult(prepared.name, BatchStatus.SAVED,
        "Original replaced · backup kept in History · ${prepared.copy.scope}")
}
