package com.metavault.cleaner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

internal fun documentKey(uri: Uri): Pair<String?, String> {
    val id = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: IllegalArgumentException) {
        uri.toString()
    }
    return uri.authority to id
}

internal fun processBatchDocument(
    context: Context, source: Uri, tree: Uri, selectedKeys: Set<Pair<String?, String>>
): BatchResult {
    val resolver = context.contentResolver
    val name = displayName(context, source) ?: source.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
    try {
        val format = BatchFormat.resolve(name, resolver.getType(source))
            ?: return BatchResult(name, BatchStatus.SKIPPED, "Unsupported file format")
        val copy = prepareBatchCopy(format, MetadataEngine.load(context, originalUri(context, source)))
        val outputName = sanitizeName(name.substringBeforeLast('.', name)) + "-clean." + format.extension
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val output = DocumentsContract.createDocument(resolver, parent, format.mime, outputName)
            ?: error("Folder could not create a new document")
        check(documentKey(output) !in selectedKeys) { "Provider returned a selected original as the destination" }
        writeVerifiedCopy(copy.bytes,
            openWriter = { resolver.openOutputStream(output) ?: error("Could not write to the new file") },
            openReader = { resolver.openInputStream(output) ?: error("Could not reopen the new file") },
            deleteNew = { DocumentsContract.deleteDocument(resolver, output) }
        )
        val savedName = runCatching { displayName(context, output) }.getOrNull()
        val destination = savedName ?: "New document in the chosen folder"
        return BatchResult(name, BatchStatus.SAVED, "$destination · ${copy.scope}")
    } catch (e: Exception) {
        return BatchResult(name, BatchStatus.FAILED, e.localizedMessage ?: "Could not process file")
    }
}
