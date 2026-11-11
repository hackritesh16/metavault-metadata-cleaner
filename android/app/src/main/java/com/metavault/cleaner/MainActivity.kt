package com.metavault.cleaner

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.content.Intent
import android.provider.Settings
import android.net.Uri as AndroidUri
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

private val Ink = Color(0xFF050A16)
private val Panel = Color(0xCC101D32)
private val Mint = Color(0xFF39F5B5)
private val Aqua = Color(0xFF20C8FF)
private val Blue = Color(0xFF356BFF)
private val Violet = Color(0xFF9274FF)
private val Muted = Color(0xFFA8B8D0)
private val Warn = Color(0xFFFFB454)
private val DisplayFont = FontFamily.SansSerif

private enum class Page(val label: String) {
    HOME("Home"), BROWSE("Browse"), PHOTOS("Photos"), VIDEOS("Videos"),
    AUDIO("Audio"), PDFS("PDF files"), DOCS("Documents"), CLEANER("Cleaner"),
    HISTORY("History"), SETTINGS("Settings"), ABOUT("About")
}

private val LibraryPages = setOf(Page.PHOTOS, Page.VIDEOS, Page.AUDIO, Page.PDFS, Page.DOCS)

private data class HistoryEntry(val name: String, val detail: String)
private data class DeviceMedia(val uri: Uri, val name: String, val mime: String, val category: String)
private data class BackupEntry(val file: File, val name: String, val uriString: String, val time: Long)

// ---------- Device library ----------

private fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

private fun loadDeviceMedia(context: Context): List<DeviceMedia> {
    val resolver = context.contentResolver
    val results = mutableListOf<DeviceMedia>()
    val allFiles = hasAllFilesAccess()
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val partialVisual = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    // Each category has its own limit so one big category (photos) can never hide the others.
    fun read(collection: Uri, category: String, allowed: Boolean, selection: String? = null, args: Array<String>? = null, limit: Int = 5000) {
        if (!allowed) return
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
        runCatching {
            resolver.query(collection, projection, selection, args, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unnamed file"
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "application/octet-stream" else "application/octet-stream"
                    results.add(DeviceMedia(android.content.ContentUris.withAppendedId(collection, id), name, mime, category))
                    count++
                }
            }
        }
    }

    val imagesOk: Boolean
    val videosOk: Boolean
    val audioOk: Boolean
    if (Build.VERSION.SDK_INT >= 33) {
        imagesOk = allFiles || granted(Manifest.permission.READ_MEDIA_IMAGES) || partialVisual
        videosOk = allFiles || granted(Manifest.permission.READ_MEDIA_VIDEO) || partialVisual
        audioOk = allFiles || granted(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        val legacy = allFiles || granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        imagesOk = legacy; videosOk = legacy; audioOk = legacy
    }
    read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photo", imagesOk)
    read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Video", videosOk)
    read(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", audioOk)

    // PDF and Office files are only listed by Android when the app has all-files access (or on Android 9 and below).
    val filesOk = allFiles || (Build.VERSION.SDK_INT < 30 && granted(Manifest.permission.READ_EXTERNAL_STORAGE))
    val files = MediaStore.Files.getContentUri("external")
    val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
    read(files, "PDF", filesOk, "$nameCol LIKE ?", arrayOf("%.pdf"))
    read(
        files, "Document", filesOk,
        "$nameCol LIKE ? OR $nameCol LIKE ? OR $nameCol LIKE ?",
        arrayOf("%.docx", "%.xlsx", "%.pptx")
    )
    return results
}

/** Android hides GPS tags of gallery photos unless we ask for the original (needs ACCESS_MEDIA_LOCATION). */
private fun originalUri(context: Context, uri: Uri): Uri {
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

// ---------- Saving / backups ----------

private fun sanitizeName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "metavault-file" }

private fun mimeForExt(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "jpg", "jpeg" -> "image/jpeg"
    else -> "application/octet-stream"
}

private fun isImageExt(ext: String) = ext.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")

@androidx.annotation.RequiresApi(29)
private fun saveToGallery(context: Context, bytes: ByteArray, displayName: String, mime: String): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MetaVault")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the new image")
    try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open the new image for writing")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
    return uri
}

private fun backupOriginal(context: Context, uri: Uri, name: String): File {
    val dir = File(context.filesDir, "backups").apply { mkdirs() }
    val file = File(dir, System.currentTimeMillis().toString() + "__" + sanitizeName(name) + ".bak")
    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
        ?: error("Could not read the original")
    File(dir, file.name + ".uri").writeText(uri.toString())
    return file
}

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
        window.statusBarColor = android.graphics.Color.rgb(5, 10, 22)
        window.navigationBarColor = android.graphics.Color.rgb(5, 10, 22)
        window.decorView.systemUiVisibility = 0
        setContent { MetaVaultApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaVaultApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.HOME) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No file selected") }
    var mime by remember { mutableStateOf("image/jpeg") }
    var preserve by remember { mutableStateOf(true) }
    var removeMetadata by remember { mutableStateOf(true) }
    var output by remember { mutableStateOf("image/jpeg") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Your files stay on this device.") }
    var readyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var outBase by remember { mutableStateOf("metavault-clean") }
    var outExt by remember { mutableStateOf("jpg") }
    var lastAction by remember { mutableStateOf("Clean copy saved") }
    var history by remember { mutableStateOf(listOf<HistoryEntry>()) }
    var backups by remember { mutableStateOf(listOf<BackupEntry>()) }
    var deviceMedia by remember { mutableStateOf(emptyList<DeviceMedia>()) }
    var libraryStatus by remember { mutableStateOf("Requesting media access…") }
    var libraryLoading by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    val retryOverwrite = remember { mutableStateOf<(() -> Unit)?>(null) }
    var fields by remember { mutableStateOf<List<MetaField>?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var inspectError by remember { mutableStateOf<String?>(null) }
    val editValues = remember { mutableStateMapOf<String, String>() }
    val allFiles = remember(reloadTick) { hasAllFilesAccess() }
    val saveName = sanitizeName(outBase) + "." + outExt

    // Reload the library whenever the app comes back to the foreground (e.g. after granting access in Settings).
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reloadTick++
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(reloadTick) {
        libraryLoading = true
        val (list, saved) = withContext(Dispatchers.IO) { loadDeviceMedia(context) to loadBackups(context) }
        deviceMedia = list
        backups = saved
        libraryStatus = if (list.isEmpty()) "No accessible files found. Allow media access, and all-files access for PDF/Office files."
        else "Loaded " + list.size + " files from device storage."
        libraryLoading = false
    }

    LaunchedEffect(selectedUri) {
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
        if (grants.values.none { it }) libraryStatus = "Media permission was denied. Use the system picker to select items manually."
    }
    LaunchedEffect(Unit) {
        val requested = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES); add(Manifest.permission.READ_MEDIA_VIDEO); add(Manifest.permission.READ_MEDIA_AUDIO)
                if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        val missing = requested.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) mediaPermissionLauncher.launch(missing.toTypedArray())
    }

    val openAllFilesAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = AndroidUri.parse("package:${context.packageName}") }
            runCatching { context.startActivity(intent) }.onFailure { status = "Could not open Android settings." }
        } else status = "Use Android's file picker to choose files."
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
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
            status = "File selected. Review it in Cleaner."
            page = Page.CLEANER
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = readyBytes
        if (uri != null && bytes != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("Could not open destination")
                    }
                }.onSuccess {
                    status = "Saved as a new file. Original file is untouched."
                    history = listOf(HistoryEntry(fileName, lastAction)) + history
                    readyBytes = null
                }.onFailure { status = "Save failed: ${it.message}" }
            }
        }
    }
    val writeConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) retryOverwrite.value?.invoke()
        else { status = "Permission to overwrite was not granted. Your original is unchanged."; busy = false }
    }

    fun openMedia(item: DeviceMedia) {
        selectedUri = item.uri; fileName = item.name; mime = item.mime
        readyBytes = null; status = "File selected from device library."; page = Page.CLEANER
    }

    fun overwriteOriginal(uri: Uri, bytes: ByteArray, label: String, detail: String, retried: Boolean = false) {
        scope.launch {
            busy = true
            val outcome: Pair<String, IntentSender?> = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("Could not open the original for writing")
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
                    status = "Done. The original file was replaced. A backup is kept; restore it from History."
                    history = listOf(HistoryEntry(label, detail)) + history
                    readyBytes = null
                    thumbCache.evictAll()
                    reloadTick++
                    busy = false
                }
                code == "consent" && !retried -> {
                    retryOverwrite.value = { overwriteOriginal(uri, bytes, label, detail, true) }
                    val request = sender ?: runCatching {
                        if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender else null
                    }.getOrNull()
                    if (request != null) writeConsentLauncher.launch(IntentSenderRequest.Builder(request).build())
                    else { status = "Android does not allow overwriting this file. Use Save as new instead. Your original is unchanged."; busy = false }
                }
                code == "consent" -> { status = "Permission to overwrite was not granted. Your original is unchanged."; busy = false }
                else -> { status = "Overwrite failed: " + code.removePrefix("error:") + ". A backup of the original is kept in History."; busy = false }
            }
        }
    }

    fun startOverwrite() {
        val uri = selectedUri
        val bytes = readyBytes
        if (uri == null || bytes == null) return
        scope.launch {
            busy = true; status = "Backing up the original first…"
            val backupFailure = try {
                withContext(Dispatchers.IO) { backupOriginal(context, originalUri(context, uri), fileName) }
                null
            } catch (e: Exception) { e.message ?: "unknown error" }
            if (backupFailure != null) {
                status = "Could not create a backup, so nothing was overwritten: $backupFailure"
                busy = false
            } else {
                backups = withContext(Dispatchers.IO) { loadBackups(context) }
                overwriteOriginal(uri, bytes, fileName, "$lastAction (original overwritten)")
            }
        }
    }

    fun saveAsNew() {
        val bytes = readyBytes ?: return
        if (isImageExt(outExt) && Build.VERSION.SDK_INT >= 29) {
            scope.launch {
                busy = true; status = "Saving new image…"
                runCatching {
                    withContext(Dispatchers.IO) { saveToGallery(context, bytes, saveName, mimeForExt(outExt)) }
                }.onSuccess {
                    status = "Saved as a new image in Pictures/MetaVault. Original file is untouched."
                    history = listOf(HistoryEntry(saveName, "$lastAction (new image)")) + history
                    readyBytes = null
                    reloadTick++
                }.onFailure { status = "Save failed: ${it.message}" }
                busy = false
            }
        } else saver.launch(saveName)
    }

    fun restoreBackup(entry: BackupEntry) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { entry.file.readBytes() }.getOrNull() }
            if (bytes == null) { status = "The backup file could not be read."; return@launch }
            overwriteOriginal(Uri.parse(entry.uriString), bytes, entry.name, "Original restored from backup")
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

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink, surface = Panel, primary = Mint, secondary = Aqua,
            tertiary = Violet, onBackground = Color.White, onSurface = Color.White,
            onPrimary = Color.White, onSecondary = Color.White, onTertiary = Color.White,
            onSurfaceVariant = Color(0xFFD0DCEC), surfaceVariant = Color(0xFF1B2B42)
        ),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = DisplayFont),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = DisplayFont)
        )
    ) {
        if (confirmOverwrite) {
            AlertDialog(
                onDismissRequest = { confirmOverwrite = false },
                containerColor = Color(0xFF101D32),
                title = { Text("Overwrite original?") },
                text = { Text("The original file will be replaced by the processed version. MetaVault first stores a backup inside the app, and you can restore it from History.") },
                confirmButton = { TextButton(onClick = { confirmOverwrite = false; startOverwrite() }) { Text("OVERWRITE", color = Warn) } },
                dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("CANCEL") } }
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF07162A), Ink, Color(0xFF061D20), Color(0xFF090D24))))) {
            Box(Modifier.size(240.dp).offset(x = 210.dp, y = 80.dp).background(Brush.radialGradient(listOf(Color(0x3334F5B5), Color.Transparent))))
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        containerColor = Color(0xF20A1426),
                        tonalElevation = 0.dp,
                        modifier = Modifier.border(1.dp, Color(0x2239F5B5), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    ) {
                        val activeTab = when (page) {
                            Page.PHOTOS, Page.VIDEOS, Page.AUDIO, Page.PDFS, Page.DOCS -> Page.BROWSE
                            Page.ABOUT -> Page.SETTINGS
                            else -> page
                        }
                        listOf(Page.HOME, Page.BROWSE, Page.CLEANER, Page.HISTORY, Page.SETTINGS).forEach { item ->
                            val icon = when (item) {
                                Page.HOME -> Icons.Default.Home
                                Page.BROWSE -> Icons.Default.FolderOpen
                                Page.CLEANER -> Icons.Default.AutoAwesome
                                Page.HISTORY -> Icons.Default.History
                                else -> Icons.Default.Settings
                            }
                            NavigationBarItem(
                                selected = activeTab == item,
                                onClick = { page = item },
                                icon = { Icon(icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Mint, selectedTextColor = Mint,
                                    indicatorColor = Color(0x2239F5B5), unselectedIconColor = Muted,
                                    unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).statusBarsPadding()
                        .padding(horizontal = 18.dp).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Header(onAbout = { page = Page.ABOUT })
                    if (page in LibraryPages) {
                        // Gallery pages use lazy lists, so they must not sit inside a scrolling column.
                        Box(Modifier.weight(1f)) {
                            LibraryPage(
                                page = page, items = deviceMedia, loading = libraryLoading, status = libraryStatus,
                                hasAllFiles = allFiles, onOpen = { openMedia(it) },
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
                        }
                    } else {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            AnimatedContent(targetState = page, label = "page") { current ->
                                when (current) {
                                    Page.HOME -> HomePage(
                                        onBrowse = { page = Page.BROWSE }, onClean = { page = Page.CLEANER },
                                        onCategory = { page = it }, onHistory = { page = Page.HISTORY }
                                    )
                                    Page.BROWSE -> BrowsePage(
                                        onCategory = { page = it }, onPick = { picker.launch(arrayOf("*/*")) },
                                        media = deviceMedia, libraryStatus = libraryStatus, loading = libraryLoading
                                    )
                                    Page.CLEANER -> CleanerPage(
                                        fileName, mime, selectedUri != null, preserve, { preserve = it },
                                        removeMetadata, { removeMetadata = it }, output, { output = it },
                                        busy, status, readyBytes != null,
                                        onPick = { picker.launch(arrayOf("*/*")) },
                                        onClean = {
                                            val uri = selectedUri
                                            val kind = MetadataEngine.kindOf(fileName, mime)
                                            if (uri == null) { status = "Choose a file first." }
                                            else if (!removeMetadata) { status = "Enable metadata removal to continue." }
                                            else if (kind == MetadataEngine.Kind.UNSUPPORTED || kind == MetadataEngine.Kind.OTHER_MEDIA) {
                                                status = "Cleaning this format is not supported yet. Your file is unchanged."
                                            } else {
                                                busy = true; status = "Cleaning locally…"; readyBytes = null
                                                scope.launch {
                                                    runCatching {
                                                        withContext(Dispatchers.IO) {
                                                            val source = originalUri(context, uri)
                                                            if (kind == MetadataEngine.Kind.IMAGE && !preserve) {
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
                                                                Triple(stream.toByteArray(), ext, "Image was converted and re-encoded, so all embedded metadata was dropped. Pixels may change slightly.")
                                                            } else {
                                                                val r = MetadataEngine.clean(context, source, fileName, mime) ?: error("This format is not supported yet")
                                                                val ext = fileName.substringAfterLast('.', "bin")
                                                                val summary = "Removed " + r.removed + " field(s); " + r.remaining + " known field(s) still present. " + r.note
                                                                Triple(r.bytes, ext, summary)
                                                            }
                                                        }
                                                    }.onSuccess { (bytes, ext, note) ->
                                                        readyBytes = bytes
                                                        outBase = fileName.substringBeforeLast('.', fileName) + "-clean"
                                                        outExt = ext
                                                        lastAction = "Clean copy saved"
                                                        status = "Cleaned copy prepared. Original file is untouched. " + note
                                                    }.onFailure { status = "Could not clean file: ${it.message}" }
                                                    busy = false
                                                }
                                            }
                                        },
                                        extra = {
                                            MetadataSection(
                                                uri = selectedUri, fields = fields, inspecting = inspecting, inspectError = inspectError,
                                                kind = MetadataEngine.kindOf(fileName, mime), mime = mime, hasFile = selectedUri != null,
                                                locationMayBeHidden = Build.VERSION.SDK_INT >= 29 &&
                                                    context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED,
                                                edits = editValues, busy = busy,
                                                onSaveEdits = {
                                                    val uri = selectedUri
                                                    if (uri != null) {
                                                        busy = true; status = "Saving edits locally…"; readyBytes = null
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
                                                                status = "Edited copy prepared. Original file is untouched. " + r.note
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
                                                busy = busy, onSaveNew = { saveAsNew() }, onOverwrite = { confirmOverwrite = true }
                                            )
                                        }
                                    )
                                    Page.HISTORY -> HistoryPage(history, backups, onRestore = { restoreBackup(it) }, onDeleteBackup = { deleteBackup(it) })
                                    Page.SETTINGS -> SettingsPage(onAbout = { page = Page.ABOUT }, onPermissions = openAllFilesAccess, status = status)
                                    Page.ABOUT -> AboutPage()
                                    else -> {}
                                }
                            }
                            Text("METAVAULT  •  CLEAN · PROTECT · STAY PRIVATE", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------- Library (in-app gallery) ----------

@Composable
private fun Thumb(uri: Uri, size: Int, modifier: Modifier, fallback: ImageVector) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { loadThumb(context, uri, size) } }
    Box(modifier.background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(fallback, null, tint = Muted)
    }
}

private fun supportText(page: Page) = when (page) {
    Page.PHOTOS -> "JPG · PNG · WEBP: view, edit and clean metadata without re-encoding."
    Page.VIDEOS -> "Metadata can be viewed. Cleaning video is not implemented yet."
    Page.AUDIO -> "MP3 tags can be viewed and removed. Other audio is view-only."
    Page.PDFS -> "PDF cleaning is not implemented yet. No PDF will be altered."
    else -> "DOCX · XLSX · PPTX author and company details can be viewed and cleared."
}

@Composable
private fun LibraryPage(
    page: Page, items: List<DeviceMedia>, loading: Boolean, status: String, hasAllFiles: Boolean,
    onOpen: (DeviceMedia) -> Unit, onSystemPick: () -> Unit, onRefresh: () -> Unit, onAllFiles: () -> Unit
) {
    val category = when (page) { Page.PHOTOS -> "Photo"; Page.VIDEOS -> "Video"; Page.AUDIO -> "Audio"; Page.PDFS -> "PDF"; else -> "Document" }
    val list = remember(items, category) { items.filter { it.category == category } }
    val icon = when (category) { "Photo" -> Icons.Default.Image; "Video" -> Icons.Default.VideoLibrary; "Audio" -> Icons.Default.MusicNote; "PDF" -> Icons.Default.PictureAsPdf; else -> Icons.Default.Description }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageHeading(page.label, list.size.toString() + " items · tap one to view, edit or clean")
        Text(supportText(page), color = Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("REFRESH", fontSize = 12.sp) }
            OutlinedButton(onClick = onSystemPick, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("SYSTEM PICKER", fontSize = 12.sp) }
        }
        when {
            loading && list.isEmpty() -> GlassCard { Text("Loading your " + page.label.lowercase() + "…", color = Muted, fontSize = 12.sp) }
            list.isEmpty() -> GlassCard(accent = Aqua) {
                Text("Nothing to show yet", fontWeight = FontWeight.Bold)
                Text(status, color = Muted, fontSize = 12.sp)
                if (!hasAllFiles) {
                    Text("PDF and Office files only appear with all-files access. You can also use the system picker.", color = Muted, fontSize = 11.sp)
                    GradientButton("ALLOW ALL FILES ACCESS", Icons.Default.Security, onAllFiles)
                }
            }
            category == "Photo" || category == "Video" -> LazyVerticalGrid(
                columns = GridCells.Fixed(3), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(list, key = { it.uri.toString() }) { item ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x3339F5B5), RoundedCornerShape(14.dp))
                            .clickable { onOpen(item) }
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
                    GlassCard(Modifier.clickable { onOpen(item) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = Aqua)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.mime, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Mint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(
    uri: Uri?, fields: List<MetaField>?, inspecting: Boolean, inspectError: String?,
    kind: MetadataEngine.Kind, mime: String, hasFile: Boolean, locationMayBeHidden: Boolean,
    edits: MutableMap<String, String>, busy: Boolean, onSaveEdits: () -> Unit
) {
    if (!hasFile || uri == null) return
    if (kind == MetadataEngine.Kind.IMAGE || mime.startsWith("video/")) {
        Thumb(uri, 720, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp)), Icons.Default.Image)
    }
    GlassCard(accent = Aqua) {
        Text("METADATA FOUND", color = Aqua, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        when {
            inspecting -> Text("Reading metadata…", color = Muted, fontSize = 12.sp)
            inspectError != null -> Text(inspectError, color = Muted, fontSize = 12.sp)
            kind == MetadataEngine.Kind.UNSUPPORTED -> Text("Viewing metadata is not supported for this format yet.", color = Muted, fontSize = 12.sp)
            fields == null -> Text("No metadata loaded.", color = Muted, fontSize = 12.sp)
            fields.isEmpty() -> Text("No metadata found in the fields MetaVault can read.", color = Muted, fontSize = 12.sp)
            else -> fields.forEach { f ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(f.key, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(0.4f))
                    Text(f.value, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(0.6f))
                }
                HorizontalDivider(color = Color(0x1FFFFFFF))
            }
        }
        if (kind == MetadataEngine.Kind.IMAGE && locationMayBeHidden) {
            Text("Android may hide GPS location from this view until media-location access is allowed for MetaVault.", color = Warn, fontSize = 11.sp)
        }
    }
    if (kind == MetadataEngine.Kind.IMAGE && fields != null && inspectError == null) {
        GlassCard(accent = Violet) {
            Text("EDIT IMAGE METADATA", color = Violet, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
            MetadataEngine.EDITABLE_IMAGE_TAGS.keys.forEach { label ->
                OutlinedTextField(
                    value = edits[label] ?: "", onValueChange = { edits[label] = it },
                    label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Text("Date format: 2026:11:11 14:30:00. Leaving a field empty removes that tag. The edit becomes a copy you can save below.", color = Muted, fontSize = 11.sp)
            GradientButton(if (busy) "WORKING…" else "PREPARE EDITED COPY", Icons.Default.Edit, { if (!busy) onSaveEdits() })
        }
    }
}

@Composable
private fun ResultCard(
    outBase: String, onOutBase: (String) -> Unit, outExt: String, canOverwrite: Boolean,
    busy: Boolean, onSaveNew: () -> Unit, onOverwrite: () -> Unit
) {
    GlassCard(accent = Mint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, null, tint = Mint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text("Copy ready", fontWeight = FontWeight.Bold); Text("Name it, then choose how to save", color = Muted, fontSize = 11.sp) }
        }
        OutlinedTextField(
            value = outBase, onValueChange = onOutBase, label = { Text("File name") },
            suffix = { Text("." + outExt) }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        GradientButton(if (busy) "WORKING…" else "SAVE AS NEW FILE", Icons.Default.Download, { if (!busy) onSaveNew() })
        OutlinedButton(onClick = onOverwrite, enabled = canOverwrite && !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
            Icon(Icons.Default.Warning, null, tint = Warn)
            Spacer(Modifier.width(8.dp))
            Text("OVERWRITE ORIGINAL", color = if (canOverwrite) Warn else Muted, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        Text(
            if (canOverwrite) "Save as new keeps your original untouched (images go to Pictures/MetaVault). Overwrite replaces the original after storing a backup you can restore from History."
            else "Overwrite is unavailable because the output format differs from the original. Use Save as new.",
            color = Muted, fontSize = 11.sp
        )
    }
}

@Composable
private fun Header(onAbout: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Mint, Aqua, Blue)), RoundedCornerShape(14.dp)).padding(1.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(Color(0xFF071426), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Text("M", color = Mint, fontSize = 25.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("MetaVault", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp)
                Text("METADATA CLEANER", color = Muted, fontSize = 9.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Bold)
            }
        }
        Surface(color = Color(0x2239F5B5), shape = RoundedCornerShape(50), modifier = Modifier.clickable { onAbout() }) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = Mint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("LOCAL ONLY", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomePage(onBrowse: () -> Unit, onClean: () -> Unit, onCategory: (Page) -> Unit, onHistory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Surface(color = Color(0x2239F5B5), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Mint, RoundedCornerShape(50)))
                    Spacer(Modifier.width(7.dp))
                    Text("PRIVATE FILE WORKSPACE  ·  BETA", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Your files.", color = Color.White, fontSize = 33.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.4).sp)
            Text("Your privacy.", color = Mint, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.3).sp)
            Text("A smarter, safer way to prepare photos and personal files before sharing them. View, edit and clean supported file metadata, and export a fresh copy — all on your device.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("ON-DEVICE" to Mint, "NO CLOUD" to Aqua, "SAFE EXPORT" to Violet).forEach { (label, tint) ->
                Surface(color = tint.copy(alpha = 0.10f), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(tint, RoundedCornerShape(50)))
                        Spacer(Modifier.width(5.dp))
                        Text(label, color = tint, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.35.sp, maxLines = 1)
                    }
                }
            }
        }
        GlassCard(accent = Mint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("PRIVATE FILE WORKSPACE", color = Mint, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                    Text("Your privacy deserves a cleaner.", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("MetaVault helps you see and remove hidden details before sharing. Choose your files, review the options, and save a separate clean copy. Your original stays untouched unless you choose to overwrite it.", color = Muted, fontSize = 12.sp)
                }
                Icon(Icons.Default.Security, null, tint = Mint, modifier = Modifier.size(52.dp))
            }
            GradientButton("OPEN FILE LIBRARY", Icons.Default.FolderOpen, onBrowse)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("07", "CLEAN FORMATS", Mint, Modifier.weight(1f))
            StatTile("LOCAL", "PROCESSING", Aqua, Modifier.weight(1f))
            StatTile("SAFE", "BACKUPS", Violet, Modifier.weight(1f))
        }
        SectionTitle("EXPLORE CATEGORIES", "Choose a file type")
        CategoryGrid(onCategory)
        GlassCard(accent = Mint) {
            Text("ON-DEVICE PRIVACY", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("Your files are processed locally. Open a category to browse and pick files inside the app, or use the secure file picker.", color = Muted, fontSize = 12.sp)
            GradientButton("VIEW DEVICE LIBRARY", Icons.Default.FolderOpen, onBrowse)
        }
        GlassCard(accent = Aqua) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = Aqua, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ready to clean?", fontWeight = FontWeight.Bold)
                    Text("Start with a photo, MP3 or Office file.", color = Muted, fontSize = 12.sp)
                }
            }
            GradientButton("OPEN CLEANER", Icons.Default.CleaningServices, onClean)
        }
        Text("RECENT ACTIVITY", color = Muted, fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onHistory() })
        Row(Modifier.fillMaxWidth().clickable { onHistory() }, verticalAlignment = Alignment.CenterVertically) {
            Text("View cleaning history", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = Mint)
        }
    }
}

@Composable
private fun BrowsePage(onCategory: (Page) -> Unit, onPick: () -> Unit, media: List<DeviceMedia>, libraryStatus: String, loading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        PageHeading("File library", "Open a category to browse files inside MetaVault.")
        CategoryGrid(onCategory)
        GlassCard(accent = Mint) {
            Text("ON-DEVICE LIBRARY", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(if (loading) "Loading…" else libraryStatus, color = Muted, fontSize = 12.sp)
            val grouped = media.groupBy { it.category }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Photo", "Video", "Audio", "PDF", "Document").forEach { category ->
                    Column(Modifier.weight(1f)) {
                        Text((grouped[category]?.size ?: 0).toString(), color = Mint, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text(category.uppercase(), color = Muted, fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
        GlassCard {
            Text("BROWSE WITH SYSTEM PICKER", color = Mint, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
            Text("Prefer Android's own file picker? You can still choose any file this way.", color = Muted, fontSize = 12.sp)
            GradientButton("BROWSE ALL FILES", Icons.Default.FolderOpen, onPick)
        }
        GlassCard(accent = Aqua) {
            Text("ACCESS & PRIVACY", color = Aqua, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Files stay on this device. All-files access is optional; it is only needed to list PDF and Office files in the app.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CategoryGrid(onCategory: (Page) -> Unit) {
    val items = listOf(
        Triple(Page.PHOTOS, Icons.Default.Image, Mint),
        Triple(Page.VIDEOS, Icons.Default.VideoLibrary, Aqua),
        Triple(Page.AUDIO, Icons.Default.MusicNote, Violet),
        Triple(Page.PDFS, Icons.Default.PictureAsPdf, Color(0xFFFF718B)),
        Triple(Page.DOCS, Icons.Default.Description, Color(0xFF72A8FF))
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { (target, icon, tint) ->
                    GlassCard(Modifier.weight(1f).clickable { onCategory(target) }, accent = tint) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(25.dp))
                        Text(target.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            when (target) {
                                Page.PHOTOS -> "View · edit · clean"
                                Page.AUDIO -> "MP3 clean · view"
                                Page.DOCS -> "DOCX · XLSX · PPTX"
                                Page.VIDEOS -> "View only"
                                else -> "Browse files"
                            },
                            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CleanerPage(
    fileName: String, mime: String, hasFile: Boolean, preserve: Boolean, onPreserve: (Boolean) -> Unit,
    remove: Boolean, onRemove: (Boolean) -> Unit, output: String, onOutput: (String) -> Unit,
    busy: Boolean, status: String, ready: Boolean,
    onPick: () -> Unit, onClean: () -> Unit,
    extra: @Composable () -> Unit,
    result: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Cleaner studio", "Inspect your selection, choose options, then export a fresh copy.")
        GlassCard(accent = Mint) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SELECTED FILE", color = Mint, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                Surface(color = if (hasFile) Color(0x2239F5B5) else Color(0x22A8B8D0), shape = RoundedCornerShape(50)) {
                    Text(if (hasFile) "READY TO REVIEW" else "AWAITING FILE", color = if (hasFile) Mint else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, null, tint = Aqua, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (hasFile) fileName else "No file selected", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (hasFile) mime else "Choose a file to get started", color = Muted, fontSize = 11.sp)
                }
            }
            GradientButton("CHOOSE FILE", Icons.Default.FolderOpen, onPick)
        }
        extra()
        GlassCard {
            Text("CLEANING OPTIONS", color = Mint, fontSize = 11.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
            SettingRow("Remove metadata", "Supported fields are removed from a new copy", remove, onRemove)
            HorizontalDivider(color = Color(0x22FFFFFF))
            SettingRow("Keep original format", "Images only. Off = convert and re-encode", preserve, onPreserve)
            if (!preserve) {
                Text("OUTPUT FORMAT (IMAGES)", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("JPG" to "image/jpeg", "PNG" to "image/png", "WEBP" to "image/webp").forEach { (label, value) ->
                        FilterChip(selected = output == value, onClick = { onOutput(value) }, label = { Text(label) })
                    }
                }
            }
        }
        Button(onClick = onClean, enabled = hasFile && !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White, disabledContentColor = Color.White)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null)
            Spacer(Modifier.width(9.dp)); Text(if (busy) "PROCESSING LOCALLY…" else "CLEAN SELECTED FILE", color = Color.White, fontWeight = FontWeight.Bold)
        }
        AnimatedVisibility(ready) { result() }
        GlassCard { Text(status, color = if (ready) Mint else Muted, fontSize = 12.sp) }
        GlassCard(accent = Aqua) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Aqua, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Clean: JPG · PNG · WEBP · MP3 · DOCX · XLSX · PPTX. View only: other audio and video. PDF is not available yet.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun HistoryPage(history: List<HistoryEntry>, backups: List<BackupEntry>, onRestore: (BackupEntry) -> Unit, onDeleteBackup: (BackupEntry) -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Activity history", "Exports from this session, plus backups of overwritten originals.")
        if (history.isEmpty()) {
            GlassCard(accent = Aqua) {
                Icon(Icons.Default.History, null, tint = Aqua, modifier = Modifier.size(34.dp))
                Text("Nothing here yet", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Your successful exports will appear here. The activity list is kept in memory for this session only.", color = Muted, fontSize = 12.sp)
            }
        } else history.forEach {
            GlassCard { Text(it.name, fontWeight = FontWeight.Bold); Text(it.detail, color = Muted, fontSize = 12.sp) }
        }
        if (backups.isNotEmpty()) {
            SectionTitle("BACKUPS", "Originals saved before overwriting")
            backups.forEach { b ->
                GlassCard(accent = Warn) {
                    Text(b.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dateFormat.format(Date(b.time)), color = Muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onRestore(b) }, modifier = Modifier.weight(1f)) { Text("RESTORE ORIGINAL", fontSize = 11.sp) }
                        OutlinedButton(onClick = { onDeleteBackup(b) }, modifier = Modifier.weight(1f)) { Text("DELETE BACKUP", fontSize = 11.sp) }
                    }
                }
            }
            Text("Backups contain the original file including its metadata. They stay inside MetaVault until you delete them.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsPage(onAbout: () -> Unit, onPermissions: () -> Unit, status: String) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Settings", "Manage access and learn how MetaVault handles your files.")
        GlassCard(accent = Mint) {
            Text("PRIVACY & ACCESS", color = Mint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            SettingsAction("All-files access", "Needed only to list PDF and Office files in the app", Icons.Default.FolderOpen, onPermissions)
            HorizontalDivider(color = Color(0x22FFFFFF))
            SettingsAction("Privacy & about", "How local processing works", Icons.Default.Shield, onAbout)
        }
        GlassCard {
            Text("CURRENT SESSION", color = Aqua, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(status, color = Muted, fontSize = 12.sp)
            Text("No cloud upload or account is required. Everything runs on your device.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AboutPage() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("About MetaVault", "A private workspace for cleaner file sharing.")
        GlassCard(accent = Mint) {
            Text("M", color = Mint, fontSize = 54.sp, fontWeight = FontWeight.Black)
            Text("MetaVault", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
            Text("MetaVault is a privacy-first metadata tool built to help you take control of the information attached to your files. Photos can contain capture time, device information and location tags; documents can carry author and company names. MetaVault shows what is inside, lets you edit image fields, and saves a cleaner copy. Processing happens locally on your device. Originals are only replaced if you explicitly choose to overwrite, and a backup is kept first.", color = Muted, fontSize = 12.sp)
        }
        GlassCard {
            InfoLine("01", "Local by design", "Viewing, editing and cleaning all run on your device.")
            InfoLine("02", "Originals stay safe", "Results are saved as a new file unless you confirm an overwrite, which keeps a restorable backup.")
            InfoLine("03", "Honest support", "Clean: JPG, PNG, WEBP, MP3, DOCX, XLSX, PPTX. View only: other audio and video. PDF and video cleaning are planned and are not active in this build.")
        }
        Text("Android beta · Features are being developed incrementally.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.width(34.dp).height(3.dp).background(Brush.horizontalGradient(listOf(Mint, Aqua, Violet)), RoundedCornerShape(50)))
        Text(title, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SectionTitle(eyebrow: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow, color = Mint, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, accent: Color = Mint, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xDD14263D), Color(0xB20B182B), Color(0xCC102B31))), RoundedCornerShape(22.dp))
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.45f), Color(0x337F9CC2), Aqua.copy(alpha = 0.22f))), RoundedCornerShape(22.dp))
            .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content
    )
}

@Composable
private fun GradientButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White, disabledContentColor = Color.White),
        contentPadding = PaddingValues(1.dp)
    ) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF17B98D), Color(0xFF168FC4), Color(0xFF5367DB))), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(9.dp)); Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier, tint) {
        Text(value, color = tint, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoLine(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(number, color = Mint, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        Spacer(Modifier.width(11.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(detail, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsAction(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Mint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp); Text(detail, color = Muted, fontSize = 11.sp) }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
