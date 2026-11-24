package com.metavault.cleaner

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

internal fun displayName(context: Context, uri: Uri): String? =
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

internal fun retainWriteGrant(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
}

internal fun replaceVerified(context: Context, target: Uri, name: String, bytes: ByteArray): String {
    val backup = backupOriginal(context, target, name)
    writeWithRecovery(context, target, bytes, backup)
    return name
}

internal fun backupOriginal(context: Context, uri: Uri, name: String): File {
    val dir = File(context.filesDir, "backups").apply { mkdirs() }
    val file = File.createTempFile(System.currentTimeMillis().toString() + "-", "__" + sanitizeName(name).take(64) + ".bak", dir)
    val location = File(dir, file.name + ".uri")
    try {
        val source = if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY &&
            context.contentResolver.getType(uri)?.startsWith("image/") == true) {
            // A backup of an Android-redacted photo cannot restore the previous embedded GPS data.
            MediaStore.setRequireOriginal(uri)
        } else uri
        val digest = context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { out -> backupDigest(input, out).also { out.fd.sync() } }
        } ?: error("Could not read the previous file")
        check(file.inputStream().use { backupDigest(it) }.contentEquals(digest)) { "Backup verification failed" }
        location.writeText(uri.toString())
        return file
    } catch (e: Exception) {
        file.delete()
        location.delete()
        throw e
    }
}

internal fun writeWithRecovery(context: Context, target: Uri, bytes: ByteArray, backup: File) {
    val resolver = context.contentResolver
    writeWithRecovery(bytes, backup,
        openWriter = { resolver.openOutputStream(target, "wt") ?: error("Cannot open file for writing") },
        openReader = { resolver.openInputStream(target) ?: error("Cannot verify saved file") })
}

internal data class FolderEntry(val uri: Uri, val name: String, val directory: Boolean)

internal class ExportFolder(private val context: Context, private val tree: Uri?) {
    private val resolver = context.contentResolver
    private val gallery = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val pictures = Environment.DIRECTORY_PICTURES + "/MetaVault/"

    fun entries(name: String): List<FolderEntry> {
        val matches = mutableListOf<FolderEntry>()
        if (tree != null) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            val cursor = resolver.query(children, columns, null, null, null) ?: error("Cannot inspect the chosen folder")
            cursor.use {
                while (it.moveToNext()) {
                    val actual = it.getString(1) ?: continue
                    if (name.equals(actual, true)) matches += FolderEntry(
                        DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)), actual,
                        it.getString(2) == Document.MIME_TYPE_DIR)
                }
                check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING)) { "Folder is still loading. Try again." }
            }
        } else {
            check(Build.VERSION.SDK_INT >= 29)
            val cursor = resolver.query(gallery, arrayOf(MediaStore.Images.Media._ID, OpenableColumns.DISPLAY_NAME),
                "relative_path = ? AND _display_name = ? COLLATE NOCASE", arrayOf(pictures, name), null)
                ?: error("Cannot inspect Pictures/MetaVault")
            cursor.use {
                while (it.moveToNext()) matches += FolderEntry(ContentUris.withAppendedId(gallery, it.getLong(0)), it.getString(1), false)
            }
        }
        return matches
    }

    fun newCopy(bytes: ByteArray, name: String, mime: String, sources: Set<Pair<String?, String>>): String {
        val uri = if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, mime, name)
        } else {
            check(Build.VERSION.SDK_INT >= 29)
            resolver.insert(gallery, ContentValues().apply {
                put(OpenableColumns.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, pictures)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            })
        } ?: error("Could not create a new file")
        check(documentKey(uri) !in sources) { "Provider returned a selected original" }
        fun deleteNew() = if (tree == null) resolver.delete(uri, null, null) > 0 else DocumentsContract.deleteDocument(resolver, uri)
        // Some providers silently pick a different name after a concurrent or invisible collision.
        try {
            if (displayName(context, uri) != name) throw NameUnavailable()
        } catch (e: Exception) {
            check(deleteNew()) { "Check the destination for an empty new file" }
            throw e
        }
        writeVerifiedCopy(bytes,
            openWriter = { resolver.openOutputStream(uri) ?: error("Cannot write the new file") },
            openReader = { resolver.openInputStream(uri) ?: error("Cannot verify the new file") }, deleteNew = ::deleteNew)
        if (tree == null && Build.VERSION.SDK_INT >= 29) {
            try {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            } catch (e: Exception) {
                check(deleteNew()) { "Could not publish or remove the pending gallery copy" }
                throw e
            }
        }
        return name
    }
}

internal class SelectedSources(private val context: Context, uris: Collection<Uri>) {
    val keys = uris.map(::documentKey).toSet()
    private val originals = uris.toList()
    private val mediaKeys by lazy { originals.mapNotNull(::mediaKey).toSet() }

    private fun mediaKey(uri: Uri): Pair<String, String>? {
        val media = when {
            uri.authority == MediaStore.AUTHORITY -> uri
            Build.VERSION.SDK_INT >= 29 -> runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            else -> null
        } ?: return null
        val volume = media.pathSegments.firstOrNull() ?: return null
        return volume.replace("external_primary", "external") to (media.lastPathSegment ?: return null)
    }

    fun contains(uri: Uri): Boolean {
        if (documentKey(uri) in keys || mediaKey(uri) in mediaKeys) return true
        // API 26-28 can map a gallery URI to its document URI even without the reverse API.
        return originals.any { original ->
            val gallery = if (original.authority == MediaStore.AUTHORITY) original else uri
            val document = if (gallery == original) uri else original
            if (gallery.authority != MediaStore.AUTHORITY || document.authority == MediaStore.AUTHORITY) false
            else runCatching { MediaStore.getDocumentUri(context, gallery)?.let(::documentKey) == documentKey(document) }.getOrDefault(false)
        }
    }
}

internal suspend fun saveCopy(
    context: Context, tree: Uri?, bytes: ByteArray, stem: String, ext: String, mime: String,
    sources: SelectedSources, decide: suspend (NameClash) -> CollisionChoice
): String? {
    val folder = ExportFolder(context, tree)
    return exportNamed(stem, ext, folder::entries,
        replaceable = { !it.directory && !sources.contains(it.uri) },
        decide = decide, create = { folder.newCopy(bytes, it, mime, sources.keys) },
        replace = {
            if (tree != null) retainWriteGrant(context, tree)
            replaceVerified(context, it.uri, it.name, bytes)
        })
}
