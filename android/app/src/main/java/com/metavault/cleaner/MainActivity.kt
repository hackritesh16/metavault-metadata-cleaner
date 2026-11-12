package com.metavault.cleaner

import android.Manifest
import android.app.Activity
import android.content.ContentValues
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Palette shared with the web app (see style.css).
private val Bg = Color(0xFF0A0F1A)
private val Surface1 = Color(0xFF0F1624)
private val Surface2 = Color(0xFF141D2F)
private val Line = Color(0xFF1F2A40)
private val LineStrong = Color(0xFF33425F)
private val TextMain = Color(0xFFEEF2F8)
private val Muted = Color(0xFFCDD1D8)
private val Accent = Color(0xFF3A63E8)
private val AccentSoft = Color(0xFF8EABFF)
private val AccentTint = Color(0x333A63E8)
private val Clean = Color(0xFF3DDC97)
private val Warn = Color(0xFFFFB454)
private val Paper = Color(0xFFF3F5F8)
private val PaperInk = Color(0xFF0B1018)
private val PaperMuted = Color(0xFF4B586D)

// Fonts are bundled in res/font so the app never downloads them.
private val DisplayFont = FontFamily(
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold)
)
private val BodyFont = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold)
)
private val MonoFont = FontFamily(Font(R.font.jetbrains_mono_medium, FontWeight.Medium))

private val VaultTypography = Typography(
    headlineLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 39.sp, letterSpacing = (-0.9).sp),
    headlineMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

private enum class Page(val label: String) {
    HOME("Home"), BROWSE("Browse"), PHOTOS("Photos"), VIDEOS("Videos"),
    AUDIO("Audio"), PDFS("PDF files"), DOCS("Documents"), CLEANER("Cleaner"),
    HISTORY("History"), SETTINGS("Settings"), ABOUT("About")
}

private val LibraryPages = setOf(Page.PHOTOS, Page.VIDEOS, Page.AUDIO, Page.PDFS, Page.DOCS)

private data class HistoryEntry(val name: String, val detail: String)
private data class DeviceMedia(val uri: Uri, val name: String, val mime: String, val category: String)
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

// ---------- Device library ----------

private fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

private fun loadDeviceMedia(context: Context): List<DeviceMedia> {
    val resolver = context.contentResolver
    val results = mutableListOf<DeviceMedia>()
    val allFiles = hasAllFilesAccess()
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val partialVisual = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    // Each category has its own limit, so a large category (photos) can never hide the others.
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

    // PDF and Office files are only listed by Android with all-files access (or on Android 9 and below).
    val filesOk = allFiles || (Build.VERSION.SDK_INT < 30 && granted(Manifest.permission.READ_EXTERNAL_STORAGE))
    val files = MediaStore.Files.getContentUri("external")
    val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
    read(files, "PDF", filesOk, "$nameCol LIKE ?", arrayOf("%.pdf"))
    read(files, "Document", filesOk, "$nameCol LIKE ? OR $nameCol LIKE ? OR $nameCol LIKE ?", arrayOf("%.docx", "%.xlsx", "%.pptx"))
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
        window.statusBarColor = android.graphics.Color.rgb(10, 15, 26)
        window.navigationBarColor = android.graphics.Color.rgb(15, 22, 36)
        window.decorView.systemUiVisibility = 0
        setContent { MetaVaultApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaVaultApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by remember { mutableStateOf(Page.HOME) }
    var selectedTheme by remember { mutableStateOf(context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).getString("theme", "Aurora") ?: "Aurora") }
    var customPhotoUri by remember { mutableStateOf(context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).getString("background_photo", null)) }
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
    val counts = remember(deviceMedia) { deviceMedia.groupingBy { it.category }.eachCount() }
    val kind = if (selectedUri != null) MetadataEngine.kindOf(fileName, mime) else MetadataEngine.Kind.UNSUPPORTED
    val saveName = sanitizeName(outBase) + "." + outExt

    fun say(message: String) {
        status = message
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
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
    LaunchedEffect(reloadTick) {
        libraryLoading = true
        val (list, saved) = withContext(Dispatchers.IO) { loadDeviceMedia(context) to loadBackups(context) }
        deviceMedia = list
        backups = saved
        libraryStatus = if (list.isEmpty()) "No accessible files found. Allow media access, and all-files access for PDF and Office files."
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
                    say("Saved as a new file. Original file is untouched.")
                    history = listOf(HistoryEntry(fileName, lastAction)) + history
                    readyBytes = null
                }.onFailure { say("Save failed: ${it.message}") }
            }
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
                    say("Done. The original was replaced. A backup is kept; restore it from History.")
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
            val backupFailure = try {
                withContext(Dispatchers.IO) { backupOriginal(context, originalUri(context, uri), fileName) }
                null
            } catch (e: Exception) { e.message ?: "unknown error" }
            if (backupFailure != null) {
                say("Could not create a backup, so nothing was overwritten: $backupFailure")
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
                    say("Saved as a new image in Pictures/MetaVault. Original file is untouched.")
                    history = listOf(HistoryEntry(saveName, "$lastAction (new image)")) + history
                    readyBytes = null
                    reloadTick++
                }.onFailure { say("Save failed: ${it.message}") }
                busy = false
            }
        } else saver.launch(saveName)
    }

    fun restoreBackup(entry: BackupEntry) {
        if (busy) return
        busy = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { entry.file.readBytes() }.getOrNull() }
            if (bytes == null) { busy = false; say("The backup file could not be read."); return@launch }
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
            background = Bg, surface = Surface1, surfaceVariant = Surface2,
            primary = Accent, onPrimary = Color.White,
            secondary = AccentSoft, onSecondary = PaperInk,
            secondaryContainer = AccentTint, onSecondaryContainer = TextMain,
            tertiary = Clean, onTertiary = Color(0xFF04210F),
            onBackground = TextMain, onSurface = TextMain, onSurfaceVariant = Muted,
            outline = LineStrong, outlineVariant = Line
        ),
        typography = VaultTypography
    ) {
        if (confirmOverwrite) {
            AlertDialog(
                onDismissRequest = { confirmOverwrite = false },
                containerColor = Surface2,
                title = { Text("Overwrite original?") },
                text = { Text("The original file will be replaced by the processed version. MetaVault first stores a backup inside the app, and you can restore it from History.") },
                confirmButton = { TextButton(onClick = { confirmOverwrite = false; startOverwrite() }) { Text("Overwrite", color = Warn) } },
                dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") } }
            )
        }
        Box(Modifier.fillMaxSize().background(Bg)) {
            LiquidBackdrop(theme = selectedTheme, scrollOffset = mainScrollState.value.toFloat(), photo = customPhotoBitmap)
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    StableGlassBottomBar(page = page, onPageSelected = { if (!busy) page = it })
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).statusBarsPadding()
                        .padding(horizontal = 18.dp).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Header()
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
                            Modifier.weight(1f).verticalScroll(mainScrollState).padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            AnimatedContent(targetState = page, label = "page") { current ->
                                when (current) {
                                    Page.HOME -> HomePage(onStart = { page = Page.BROWSE })
                                    Page.BROWSE -> BrowsePage(counts = counts, onCategory = { page = it }, onPick = { picker.launch(arrayOf("*/*")) })
                                    Page.CLEANER -> CleanerPage(
                                        fileName = fileName, mime = mime, hasFile = selectedUri != null,
                                        isImage = kind == MetadataEngine.Kind.IMAGE,
                                        convert = convert, onConvert = { convert = it }, output = output, onOutput = { output = it },
                                        busy = busy, status = status, ready = readyBytes != null,
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
                                                locationMayBeHidden = Build.VERSION.SDK_INT >= 29 &&
                                                    context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED,
                                                edits = editValues, busy = busy,
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
                                                busy = busy, onSaveNew = { saveAsNew() }, onOverwrite = { confirmOverwrite = true }
                                            )
                                        }
                                    )
                                    Page.HISTORY -> HistoryPage(history, backups, busy = busy, onRestore = { restoreBackup(it) }, onDeleteBackup = { deleteBackup(it) })
                                    Page.SETTINGS -> SettingsPage(
                                        allFiles = allFiles, selectedTheme = selectedTheme, hasCustomPhoto = customPhotoUri != null,
                                        onThemeSelected = { selectedTheme = it; context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).edit().putString("theme", it).apply() },
                                        onCustomPhoto = { backgroundPicker.launch(arrayOf("image/*")) },
                                        onClearPhoto = { customPhotoUri = null; context.getSharedPreferences("metavault_ui", Context.MODE_PRIVATE).edit().remove("background_photo").apply() },
                                        onAbout = { page = Page.ABOUT }, onPermissions = openAllFilesAccess
                                    )
                                    Page.ABOUT -> AboutPage()
                                    else -> {}
                                }
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


@Composable
private fun LiquidBackdrop(theme: String, scrollOffset: Float, photo: Bitmap?) {
    val colors = when (theme) {
        "Ocean" -> listOf(Color(0xFF087EAA), Color(0xFF16C5C0), Color(0xFF173A85))
        "Violet" -> listOf(Color(0xFF7042C8), Color(0xFFB45BDB), Color(0xFF253A91))
        "Emerald" -> listOf(Color(0xFF087B63), Color(0xFF52C878), Color(0xFF145B8C))
        "Sunset" -> listOf(Color(0xFFE06A42), Color(0xFFE5A044), Color(0xFF8D3C83))
        "Rose" -> listOf(Color(0xFFC34F83), Color(0xFFEA8DAD), Color(0xFF653C9C))
        "Midnight" -> listOf(Color(0xFF34518D), Color(0xFF526A9D), Color(0xFF15233F))
        "Monochrome" -> listOf(Color(0xFF686868), Color(0xFFA0A0A0), Color(0xFF383838))
        else -> listOf(Color(0xFF287BEA), Color(0xFF20BDB9), Color(0xFF3152B5))
    }
    Box(Modifier.fillMaxSize()) {
        if (photo != null) {
            Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // A uniform neutral scrim keeps text readable without tinting the photo or cards.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            val shift = (scrollOffset * 0.16f).coerceAtMost(size.height * 0.55f)
            val radius = size.minDimension * 0.78f
            val a = Offset(size.width * 0.84f, size.height * 0.10f - shift)
            drawCircle(Brush.radialGradient(listOf(colors[0].copy(alpha = 0.40f), colors[0].copy(alpha = 0.10f), Color.Transparent), a, radius), radius, a)
            val b = Offset(size.width * 0.08f, size.height * 0.72f + shift * 0.72f)
            drawCircle(Brush.radialGradient(listOf(colors[1].copy(alpha = 0.24f), colors[1].copy(alpha = 0.07f), Color.Transparent), b, radius * 0.82f), radius * 0.82f, b)
            val c = Offset(size.width * 0.90f, size.height * 1.02f - shift * 0.42f)
            drawCircle(Brush.radialGradient(listOf(colors[2].copy(alpha = 0.22f), Color.Transparent), c, radius * 0.72f), radius * 0.72f, c)
            drawRect(Color(0x33070D18))
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
        Surface(color = Color.Transparent, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, LineStrong)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Clean, RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text("On device", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// The one memorable moment: example metadata that gets covered by redaction bars.
@Composable
private fun RedactionCard() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(600)
        progress.animateTo(1f, tween(durationMillis = 2200))
    }
    val rows = listOf(
        "Camera" to "Pixel 8 Pro",
        "Taken" to "9 Nov 2026, 18:42",
        "Location" to "28.6139 N, 77.2090 E",
        "Software" to "Snapseed 2.0",
        "Author" to "Priya Nair"
    )
    Column(Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("IMG_4821.jpg", color = PaperInk, fontFamily = MonoFont, fontSize = 13.sp)
            Text("Example data", color = PaperMuted, style = MaterialTheme.typography.bodySmall)
        }
        rows.forEachIndexed { index, (label, value) ->
            HorizontalDivider(color = Color(0xFFD2D9E3), modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = PaperMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
                Box {
                    Text(value, color = PaperInk, fontFamily = MonoFont, fontSize = 13.sp)
                    val fraction = (progress.value * rows.size - index).coerceIn(0f, 1f)
                    Box(Modifier.matchParentSize()) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(PaperInk, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Text("What MetaVault removes from your copy.", color = PaperMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
    }
}

// Home has one job: explain the idea and send people to pick a file. The tabs below do the rest.
@Composable
private fun HomePage(onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Share the photo, not where you took it.", color = TextMain, style = MaterialTheme.typography.headlineLarge)
            Text("Photos quietly record the location, time and device behind them. MetaVault shows that data, lets you edit or remove it, and saves a copy. Everything happens on your device.", color = Muted, style = MaterialTheme.typography.bodyLarge)
        }
        RedactionCard()
        PrimaryButton("Choose a file", Icons.Default.FolderOpen, onStart)
    }
}

// Browse is the single place to start picking a file: one card per category, plus the system picker.
@Composable
private fun BrowsePage(counts: Map<String, Int>, onCategory: (Page) -> Unit, onPick: () -> Unit) {
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
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { (target, icon) ->
                    val (support, supported) = supportShort(target)
                    VaultCard(Modifier.weight(1f).fillMaxHeight(), onClick = { onCategory(target) }) {
                        Icon(icon, null, tint = AccentSoft, modifier = Modifier.size(24.dp))
                        Text(target.label, color = TextMain, style = MaterialTheme.typography.titleMedium)
                        Text((counts[categoryKey(target)] ?: 0).toString() + " files", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text(support, color = if (supported) Clean else Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        VaultCard {
            Text("Somewhere else?", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text("Use Android's file picker for anything that is not listed above.", color = Muted, style = MaterialTheme.typography.bodySmall)
            SecondaryButton("Open file picker", Icons.Default.FolderOpen, onPick)
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
    page: Page, items: List<DeviceMedia>, loading: Boolean, status: String, hasAllFiles: Boolean,
    onOpen: (DeviceMedia) -> Unit, onSystemPick: () -> Unit, onRefresh: () -> Unit, onAllFiles: () -> Unit
) {
    val category = categoryKey(page)
    val list = remember(items, category) { items.filter { it.category == category } }
    val icon = when (category) { "Photo" -> Icons.Default.Image; "Video" -> Icons.Default.VideoLibrary; "Audio" -> Icons.Default.MusicNote; "PDF" -> Icons.Default.PictureAsPdf; else -> Icons.Default.Description }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { PageHeading(page.label, list.size.toString() + " files · " + supportShort(page).first) }
            TextButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Refresh") }
        }
        when {
            loading && list.isEmpty() -> VaultCard { Text("Loading…", color = Muted, style = MaterialTheme.typography.bodySmall) }
            list.isEmpty() -> VaultCard {
                Text("No files to show", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (!hasAllFiles && (category == "PDF" || category == "Document")) {
                    Text("Android only lists PDF and Office files when all-files access is allowed.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    PrimaryButton("Allow all-files access", Icons.Default.Security, onAllFiles)
                }
                SecondaryButton("Use file picker", Icons.Default.FolderOpen, onSystemPick)
            }
            category == "Photo" || category == "Video" -> LazyVerticalGrid(
                columns = GridCells.Fixed(3), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(list, key = { it.uri.toString() }) { item ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Line, RoundedCornerShape(12.dp))
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
                    VaultCard(onClick = { onOpen(item) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = AccentSoft)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = TextMain, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    edits: MutableMap<String, String>, busy: Boolean, onSaveEdits: () -> Unit
) {
    if (uri == null) return
    if (kind == MetadataEngine.Kind.IMAGE || mime.startsWith("video/")) {
        Thumb(uri, 720, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)), Icons.Default.Image)
    }
    VaultCard {
        Text("Metadata in this file", color = TextMain, style = MaterialTheme.typography.titleMedium)
        when {
            inspecting -> Text("Reading metadata…", color = Muted, style = MaterialTheme.typography.bodySmall)
            inspectError != null -> Text(inspectError, color = Muted, style = MaterialTheme.typography.bodySmall)
            kind == MetadataEngine.Kind.UNSUPPORTED -> Text("Viewing metadata is not supported for this format yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            fields == null -> Text("No metadata loaded.", color = Muted, style = MaterialTheme.typography.bodySmall)
            fields.isEmpty() -> Text("No metadata found in the fields MetaVault can read.", color = Muted, style = MaterialTheme.typography.bodySmall)
            else -> fields.forEachIndexed { index, f ->
                if (index > 0) HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(f.key, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
                    Text(f.value, color = TextMain, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.weight(0.6f))
                }
            }
        }
        if (kind == MetadataEngine.Kind.IMAGE && locationMayBeHidden) {
            Text("Android may hide GPS location here until media-location access is allowed for MetaVault.", color = Warn, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (kind == MetadataEngine.Kind.IMAGE && fields != null && inspectError == null) {
        VaultCard {
            Text("Edit image metadata", color = TextMain, style = MaterialTheme.typography.titleMedium)
            MetadataEngine.EDITABLE_IMAGE_TAGS.keys.forEach { label ->
                OutlinedTextField(
                    value = edits[label] ?: "", onValueChange = { edits[label] = it },
                    label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Text("Date format: 2026:11:11 14:30:00. An empty field removes that tag.", color = Muted, style = MaterialTheme.typography.bodySmall)
            SecondaryButton(if (busy) "Working…" else "Apply edits to a copy", Icons.Default.Edit) { if (!busy) onSaveEdits() }
        }
    }
}

@Composable
private fun CleanerPage(
    fileName: String, mime: String, hasFile: Boolean, isImage: Boolean,
    convert: Boolean, onConvert: (Boolean) -> Unit, output: String, onOutput: (String) -> Unit,
    busy: Boolean, status: String, ready: Boolean,
    onChangeFile: () -> Unit, onClean: () -> Unit,
    extra: @Composable () -> Unit,
    result: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Cleaner", "Review what is inside a file, then save a clean copy.")
        if (!hasFile) {
            VaultCard {
                Icon(Icons.Default.AutoAwesome, null, tint = AccentSoft, modifier = Modifier.size(30.dp))
                Text("No file selected", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text("Pick a photo, MP3 or Office file from Browse to get started.", color = Muted, style = MaterialTheme.typography.bodySmall)
                PrimaryButton("Choose a file", Icons.Default.FolderOpen, onChangeFile)
            }
            return@Column
        }
        VaultCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, null, tint = AccentSoft, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fileName, color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(mime, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onChangeFile, enabled = !busy) { Text("Change") }
            }
        }
        extra()
        if (isImage) {
            VaultCard {
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
        Button(
            onClick = onClean, enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent, contentColor = Color.White,
                disabledContainerColor = Accent.copy(alpha = 0.45f), disabledContentColor = Color.White.copy(alpha = 0.75f)
            )
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null)
            Spacer(Modifier.width(9.dp))
            Text(if (busy) "Working…" else "Remove metadata", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        AnimatedVisibility(ready) { result() }
        Text(status, color = if (ready) Clean else Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultCard(
    outBase: String, onOutBase: (String) -> Unit, outExt: String, canOverwrite: Boolean,
    busy: Boolean, onSaveNew: () -> Unit, onOverwrite: () -> Unit
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
        OutlinedTextField(
            value = outBase, onValueChange = onOutBase, label = { Text("File name") },
            suffix = { Text("." + outExt) }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        PrimaryButton(if (busy) "Working…" else "Save as new file", Icons.Default.Download, { if (!busy) onSaveNew() }, container = Clean, content = Color(0xFF04210F))
        OutlinedButton(
            onClick = onOverwrite, enabled = canOverwrite && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (canOverwrite) Warn else LineStrong),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Warn, disabledContentColor = Muted)
        ) {
            Icon(Icons.Default.Warning, null)
            Spacer(Modifier.width(9.dp))
            Text("Overwrite original", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Text(
            if (canOverwrite) "Save as new keeps your original untouched (images go to Pictures/MetaVault). Overwrite replaces it after storing a backup you can restore from History."
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
            VaultCard {
                Icon(Icons.Default.History, null, tint = AccentSoft, modifier = Modifier.size(32.dp))
                Text("Nothing here yet", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text("Saved copies will appear here. The export list is kept in memory for this session only.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        history.forEach {
            VaultCard {
                Text(it.name, color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(it.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (backups.isNotEmpty()) {
            Text("Backups", color = TextMain, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
            backups.forEach { b ->
                VaultCard {
                    Text(b.name, color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dateFormat.format(Date(b.time)), color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onRestore(b) }, enabled = !busy, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, LineStrong)) { Text("Restore") }
                        OutlinedButton(onClick = { onDeleteBackup(b) }, enabled = !busy, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, LineStrong)) { Text("Delete") }
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
    onThemeSelected: (String) -> Unit, onCustomPhoto: () -> Unit, onClearPhoto: () -> Unit,
    onAbout: () -> Unit, onPermissions: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Settings", "Personalise your MetaVault experience.")
        VaultCard {
            Text("Appearance & themes", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text("Choose a colour atmosphere for your app.", color = Muted, style = MaterialTheme.typography.bodySmall)
            val themes = listOf("Aurora", "Ocean", "Violet", "Emerald", "Sunset", "Rose", "Midnight", "Monochrome")
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
                TextButton(onClick = onClearPhoto) { Text("Remove custom photo") }
            }
        }
        VaultCard {
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
        VaultCard {
            BrandMark(48.dp)
            Text("MetaVault", color = TextMain, style = MaterialTheme.typography.headlineMedium)
            Text("Files can carry hidden details such as capture time, device, location and author names. MetaVault shows what is inside, lets you edit image fields, and saves a cleaner copy. All processing happens on your device and nothing is uploaded.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        VaultCard {
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
private fun StableGlassBottomBar(page: Page, onPageSelected: (Page) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val activeTab = when (page) {
        Page.PHOTOS, Page.VIDEOS, Page.AUDIO, Page.PDFS, Page.DOCS -> Page.BROWSE
        Page.ABOUT -> Page.SETTINGS
        else -> page
    }
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(
                Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.14f)
            )))
            .border(1.dp, Brush.linearGradient(listOf(
                Color.White.copy(alpha = 0.48f), Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.30f)
            )), shape)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 4.dp, vertical = 3.dp)) {
            val tabs = listOf(Page.HOME, Page.BROWSE, Page.CLEANER, Page.HISTORY, Page.SETTINGS)
            val activeIndex = tabs.indexOf(activeTab).coerceAtLeast(0)
            val indicatorWidth = maxWidth / tabs.size
            val indicatorOffset by animateDpAsState(indicatorWidth * activeIndex,
                animationSpec = tween(420, easing = FastOutSlowInEasing), label = "bottom-nav-slide")
            Box(Modifier.offset { IntOffset(indicatorOffset.roundToPx(), 0) }.width(indicatorWidth).fillMaxHeight()
                .padding(horizontal = 2.dp, vertical = 1.dp).clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.16f))
            )
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { item ->
                val icon = when (item) {
                    Page.HOME -> Icons.Default.Home
                    Page.BROWSE -> Icons.Default.FolderOpen
                    Page.CLEANER -> Icons.Default.AutoAwesome
                    Page.HISTORY -> Icons.Default.History
                    else -> Icons.Default.Settings
                }
                val selected = activeTab == item
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                    .clickable { onPageSelected(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp))
                        .padding(horizontal = 13.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = item.label,
                            tint = if (selected) Color.White else Muted, modifier = Modifier.size(19.dp))
                    }
                    Text(item.label, color = if (selected) AccentSoft else Muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter)
            .background(Brush.horizontalGradient(listOf(Color.Transparent,
                Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.18f), Color.Transparent))))
        }
    }
}

@Composable
private fun VaultCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    // No elevation layer behind translucent glass. Clip the interaction separately
    // from caller layout modifiers so both the content and ripple stay rounded.
    Box(modifier.fillMaxWidth().clip(shape)
        .background(Brush.linearGradient(listOf(
            Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.035f), Color.White.copy(alpha = 0.07f)
        )))
        .border(1.dp, Brush.linearGradient(listOf(
            Color.White.copy(alpha = 0.46f), Color.White.copy(alpha = 0.20f),
            Color.White.copy(alpha = 0.28f)
        )), shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter)
            .background(Brush.horizontalGradient(listOf(Color.Transparent,
                Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.16f), Color.Transparent))))
    }
}

@Composable
private fun PrimaryButton(
    label: String, icon: ImageVector, onClick: () -> Unit,
    container: Color = Accent, content: Color = Color.White
) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content, disabledContentColor = content)
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(9.dp))
        Text(label, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun SecondaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, LineStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(9.dp))
        Text(label, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
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
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                uncheckedThumbColor = Color(0xFFDCE4F8), uncheckedTrackColor = Color(0xFF3A4560),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
