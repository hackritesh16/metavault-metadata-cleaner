package com.metavault.cleaner

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.Uri as AndroidUri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Page(val label: String) {
    HOME("Home"), BROWSE("Browse"), PHOTOS("Photos"), VIDEOS("Videos"),
    AUDIO("Audio"), PDFS("PDF files"), DOCS("Documents"), CLEANER("Cleaner"),
    HISTORY("History"), SETTINGS("Settings"), ABOUT("About")
}

private val LibraryPages = setOf(Page.PHOTOS, Page.VIDEOS, Page.AUDIO, Page.PDFS, Page.DOCS)

internal data class HistoryEntry(val name: String, val detail: String)
private data class DeviceMedia(val uri: Uri, val name: String, val mime: String, val category: String, val size: Long?, val modified: Long?)
private data class BackupEntry(val file: File, val name: String, val uriString: String, val time: Long)

/** One place that says what each category supports; used by the Browse cards and the gallery header. */
private fun supportShort(page: Page): Pair<String, Boolean> = when (page) {
    Page.PHOTOS -> "View · edit · clean" to true
    Page.VIDEOS -> "View only" to false
    Page.AUDIO -> "MP3 clean · others view only" to true
    Page.PDFS -> "Not supported yet" to false
    else -> "DOCX · XLSX · PPTX clean" to true
}

private fun categoryKey(page: Page) = when (page) {
    Page.PHOTOS -> "Photo"; Page.VIDEOS -> "Video"; Page.AUDIO -> "Audio"; Page.PDFS -> "PDF"; else -> "Document"
}

private fun permissionCategory(page: Page) = when (page) {
    Page.PHOTOS -> MediaAccessPolicy.Category.PHOTOS
    Page.VIDEOS -> MediaAccessPolicy.Category.VIDEOS
    Page.AUDIO -> MediaAccessPolicy.Category.AUDIO
    else -> MediaAccessPolicy.Category.DOCUMENTS
}

private fun mediaAccess(context: Context, page: Page): MediaAccessPolicy.Access {
    val granted = listOf(MediaAccessPolicy.STORAGE, MediaAccessPolicy.IMAGES,
        MediaAccessPolicy.VIDEO, MediaAccessPolicy.AUDIO, MediaAccessPolicy.SELECTED)
        .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()
    return MediaAccessPolicy.access(Build.VERSION.SDK_INT, permissionCategory(page), granted, hasAllFilesAccess())
}

// ---------- Device library ----------

private fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

private fun loadDeviceMedia(context: Context): List<DeviceMedia> {
    val resolver = context.contentResolver
    val results = mutableListOf<DeviceMedia>()

    // Each category has its own limit, so a large category (photos) can never hide the others.
    fun read(collection: Uri, category: String, allowed: Boolean, selection: String? = null, args: Array<String>? = null, limit: Int = 5000) {
        if (!allowed) return
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        runCatching {
            resolver.query(collection, projection, selection, args, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                fun number(col: Int) = if (col >= 0 && !cursor.isNull(col)) cursor.getLong(col) else null
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unnamed file"
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "application/octet-stream" else "application/octet-stream"
                    results.add(DeviceMedia(android.content.ContentUris.withAppendedId(collection, id), name, mime, category, number(sizeCol), number(dateCol)))
                    count++
                }
            }
        }
    }

    val visualAccess = setOf(MediaAccessPolicy.Access.FULL, MediaAccessPolicy.Access.SELECTED)
    val imagesOk = mediaAccess(context, Page.PHOTOS) in visualAccess
    val videosOk = mediaAccess(context, Page.VIDEOS) in visualAccess
    val audioOk = mediaAccess(context, Page.AUDIO) == MediaAccessPolicy.Access.FULL
    read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photo", imagesOk)
    read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Video", videosOk)
    read(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", audioOk)

    // PDF and Office files are only listed by Android with all-files access (or on Android 9 and below).
    val filesOk = mediaAccess(context, Page.DOCS) == MediaAccessPolicy.Access.FULL
    val files = MediaStore.Files.getContentUri("external")
    val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
    read(files, "PDF", filesOk, "$nameCol LIKE ?", arrayOf("%.pdf"))
    read(files, "Document", filesOk, "$nameCol LIKE ? OR $nameCol LIKE ? OR $nameCol LIKE ?", arrayOf("%.docx", "%.xlsx", "%.pptx"))
    return results
}

/** Android hides GPS tags of gallery photos unless we ask for the original (needs ACCESS_MEDIA_LOCATION). */
internal fun originalUri(context: Context, uri: Uri): Uri {
    if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY &&
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    ) {
        return runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    }
    return uri
}

private val thumbCache = object : LruCache<String, Bitmap>(48 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private fun loadThumb(context: Context, uri: Uri, size: Int): Bitmap? {
    val key = uri.toString() + "@" + size
    thumbCache.get(key)?.let { return it }
    val bitmap = runCatching {
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(size, size), null)
        else context.contentResolver.openInputStream(uri)?.use { s ->
            BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
    }.getOrNull()
    if (bitmap != null) thumbCache.put(key, bitmap)
    return bitmap
}

/** Decode only a display-sized bitmap; a camera photo must not exhaust the UI heap. */
private fun decodeBackground(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    return resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

// ---------- Saving and backups ----------

internal fun sanitizeName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "metavault-file" }

private fun mimeForExt(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "jpg", "jpeg" -> "image/jpeg"
    else -> "application/octet-stream"
}

private fun isImageExt(ext: String) = ext.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")

private fun loadBackups(context: Context): List<BackupEntry> {
    val dir = File(context.filesDir, "backups")
    return (dir.listFiles()?.toList() ?: emptyList()).filter { it.name.endsWith(".bak") }.mapNotNull { f ->
        val uriFile = File(dir, f.name + ".uri")
        if (!uriFile.exists()) return@mapNotNull null
        val stem = f.name.removeSuffix(".bak")
        BackupEntry(f, stem.substringAfter("__"), uriFile.readText().trim(), stem.substringBefore("__").toLongOrNull() ?: f.lastModified())
    }.sortedByDescending { it.time }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val batch = androidx.lifecycle.ViewModelProvider(this)[BatchCleanerModel::class.java]
        setContent { MetaVaultApp(batch) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaVaultApp(batch: BatchCleanerModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by remember { mutableStateOf(Page.HOME) }
    var selectedTheme by remember { mutableStateOf(context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).getString("theme", "Aurora") ?: "Aurora") }
    var customPhotoUri by remember { mutableStateOf(context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).getString("background_photo", null)) }
    var previewThemes by remember { mutableStateOf(true) }
    val mainScrollState = rememberScrollState()
    var status by remember { mutableStateOf("Your files stay on this device.") }
    var customPhotoBitmap by remember(customPhotoUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(customPhotoUri) {
        customPhotoBitmap = customPhotoUri?.let { raw ->
            withContext(Dispatchers.IO) { runCatching { decodeBackground(context, Uri.parse(raw)) }.getOrNull() }
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            customPhotoUri = uri.toString()
            context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).edit().putString("background_photo", uri.toString()).apply()
            status = "Custom background photo applied."
        }
    }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No file selected") }
    var mime by remember { mutableStateOf("image/jpeg") }
    var convert by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("image/jpeg") }
    var busy by remember { mutableStateOf(false) }
    var readyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var outBase by remember { mutableStateOf("metavault-clean") }
    var outExt by remember { mutableStateOf("jpg") }
    var lastAction by remember { mutableStateOf("Clean copy saved") }
    var history by batch.history
    var backups by remember { mutableStateOf(listOf<BackupEntry>()) }
    var deviceMedia by remember { mutableStateOf(emptyList<DeviceMedia>()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var metadataReloadTick by remember { mutableIntStateOf(0) }
    var requestedCategory by remember { mutableStateOf<Page?>(null) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    val retryOverwrite = remember { mutableStateOf<(() -> Unit)?>(null) }
    var fields by remember { mutableStateOf<List<MetaField>?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var inspectError by remember { mutableStateOf<String?>(null) }
    val editValues = remember { mutableStateMapOf<String, String>() }
    val hasLocationAccess = remember(reloadTick, metadataReloadTick) {
        Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val allFiles = remember(reloadTick) { hasAllFilesAccess() }
    val accessByPage = remember(reloadTick) { LibraryPages.associateWith { mediaAccess(context, it) } }
    val counts = remember(deviceMedia) { deviceMedia.groupingBy { it.category }.eachCount() }
    val kind = if (selectedUri != null) MetadataEngine.kindOf(fileName, mime) else MetadataEngine.Kind.UNSUPPORTED

    fun say(message: String, saved: Boolean = false) {
        status = message
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (saved) snackbar.showSnackbar(SavedNotice(message)) else snackbar.showSnackbar(message)
        }
    }

    BackHandler(enabled = page != Page.HOME || busy) {
        if (busy) say("Please wait for the current file operation to finish.")
        else page = when (page) {
            in LibraryPages -> Page.BROWSE
            Page.ABOUT -> Page.SETTINGS
            else -> Page.HOME
        }
    }

    // Reload the library whenever the app returns to the foreground (e.g. after granting access in Settings).
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reloadTick++
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(batch.refresh) {
        if (batch.refresh > 0) thumbCache.evictAll()
    }
    LaunchedEffect(reloadTick, batch.refresh) {
        libraryLoading = true
        val (list, saved) = withContext(Dispatchers.IO) { loadDeviceMedia(context) to loadBackups(context) }
        deviceMedia = list
        backups = saved
        libraryLoading = false
    }

    LaunchedEffect(selectedUri, hasLocationAccess, batch.refresh, metadataReloadTick) {
        val uri = selectedUri
        fields = null
        inspectError = null
        editValues.clear()
        if (uri != null) {
            inspecting = true
            runCatching { withContext(Dispatchers.IO) { MetadataEngine.inspect(context, originalUri(context, uri), fileName, mime) } }
                .onSuccess { list ->
                    fields = list
                    list.forEach { f -> if (f.key in MetadataEngine.EDITABLE_IMAGE_TAGS) editValues[f.key] = f.value }
                }
                .onFailure { inspectError = "Could not read metadata: ${it.message}" }
            inspecting = false
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        reloadTick++
        if (grants.values.none { it }) say("Access was not granted. You can still use the file picker, or change access in Android Settings.")
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) metadataReloadTick++
        else say("Location metadata access was not granted. Android may hide GPS fields.")
    }
    val openAppPermissions: () -> Unit = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        }.onFailure { say("Could not open Android settings. Use the system file picker instead.") }
    }

    val openAllFilesAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = AndroidUri.parse("package:${context.packageName}") }
            runCatching { context.startActivity(intent) }.onFailure { say("Could not open Android settings.") }
        } else say("Use the file picker to choose files on this Android version.")
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep SAF access for later backup restore where the provider allows it.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.onFailure {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            selectedUri = uri
            fileName = "No file selected"
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) fileName = c.getString(0) ?: "Selected file"
                }
            }
            mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            if (fileName == "No file selected") fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
            readyBytes = null
            status = "File selected."
            page = Page.CLEANER
        }
    }
    val writeConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) retryOverwrite.value?.invoke()
        else { say("Permission to overwrite was not granted. Your original is unchanged."); busy = false }
    }

    fun openMedia(item: DeviceMedia) {
        selectedUri = item.uri; fileName = item.name; mime = item.mime
        readyBytes = null; status = "File selected."; page = Page.CLEANER
    }

    fun overwriteOriginal(uri: Uri, bytes: ByteArray, label: String, detail: String, backup: File, retried: Boolean = false) {
        scope.launch {
            busy = true
            val outcome: Pair<String, IntentSender?> = withContext(Dispatchers.IO) {
                try {
                    writeWithRecovery(context, uri, bytes, backup)
                    "ok" to null
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException) "consent" to e.userAction.actionIntent.intentSender
                    else "consent" to null
                } catch (e: FileNotFoundException) {
                    val m = e.message ?: ""
                    if (m.contains("denied", true) || m.contains("EACCES")) "consent" to null else ("error:" + m) to null
                } catch (e: Exception) {
                    ("error:" + (e.message ?: "unknown")) to null
                }
            }
            val (code, sender) = outcome
            when {
                code == "ok" -> {
                    say("Done. The original was replaced. A backup is kept; restore it from History.", saved = true)
                    history = listOf(HistoryEntry(label, detail)) + history
                    readyBytes = null
                    thumbCache.evictAll()
                    metadataReloadTick++
                    reloadTick++
                    busy = false
                }
                code == "consent" && !retried -> {
                    retryOverwrite.value = { overwriteOriginal(uri, bytes, label, detail, backup, true) }
                    val request = sender ?: runCatching {
                        if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender else null
                    }.getOrNull()
                    if (request != null) writeConsentLauncher.launch(IntentSenderRequest.Builder(request).build())
                    else { say("Android does not allow overwriting this file. Use Save as new instead. Your original is unchanged."); busy = false }
                }
                code == "consent" -> { say("Permission to overwrite was not granted. Your original is unchanged."); busy = false }
                else -> { say("Overwrite failed: " + code.removePrefix("error:") + ". A backup of the original is kept in History."); busy = false }
            }
        }
    }

    fun startOverwrite() {
        val uri = selectedUri
        val bytes = readyBytes
        if (uri == null || bytes == null) return
        scope.launch {
            busy = true; status = "Backing up the original first…"
            try {
                val backup = withContext(Dispatchers.IO) { backupOriginal(context, uri, fileName) }
                backups = withContext(Dispatchers.IO) { loadBackups(context) }
                overwriteOriginal(uri, bytes, fileName, "$lastAction (original overwritten)", backup)
            } catch (e: Exception) {
                say("Could not back up the original: ${e.message}")
                busy = false
            }
        }
    }

    fun saveSingleCopy(tree: Uri?) {
        val bytes = readyBytes ?: return
        val source = selectedUri ?: return
        val stem = outBase
        val ext = outExt
        val outputMime = BatchFormat.resolve("file.$ext", null)?.mime ?: mimeForExt(ext)
        if (busy || !validStem(stem)) return
        busy = true
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    saveCopy(context, tree, bytes, stem, ext, outputMime, SelectedSources(context, listOf(source)), batch::decide)
                }
                if (name == null) say("Skipped. Your processed file is still ready.")
                else {
                    say("Saved and verified $name.", saved = true)
                    history = listOf(HistoryEntry(name, lastAction)) + history
                    readyBytes = null
                }
            } catch (e: Exception) {
                say("Save failed: ${e.localizedMessage}. You can choose another folder or name.")
            } finally {
                busy = false
                reloadTick++
            }
        }
    }
    val singleFolder = rememberLauncherForActivityResult(LocalFolder) { uri -> if (uri != null) saveSingleCopy(uri) }

    fun restoreBackup(entry: BackupEntry) {
        if (busy) return
        busy = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { entry.file.readBytes() }.getOrNull() }
            if (bytes == null) { busy = false; say("The backup file could not be read."); return@launch }
            overwriteOriginal(Uri.parse(entry.uriString), bytes, entry.name, "Original restored from backup", entry.file)
        }
    }

    fun deleteBackup(entry: BackupEntry) {
        scope.launch {
            withContext(Dispatchers.IO) {
                entry.file.delete()
                File(entry.file.parentFile, entry.file.name + ".uri").delete()
            }
            backups = backups.filter { it.file != entry.file }
        }
    }

    AuroraTheme {
        BatchCleanerDialog(batch)
        requestedCategory?.let { requested ->
            AlertDialog(
                onDismissRequest = { requestedCategory = null },
                title = { Text("Access to ${requested.label.lowercase(Locale.ROOT)}") },
                text = { Text("MetaVault uses this access to list ${requested.label.lowercase(Locale.ROOT)} on your device. Files stay local. You can choose selected items where Android supports it, or use the file picker without library access.") },
                confirmButton = {
                    AuroraTextButton(onClick = {
                        requestedCategory = null
                        mediaPermissionLauncher.launch(MediaAccessPolicy.permissions(
                            Build.VERSION.SDK_INT, permissionCategory(requested)).toTypedArray())
                    }) { Text("Continue") }
                },
                dismissButton = {
                    AuroraTextButton(onClick = { requestedCategory = null; picker.launch(arrayOf("*/*")) }) { Text("Use file picker") }
                }
            )
        }
        if (confirmOverwrite) {
            AlertDialog(
                onDismissRequest = { confirmOverwrite = false },
                containerColor = Surface2,
                title = { Text("Overwrite original?") },
                text = { Text("$fileName will be replaced by the processed version. MetaVault first stores a backup inside the app, and you can restore it from History.") },
                confirmButton = { AuroraTextButton(onClick = { confirmOverwrite = false; startOverwrite() }) { Text("Overwrite", color = Warn) } },
                dismissButton = { AuroraTextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") } }
            )
        }
        Box(Modifier.fillMaxSize().background(Bg)) {
            AuroraBackground(theme = selectedTheme, scrollOffset = { mainScrollState.value.toFloat() },
                photo = customPhotoBitmap, showTheme = previewThemes || customPhotoBitmap != null)
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                snackbarHost = {
                    SnackbarHost(snackbar) { data ->
                        if (data.visuals is SavedNotice) Snackbar(containerColor = Surface2, contentColor = TextMain) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AuroraLayout.compact)) {
                                AuroraSuccessMark()
                                Text(data.visuals.message)
                            }
                        } else Snackbar(data)
                    }
                },
                bottomBar = {
                    StableGlassBottomBar(page = page, enabled = !busy, onPageSelected = { if (!busy) page = it })
                }
            ) { padding ->
                AuroraContentFrame(padding) {
                    Header()
                    AuroraPageMotion(page, Modifier.weight(1f).fillMaxWidth()) {
                        if (page in LibraryPages) {
                            // Gallery pages use lazy lists, so they must not sit inside a scrolling column.
                            LibraryPage(
                                page = page, items = deviceMedia, loading = libraryLoading,
                                hasAllFiles = allFiles, access = accessByPage.getValue(page),
                                onRequestAccess = { requestedCategory = page }, onAppPermissions = openAppPermissions,
                                onOpen = { openMedia(it) },
                                onSystemPick = {
                                    picker.launch(
                                        when (page) {
                                            Page.PHOTOS -> arrayOf("image/jpeg", "image/png", "image/webp")
                                            Page.VIDEOS -> arrayOf("video/*")
                                            Page.AUDIO -> arrayOf("audio/*")
                                            Page.PDFS -> arrayOf("application/pdf")
                                            else -> arrayOf("*/*")
                                        }
                                    )
                                },
                                onRefresh = { reloadTick++ }, onAllFiles = openAllFilesAccess
                            )
                        } else {
                            Column(Modifier.fillMaxSize().verticalScroll(mainScrollState).padding(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                when (page) {
                                    Page.HOME -> HomePage(
                                        currentName = selectedUri?.let { fileName },
                                        onBatch = { if (!busy) batch.visible = true },
                                        onInspect = { picker.launch(arrayOf("*/*")) },
                                        onBrowse = { page = Page.BROWSE },
                                        onResumeFile = { page = Page.CLEANER }
                                    )
                                    Page.BROWSE -> BrowsePage(counts = counts, accessByPage = accessByPage, onCategory = { page = it }, onPick = { picker.launch(arrayOf("*/*")) })
                                    Page.CLEANER -> CleanerPage(
                                        fileName = fileName, mime = mime, hasFile = selectedUri != null,
                                        isImage = kind == MetadataEngine.Kind.IMAGE,
                                        convert = convert, onConvert = { convert = it }, output = output, onOutput = { output = it },
                                        busy = busy, status = status, ready = readyBytes != null,
                                        onBatch = { batch.visible = true },
                                        onChangeFile = { if (!busy) page = Page.BROWSE },
                                        onClean = {
                                            val uri = selectedUri
                                            if (uri == null) { status = "Choose a file first." }
                                            else if (kind == MetadataEngine.Kind.UNSUPPORTED || kind == MetadataEngine.Kind.OTHER_MEDIA) {
                                                status = "Cleaning this format is not supported yet. Your file is unchanged."
                                            } else {
                                                busy = true; status = "Cleaning locally…"; readyBytes = null
                                                scope.launch {
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            val source = originalUri(context, uri)
                                                            if (kind == MetadataEngine.Kind.IMAGE && convert) {
                                                                val bitmap = context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it) } ?: error("Could not read this image")
                                                                val format = when (output) {
                                                                    "image/png" -> Bitmap.CompressFormat.PNG
                                                                    "image/webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                                                                    else -> Bitmap.CompressFormat.JPEG
                                                                }
                                                                val stream = ByteArrayOutputStream()
                                                                val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 96
                                                                check(bitmap.compress(format, quality, stream)) { "Image export failed" }
                                                                bitmap.recycle()
                                                                val ext = when (output) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                                                                Triple(stream.toByteArray(), ext, "The image was converted and re-encoded, so all embedded metadata was dropped. Pixels may change slightly.")
                                                            } else {
                                                                val r = MetadataEngine.clean(context, source, fileName, mime) ?: error("This format is not supported yet")
                                                                val ext = fileName.substringAfterLast('.', BatchFormat.resolve(fileName, mime)?.extension ?: "bin")
                                                                val summary = "Removed " + r.removed + " field(s); " + r.remaining + " known field(s) still present. " + r.note
                                                                Triple(r.bytes, ext, summary)
                                                            }
                                                        }
                                                    }.onSuccess { (bytes, ext, note) ->
                                                        readyBytes = bytes
                                                        outBase = fileName.substringBeforeLast('.', fileName)
                                                        outExt = ext
                                                        lastAction = "Clean copy saved"
                                                        status = "Cleaned copy prepared. Original is untouched. " + note
                                                    }.onFailure { status = "Could not clean file: ${it.message}" }
                                                    busy = false
                                                }
                                            }
                                        },
                                        extra = {
                                            MetadataSection(
                                                uri = selectedUri, fields = fields, inspecting = inspecting, inspectError = inspectError,
                                                kind = kind, mime = mime,
                                                locationMayBeHidden = !hasLocationAccess,
                                                edits = editValues, busy = busy,
                                                onLocationAccess = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
                                                onSaveEdits = {
                                                    val uri = selectedUri
                                                    if (uri != null) {
                                                        busy = true; status = "Applying edits locally…"; readyBytes = null
                                                        scope.launch {
                                                            runCatching {
                                                                withContext(Dispatchers.IO) {
                                                                    val edits = MetadataEngine.EDITABLE_IMAGE_TAGS.map { (label, tag) -> tag to editValues[label] }.toMap()
                                                                    MetadataEngine.editImage(context, originalUri(context, uri), edits)
                                                                }
                                                            }.onSuccess { r ->
                                                                readyBytes = r.bytes
                                                                outBase = fileName.substringBeforeLast('.', fileName) + "-edited"
                                                                outExt = fileName.substringAfterLast('.', "jpg")
                                                                lastAction = "Edited copy saved"
                                                                status = "Edited copy prepared. Original is untouched. " + r.note
                                                            }.onFailure { status = "Could not edit metadata: ${it.message}" }
                                                            busy = false
                                                        }
                                                    }
                                                }
                                            )
                                        },
                                        result = {
                                            val origExt = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).replace("jpeg", "jpg")
                                            ResultCard(
                                                outBase = outBase, onOutBase = { outBase = it }, outExt = outExt,
                                                canOverwrite = selectedUri != null && outExt.lowercase(Locale.ROOT).replace("jpeg", "jpg") == origExt,
                                                busy = busy, onSaveNew = { singleFolder.launch(null) },
                                                onGallery = if (isImageExt(outExt) && Build.VERSION.SDK_INT >= 29) ({ saveSingleCopy(null) }) else null,
                                                onOverwrite = { confirmOverwrite = true }
                                            )
                                        }
                                    )
                                    Page.HISTORY -> HistoryPage(history, backups, busy = busy, onRestore = { restoreBackup(it) }, onDeleteBackup = { deleteBackup(it) })
                                    Page.SETTINGS -> SettingsPage(
                                        allFiles = allFiles, selectedTheme = selectedTheme, hasCustomPhoto = customPhotoUri != null,
                                        previewThemes = previewThemes, onPreviewThemes = { previewThemes = it },
                                        onThemeSelected = { selectedTheme = it; context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).edit().putString("theme", it).apply() },
                                        onCustomPhoto = { backgroundPicker.launch(arrayOf("image/*")) },
                                        onClearPhoto = { customPhotoUri = null; context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).edit().remove("background_photo").apply() },
                                        onAbout = { page = Page.ABOUT }, onPermissions = openAllFilesAccess
                                    )
                                    Page.ABOUT -> AboutPage()
                                    else -> {}
                                }
                                Text(
                                    "MetaVault is in beta. Your files stay on this device.",
                                    color = Muted, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandMark(markSize: Dp) {
    Canvas(Modifier.size(markSize)) {
        val s = size.minDimension
        val w = s * 0.07f
        val c = s / 2f
        drawRoundRect(Accent, cornerRadius = CornerRadius(s * 0.29f, s * 0.29f))
        drawCircle(Color.White, radius = s * 0.25f, style = Stroke(width = w))
        drawCircle(Color.White, radius = s * 0.07f)
        drawLine(Color.White, Offset(c, s * 0.14f), Offset(c, s * 0.25f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(c, s * 0.75f), Offset(c, s * 0.86f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(s * 0.14f, c), Offset(s * 0.25f, c), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(s * 0.75f, c), Offset(s * 0.86f, c), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(32.dp)
            Spacer(Modifier.width(10.dp))
            Text("MetaVault", color = TextMain, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.4).sp)
        }
        AuroraStatusChip("On device", Clean)
    }
}

@Composable
private fun HomePage(
    currentName: String?, onBatch: () -> Unit, onInspect: () -> Unit, onBrowse: () -> Unit, onResumeFile: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Inspect your files", "Choose a file to see its metadata and available cleaning options.")
        AuroraGlassButton("Inspect a file", Icons.Default.Search, onInspect)
        AuroraGlassCard(onClick = onBatch) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Collections, null, tint = AccentSoft)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Batch clean files", style = MaterialTheme.typography.titleMedium, color = TextMain)
                    Text("Review names and save multiple files together.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }
        }
        AuroraGlassButton("Browse by category", Icons.Default.FolderOpen, onBrowse, primary = false)
        if (currentName != null) {
            AuroraGlassCard(onClick = onResumeFile) {
                Text("Continue with current file", color = Muted, style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(currentName, modifier = Modifier.weight(1f), color = TextMain,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ChevronRight, null, tint = Muted)
                }
            }
        }
    }
}

// Browse is the single place to start picking a file: one card per category, plus the system picker.
@Composable
private fun BrowsePage(counts: Map<String, Int>, accessByPage: Map<Page, MediaAccessPolicy.Access>, onCategory: (Page) -> Unit, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Browse", "Open a category to see your files inside MetaVault.")
        val items = listOf(
            Pair(Page.PHOTOS, Icons.Default.Image),
            Pair(Page.VIDEOS, Icons.Default.VideoLibrary),
            Pair(Page.AUDIO, Icons.Default.MusicNote),
            Pair(Page.PDFS, Icons.Default.PictureAsPdf),
            Pair(Page.DOCS, Icons.Default.Description)
        )
        items.chunked(2).forEach { rowItems ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { (target, icon) ->
                    val (support, supported) = supportShort(target)
                    AuroraGlassCard(Modifier.weight(1f).fillMaxHeight(), onClick = { onCategory(target) }) {
                        AuroraIconContainer(icon)
                        Text(target.label, color = TextMain, style = MaterialTheme.typography.titleMedium)
                        val count = counts[categoryKey(target)] ?: 0
                        val countLabel = when (accessByPage[target]) {
                            MediaAccessPolicy.Access.NONE -> "Access not granted"
                            MediaAccessPolicy.Access.SELECTED -> "$count accessible files"
                            MediaAccessPolicy.Access.PICKER -> "Use file picker"
                            else -> "$count accessible files"
                        }
                        Text(countLabel, color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text(support, color = if (supported) Clean else Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        AuroraGlassCard {
            Text("Somewhere else?", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text("Use Android's file picker for anything that is not listed above.", color = Muted, style = MaterialTheme.typography.bodySmall)
            AuroraGlassButton("Open file picker", Icons.Default.FolderOpen, onPick, primary = false)
        }
    }
}

// ---------- Library (in-app gallery) ----------

@Composable
private fun Thumb(uri: Uri, size: Int, modifier: Modifier, fallback: ImageVector) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(uri, size) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri, size) {
        bitmap = withContext(Dispatchers.IO) { loadThumb(context, uri, size) }
    }
    Box(modifier.background(Surface2), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(fallback, null, tint = Muted)
    }
}

@Composable
private fun LibraryPage(
    page: Page, items: List<DeviceMedia>, loading: Boolean, hasAllFiles: Boolean,
    access: MediaAccessPolicy.Access, onRequestAccess: () -> Unit, onAppPermissions: () -> Unit,
    onOpen: (DeviceMedia) -> Unit, onSystemPick: () -> Unit, onRefresh: () -> Unit, onAllFiles: () -> Unit
) {
    val category = categoryKey(page)
    var query by rememberSaveable(category) { mutableStateOf("") }
    var order by rememberSaveable(category) { mutableStateOf("Newest first") }
    var choosingOrder by remember(category) { mutableStateOf(false) }
    val files = remember(items, category) { items.filter { it.category == category } }
    val list = remember(files, query, order) {
        val matches = files.filter { it.name.contains(query.trim(), ignoreCase = true) }
        val byName = compareBy<DeviceMedia, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.uri.toString() }
        when (order) {
            "Name A–Z" -> matches.sortedWith(byName)
            "Largest first" -> matches.sortedWith(compareByDescending<DeviceMedia> { it.size }.then(byName))
            else -> matches.sortedWith(compareByDescending<DeviceMedia> { it.modified }.then(byName))
        }
    }
    val icon = when (category) { "Photo" -> Icons.Default.Image; "Video" -> Icons.Default.VideoLibrary; "Audio" -> Icons.Default.MusicNote; "PDF" -> Icons.Default.PictureAsPdf; else -> Icons.Default.Description }
    val emptyScroll = if (list.isEmpty()) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(Modifier.fillMaxSize().then(emptyScroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { PageHeading(page.label, list.size.toString() + " files · " + supportShort(page).first) }
            AuroraTextButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Refresh") }
        }
        if (files.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f),
                    label = { Text("Search filenames") }, singleLine = true)
                Box {
                    AuroraTextButton(onClick = { choosingOrder = true }) { Text(order) }
                    DropdownMenu(choosingOrder, { choosingOrder = false }) {
                        listOf("Newest first", "Name A–Z", "Largest first").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { order = option; choosingOrder = false })
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuroraOutlinedButton(onClick = onSystemPick) { Text("File picker") }
            if (access == MediaAccessPolicy.Access.NONE || access == MediaAccessPolicy.Access.SELECTED) {
                AuroraOutlinedButton(onClick = onRequestAccess) {
                    Text(if (access == MediaAccessPolicy.Access.SELECTED) "Manage access" else "Allow access")
                }
            }
        }
        if (access == MediaAccessPolicy.Access.SELECTED) {
            Text("Only your selected photos/videos are accessible. Manage access to select more.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else if (access == MediaAccessPolicy.Access.NONE) {
            Text("Library access is not granted. Choose Allow access or pick an individual file.", color = Muted, style = MaterialTheme.typography.bodySmall)
            AuroraTextButton(onClick = onAppPermissions) { Text("App permission settings") }
        }
        when {
            loading && list.isEmpty() -> AuroraGlassCard { AuroraLoadingLabel("Loading files…") }
            files.isNotEmpty() && list.isEmpty() -> AuroraGlassCard {
                Text("No matching filenames", color = TextMain)
                AuroraTextButton(onClick = { query = "" }) { Text("Clear search") }
            }
            list.isEmpty() -> AuroraGlassCard {
                Icon(icon, null, tint = AccentSoft)
                Text("No files to show", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text(if (access == MediaAccessPolicy.Access.FULL) "No accessible files in this category. Try Refresh or the file picker." else "Files you allow MetaVault to access will appear here.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (Build.VERSION.SDK_INT >= 30 && !hasAllFiles && (category == "PDF" || category == "Document")) {
                    Text("Use the file picker without extra permissions. Optional all-files access can list documents on supported Android versions.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    AuroraGlassButton("Allow all-files access", Icons.Default.Security, onAllFiles)
                }
                AuroraGlassButton("Use file picker", Icons.Default.FolderOpen, onSystemPick, primary = false)
            }
            category == "Photo" || category == "Video" -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(list, key = { it.uri.toString() }) { item ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Line, RoundedCornerShape(12.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = "Open ${item.name}", onClick = { onOpen(item) })
                            .semantics { contentDescription = item.name }
                    ) {
                        Thumb(item.uri, 256, Modifier.fillMaxSize(), icon)
                        if (category == "Video") {
                            Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp))
                        }
                    }
                }
            }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.uri.toString() }) { item ->
                    AuroraGlassCard(onClick = { onOpen(item) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = AccentSoft)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = TextMain, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.mime, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Cleaner ----------

@Composable
private fun MetadataSection(
    uri: Uri?, fields: List<MetaField>?, inspecting: Boolean, inspectError: String?,
    kind: MetadataEngine.Kind, mime: String, locationMayBeHidden: Boolean,
    edits: MutableMap<String, String>, busy: Boolean, onLocationAccess: () -> Unit, onSaveEdits: () -> Unit
) {
    if (uri == null) return
    if (kind == MetadataEngine.Kind.IMAGE || mime.startsWith("video/")) {
        Thumb(uri, 720, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)), Icons.Default.Image)
    }
    AuroraGlassCard {
        Text("Metadata in this file", color = TextMain, style = MaterialTheme.typography.titleMedium)
        when {
            inspecting -> AuroraLoadingLabel("Reading metadata…")
            inspectError != null -> Text(inspectError, color = Muted, style = MaterialTheme.typography.bodySmall)
            kind == MetadataEngine.Kind.UNSUPPORTED -> Text("Viewing metadata is not supported for this format yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            fields == null -> Text("No metadata loaded.", color = Muted, style = MaterialTheme.typography.bodySmall)
            fields.isEmpty() -> Text("No metadata found in the fields MetaVault can read.", color = Muted, style = MaterialTheme.typography.bodySmall)
            else -> fields.forEachIndexed { index, f ->
                if (index > 0) HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(f.key, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
                    Text(f.value, color = TextMain, style = TechnicalLabel, modifier = Modifier.weight(0.6f))
                }
            }
        }
        if (kind == MetadataEngine.Kind.IMAGE && locationMayBeHidden) {
            Text("Android may hide GPS location here. Optional location-metadata access reads location already stored in this photo; it does not track your device.", color = Warn, style = MaterialTheme.typography.bodySmall)
            AuroraTextButton(onClick = onLocationAccess, enabled = !busy && !inspecting) { Text("Allow location metadata") }
        }
    }
    if (kind == MetadataEngine.Kind.IMAGE && fields != null && inspectError == null) {
        AuroraGlassCard {
            Text("Edit image metadata", color = TextMain, style = MaterialTheme.typography.titleMedium)
            MetadataEngine.EDITABLE_IMAGE_TAGS.keys.forEach { label ->
                OutlinedTextField(
                    value = edits[label] ?: "", onValueChange = { edits[label] = it },
                    label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Text("Date format: 2026:11:11 14:30:00. An empty field removes that tag.", color = Muted, style = MaterialTheme.typography.bodySmall)
            AuroraGlassButton(if (busy) "Working…" else "Apply edits to a copy", Icons.Default.Edit, onSaveEdits,
                primary = false, enabled = !busy)
        }
    }
}

@Composable
private fun CleanerPage(
    fileName: String, mime: String, hasFile: Boolean, isImage: Boolean,
    convert: Boolean, onConvert: (Boolean) -> Unit, output: String, onOutput: (String) -> Unit,
    busy: Boolean, status: String, ready: Boolean,
    onChangeFile: () -> Unit, onClean: () -> Unit, onBatch: () -> Unit,
    extra: @Composable () -> Unit,
    result: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Cleaner", "Review what is inside a file, then save a clean copy.")
        AuroraOutlinedButton(onClick = onBatch, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Batch clean files")
        }
        if (!hasFile) {
            AuroraGlassCard {
                Icon(Icons.Default.AutoAwesome, null, tint = AccentSoft, modifier = Modifier.size(30.dp))
                Text("No file selected", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text("Pick a photo, MP3 or Office file from Browse to get started.", color = Muted, style = MaterialTheme.typography.bodySmall)
                AuroraGlassButton("Choose a file", Icons.Default.FolderOpen, onChangeFile)
            }
            return@Column
        }
        AuroraGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, null, tint = AccentSoft, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fileName, color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(mime, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                AuroraTextButton(onClick = onChangeFile, enabled = !busy) { Text("Change") }
            }
        }
        extra()
        if (isImage) {
            AuroraGlassCard {
                SettingRow("Convert to another format", "Re-encodes the image, so pixels may change slightly", convert, onConvert)
                if (convert) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("JPG" to "image/jpeg", "PNG" to "image/png", "WEBP" to "image/webp").forEach { (label, value) ->
                            FilterChip(
                                selected = output == value, onClick = { onOutput(value) }, label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentTint, selectedLabelColor = TextMain)
                            )
                        }
                    }
                }
            }
        }
        AuroraGlassButton(if (busy) "Working…" else "Remove metadata", Icons.Default.AutoAwesome, onClean,
            enabled = !busy, loading = busy)
        if (LocalMotionEnabled.current) {
            AnimatedVisibility(ready, enter = fadeIn(tween(240)), exit = fadeOut(tween(120))) { result() }
        } else if (ready) result()
        Text(status, color = if (ready) Clean else Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultCard(
    outBase: String, onOutBase: (String) -> Unit, outExt: String, canOverwrite: Boolean,
    busy: Boolean, onSaveNew: () -> Unit, onGallery: (() -> Unit)?, onOverwrite: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Clean.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .border(1.dp, Clean.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, null, tint = Clean, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Copy ready", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text("Name it, then choose how to save.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        ExportNameField(outBase, onOutBase, outExt, "File name", enabled = !busy)
        AuroraTextButton(onClick = { onOutBase(cleanStem(outBase)) }, enabled = !busy && validStem(outBase)) { Text("Add _clean") }
        Button(onClick = onSaveNew, enabled = !busy && validStem(outBase), modifier = Modifier.fillMaxWidth()) {
            Text(if (isImageExt(outExt)) "Save as new image in folder" else "Save as new file in folder")
        }
        if (onGallery != null) AuroraOutlinedButton(onClick = onGallery, enabled = !busy && validStem(outBase),
            modifier = Modifier.fillMaxWidth()) { Text("Save to Pictures/MetaVault") }
        AuroraOutlinedButton(
            onClick = onOverwrite, enabled = canOverwrite && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = AuroraLayout.actionHeight), shape = AuroraLayout.buttonShape,
            border = BorderStroke(1.dp, if (canOverwrite) Warn else LineStrong),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Warn, disabledContentColor = Muted)
        ) {
            Icon(Icons.Default.Warning, null)
            Spacer(Modifier.width(9.dp))
            Text("Overwrite original", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            if (canOverwrite) "Choose a folder to save a copy. For an existing name, choose Overwrite, Rename or Skip. Replacements are backed up in History."
            else "Overwrite is unavailable because the output format differs from the original.",
            color = Muted, style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---------- History, Settings, About ----------

@Composable
private fun HistoryPage(history: List<HistoryEntry>, backups: List<BackupEntry>, busy: Boolean, onRestore: (BackupEntry) -> Unit, onDeleteBackup: (BackupEntry) -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("History", "Exports from this session, and backups of originals you overwrote.")
        if (history.isEmpty() && backups.isEmpty()) {
            AuroraGlassCard {
                Icon(Icons.Default.History, null, tint = AccentSoft, modifier = Modifier.size(32.dp))
                Text("Nothing here yet", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text("Saved copies will appear here. The export list is kept in memory for this session only.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        history.forEach {
            AuroraGlassCard {
                Text(it.name, color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(it.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (backups.isNotEmpty()) {
            Text("Backups", color = TextMain, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
            backups.forEach { b ->
                AuroraGlassCard {
                    Text(b.name, color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dateFormat.format(Date(b.time)), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuroraOutlinedButton(onClick = { onRestore(b) }, enabled = !busy, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, LineStrong)) { Text("Restore") }
                        AuroraOutlinedButton(onClick = { onDeleteBackup(b) }, enabled = !busy, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, LineStrong)) { Text("Delete") }
                    }
                }
            }
            Text("A backup is the original file with its metadata. It stays inside MetaVault until you delete it.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsPage(
    allFiles: Boolean, selectedTheme: String, hasCustomPhoto: Boolean,
    previewThemes: Boolean, onPreviewThemes: (Boolean) -> Unit,
    onThemeSelected: (String) -> Unit, onCustomPhoto: () -> Unit, onClearPhoto: () -> Unit,
    onAbout: () -> Unit, onPermissions: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Settings", "Personalise your MetaVault experience.")
        AuroraGlassCard {
            Text("Appearance & themes", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text("The glass stays neutral; themes only change the background.", color = Muted, style = MaterialTheme.typography.bodySmall)
            SettingRow("Preview theme backgrounds", "Show the chosen theme behind the glass", previewThemes, onPreviewThemes)
            val themes = AuroraThemeNames
            themes.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { theme ->
                        FilterChip(selected = selectedTheme == theme, onClick = { onThemeSelected(theme) },
                            label = { Text(theme) }, modifier = Modifier.weight(1f),
                            leadingIcon = if (selectedTheme == theme) ({ Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }) else null)
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            HorizontalDivider(color = Line)
            SettingsAction("Use your own photo", "Set an image from your gallery as the app background", Icons.Default.Image, onCustomPhoto)
            if (hasCustomPhoto) {
                Text("Your custom photo is active.", color = Clean, style = MaterialTheme.typography.bodySmall)
                AuroraTextButton(onClick = onClearPhoto) { Text("Remove custom photo") }
            }
        }
        AuroraGlassCard {
            SettingsAction(
                "All-files access",
                if (allFiles) "Allowed. PDF and Office files are listed in Browse." else "Not allowed. Needed only to list PDF and Office files.",
                Icons.Default.FolderOpen, onPermissions
            )
            HorizontalDivider(color = Line)
            SettingsAction("About MetaVault", "How local processing works and what is supported", Icons.Default.Shield, onAbout)
        }
    }
}

@Composable
private fun AboutPage() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("About MetaVault", "A private workspace for cleaner file sharing.")
        AuroraGlassCard {
            BrandMark(48.dp)
            Text("MetaVault", color = TextMain, style = MaterialTheme.typography.headlineMedium)
            Text("Files can carry hidden details such as capture time, device, location and author names. MetaVault shows what is inside, lets you edit image fields, and saves a cleaner copy. All processing happens on your device and nothing is uploaded.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        AuroraGlassCard {
            InfoBullet("Originals stay safe", "Results are saved as a new file. Overwriting needs your confirmation and keeps a restorable backup.")
            InfoBullet("What can be cleaned", "JPG, PNG and WEBP images, MP3 audio, and DOCX, XLSX and PPTX documents.")
            InfoBullet("What cannot yet", "Video, other audio formats and PDF can be viewed or are unsupported, but are not cleaned in this build. Image cleaning covers supported EXIF, XMP, IPTC and container metadata blocks; embedded trailing content and invisible pixel watermarks are not removed.")
        }
        Text("Android beta. Features are being developed incrementally.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------- Shared pieces ----------

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = TextMain, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StableGlassBottomBar(page: Page, enabled: Boolean, onPageSelected: (Page) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val shape = RoundedCornerShape(24.dp)
    val activeTab = when (page) {
        Page.PHOTOS, Page.VIDEOS, Page.AUDIO, Page.PDFS, Page.DOCS -> Page.BROWSE
        Page.ABOUT -> Page.SETTINGS
        else -> page
    }
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max = AuroraLayout.barMaxWidth).fillMaxWidth()
            .padding(horizontal = AuroraLayout.padding, vertical = AuroraLayout.small)
            .auroraGlass(shape).padding(AuroraLayout.small)) {
            val tabs = listOf(Page.HOME, Page.BROWSE, Page.CLEANER, Page.HISTORY, Page.SETTINGS)
            val tabWidth = (maxWidth / tabs.size).coerceAtLeast(AuroraLayout.touchTarget)
            val overflow = if (maxWidth < AuroraLayout.touchTarget * tabs.size)
                Modifier.horizontalScroll(rememberScrollState()) else Modifier
            Box(overflow) {
                Box(Modifier.width(tabWidth * tabs.size)) {
                    val target = tabWidth * tabs.indexOf(activeTab).coerceAtLeast(0)
                    val offset = if (LocalMotionEnabled.current) animateDpAsState(target,
                        tween(NavigationMillis, easing = FastOutSlowInEasing), label = "bottom-nav-slide")
                        else rememberUpdatedState(target)
                    Box(Modifier.matchParentSize()) {
                        Box(Modifier.offset { IntOffset(offset.value.roundToPx(), 0) }.width(tabWidth).fillMaxHeight()
                            .padding(horizontal = 2.dp, vertical = 1.dp)
                            .background(Color.White.copy(alpha = 0.10f), AuroraLayout.buttonShape))
                    }
                    Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEach { item ->
                            val icon = when (item) {
                                Page.HOME -> Icons.Default.Home
                                Page.BROWSE -> Icons.Default.FolderOpen
                                Page.CLEANER -> Icons.Default.AutoAwesome
                                Page.HISTORY -> Icons.Default.History
                                else -> Icons.Default.Settings
                            }
                            val selected = activeTab == item
                            Column(Modifier.weight(1f).heightIn(min = AuroraLayout.touchTarget).clip(AuroraLayout.buttonShape)
                                .selectable(selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.Tab) {
                                    if (page != item) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                    onPageSelected(item)
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(icon, null, tint = if (selected) TextMain else Muted, modifier = Modifier.size(20.dp).selectionMotion(selected))
                                Text(item.label, color = if (selected) AccentSoft else Muted,
                                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBullet(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = TextMain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsAction(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = title, onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AccentSoft)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Accent,
                uncheckedThumbColor = TextMain, uncheckedTrackColor = Line,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
