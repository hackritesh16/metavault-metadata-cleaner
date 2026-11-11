package com.metavault.cleaner

import android.Manifest
import android.content.pm.PackageManager
import android.media.ExifInterface
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// Palette shared with the web app (see style.css).
private val Bg = Color(0xFF0A0F1A)
private val Surface1 = Color(0xFF0F1624)
private val Surface2 = Color(0xFF141D2F)
private val Line = Color(0xFF1F2A40)
private val LineStrong = Color(0xFF33425F)
private val TextMain = Color(0xFFEEF2F8)
private val Muted = Color(0xFF97A3BA)
private val Accent = Color(0xFF3A63E8)
private val AccentSoft = Color(0xFF8EABFF)
private val AccentTint = Color(0x333A63E8)
private val Clean = Color(0xFF3DDC97)
private val Warn = Color(0xFFFFB86B)
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

private data class HistoryEntry(val name: String, val detail: String)
private data class DeviceMedia(val uri: Uri, val name: String, val mime: String, val category: String)

private data class Finding(val label: String, val value: String, val sensitive: Boolean = false)
private data class Inspection(val findings: List<Finding>, val note: String)

// Matches dotted IPv4 addresses and ignores longer version-like numbers.
private val IPV4_REGEX = Regex("""(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?!\d|\.\d)""")
private val TOKEN_SPLIT = Regex("""[\s,;"'<>()\[\]{}=]+""")

// Own IPv6 check on purpose: InetAddress.getByName would fall back to a DNS lookup for non-numeric text.
private fun isIPv6(s: String): Boolean {
    if (s.count { it == ':' } < 2 || s.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':') }) return false
    val dbl = s.indexOf("::")
    if (dbl != s.lastIndexOf("::")) return false
    fun groupsOk(part: String) = part.isEmpty() || part.split(":").all { it.length in 1..4 }
    return if (dbl >= 0) {
        val head = s.substring(0, dbl)
        val tail = s.substring(dbl + 2)
        val h = if (head.isEmpty()) 0 else head.split(":").size
        val t = if (tail.isEmpty()) 0 else tail.split(":").size
        groupsOk(head) && groupsOk(tail) && h + t <= 7
    } else {
        val g = s.split(":")
        g.size == 8 && g.all { it.length in 1..4 }
    }
}

private fun hasMediaLocation(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 29 ||
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun openForMetadata(context: android.content.Context, uri: Uri): java.io.InputStream? {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY && hasMediaLocation(context)) {
        runCatching { resolver.openInputStream(MediaStore.setRequireOriginal(uri)) }.getOrNull()?.let { return it }
    }
    return runCatching { resolver.openInputStream(uri) }.getOrNull()
}

// Reads EXIF on this device only. Nothing is sent anywhere.
private fun inspectImage(context: android.content.Context, uri: Uri, mime: String): Inspection {
    if (!mime.startsWith("image/")) return Inspection(emptyList(), "MetaVault can read metadata from images for now.")
    val exif = runCatching { openForMetadata(context, uri)?.use { ExifInterface(it) } }.getOrNull()
        ?: return Inspection(emptyList(), "Could not read metadata from this file.")
    fun attr(tag: String): String {
        val raw = exif.getAttribute(tag)?.replace("\u0000", "")?.trim().orEmpty()
        return if (tag == ExifInterface.TAG_USER_COMMENT) raw.removePrefix("ASCII").removePrefix("UNICODE").trim() else raw
    }
    val rows = mutableListOf<Finding>()
    val latLong = FloatArray(2)
    if (exif.getLatLong(latLong)) {
        val lat = latLong[0]
        val lon = latLong[1]
        rows += Finding(
            "Location",
            String.format(java.util.Locale.US, "%.6f\u00b0 %s, %.6f\u00b0 %s", Math.abs(lat), if (lat >= 0) "N" else "S", Math.abs(lon), if (lon >= 0) "E" else "W"),
            true
        )
    }
    val ips = linkedMapOf<String, String>()
    listOf(
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, "CameraOwnerName"
    ).forEach { tag ->
        val text = attr(tag)
        if (text.isNotEmpty()) {
            IPV4_REGEX.findAll(text).forEach { ips.putIfAbsent(it.value, tag) }
            text.split(TOKEN_SPLIT).forEach { raw ->
                val token = raw.trimEnd('.', ',', ';')
                if (isIPv6(token)) ips.putIfAbsent(token, tag)
            }
        }
    }
    if (ips.isNotEmpty()) rows += Finding("Possible IP address", ips.entries.joinToString("\n") { it.key + "  (in " + it.value + ")" }, true)
    val author = attr(ExifInterface.TAG_ARTIST).ifEmpty { attr("CameraOwnerName") }
    if (author.isNotEmpty()) rows += Finding("Author", author, true)
    val make = attr(ExifInterface.TAG_MAKE)
    val model = attr(ExifInterface.TAG_MODEL)
    val camera = if (model.startsWith(make, ignoreCase = true)) model else listOf(make, model).filter { it.isNotEmpty() }.joinToString(" ")
    if (camera.isNotEmpty()) rows += Finding("Camera", camera)
    val taken = attr(ExifInterface.TAG_DATETIME_ORIGINAL).ifEmpty { attr(ExifInterface.TAG_DATETIME) }
        .replace(Regex("^(\\d{4}):(\\d{2}):(\\d{2})"), "$1-$2-$3")
    if (taken.isNotEmpty()) rows += Finding("Date taken", taken)
    val software = attr(ExifInterface.TAG_SOFTWARE)
    if (software.isNotEmpty()) rows += Finding("Software", software)
    val copyright = attr(ExifInterface.TAG_COPYRIGHT)
    if (copyright.isNotEmpty()) rows += Finding("Copyright", copyright)
    val comment = attr(ExifInterface.TAG_IMAGE_DESCRIPTION).ifEmpty { attr(ExifInterface.TAG_USER_COMMENT) }
    if (comment.isNotEmpty()) rows += Finding("Comment", comment)
    val locationMayBeHidden = Build.VERSION.SDK_INT >= 29 && !hasMediaLocation(context) && rows.none { it.label == "Location" }
    val note = buildString {
        append(
            if (rows.isEmpty()) "No location, camera, author or IP data found in what MetaVault can read."
            else "All of this is removed from your clean copy. Photos don\u2019t normally store an IP address, so MetaVault only flags text that looks like one."
        )
        if (locationMayBeHidden) append(" Android hides photo location unless you allow photo location access, so any location may be missing here.")
    }
    return Inspection(rows, note)
}

private fun loadDeviceMedia(context: android.content.Context): List<DeviceMedia> {
    val resolver = context.contentResolver
    val results = mutableListOf<DeviceMedia>()
    fun read(collection: Uri, category: String, permission: String) {
        val hasPermission = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && (category == "Photo" || category == "Video") &&
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
        if (Build.VERSION.SDK_INT >= 23 && !hasPermission) return
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE)
        runCatching {
            resolver.query(collection, projection, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext() && results.size < 500) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Unnamed file"
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "application/octet-stream" else "application/octet-stream"
                    results.add(DeviceMedia(android.content.ContentUris.withAppendedId(collection, id), name, mime, category))
                }
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 33) {
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photo", Manifest.permission.READ_MEDIA_IMAGES)
        read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Video", Manifest.permission.READ_MEDIA_VIDEO)
        read(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photo", Manifest.permission.READ_EXTERNAL_STORAGE)
        read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Video", Manifest.permission.READ_EXTERNAL_STORAGE)
        read(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return results
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
    var saveName by remember { mutableStateOf("metavault-clean.jpg") }
    var history by remember { mutableStateOf(listOf<HistoryEntry>()) }
    var deviceMedia by remember { mutableStateOf(emptyList<DeviceMedia>()) }
    var libraryStatus by remember { mutableStateOf("Requesting media access…") }
    var inspection by remember { mutableStateOf<Inspection?>(null) }
    var mediaLocationGranted by remember { mutableStateOf(hasMediaLocation(context)) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> mediaLocationGranted = granted }
    val needsLocationAccess = Build.VERSION.SDK_INT >= 29 && !mediaLocationGranted && inspection?.findings?.none { it.label == "Location" } == true
    LaunchedEffect(selectedUri, mime, mediaLocationGranted) {
        inspection = null
        val uri = selectedUri
        if (uri != null) inspection = withContext(Dispatchers.IO) { inspectImage(context, uri, mime) }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            deviceMedia = loadDeviceMedia(context)
            libraryStatus = "Loaded " + deviceMedia.size + " media files from accessible device storage."
        } else {
            libraryStatus = "Media permission was denied. Use Browse files to select items manually."
        }
    }
    LaunchedEffect(Unit) {
        val requested = if (Build.VERSION.SDK_INT >= 34) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) else if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = requested.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) mediaPermissionLauncher.launch(missing.toTypedArray())
        else {
            deviceMedia = loadDeviceMedia(context)
            libraryStatus = "Loaded " + deviceMedia.size + " media files from accessible device storage."
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
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
                    status = "Saved successfully. Original file is untouched."
                    history = listOf(HistoryEntry(fileName, "Clean copy saved")) + history
                    readyBytes = null
                }.onFailure { status = "Save failed: ${it.message}" }
            }
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
        Box(Modifier.fillMaxSize().background(Bg)) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    Column {
                        HorizontalDivider(color = Line)
                        NavigationBar(containerColor = Surface1, tonalElevation = 0.dp) {
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
                                    label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentSoft, selectedTextColor = AccentSoft,
                                        indicatorColor = AccentTint, unselectedIconColor = Muted,
                                        unselectedTextColor = Muted
                                    )
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).statusBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
                        .padding(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Header(onAbout = { page = Page.ABOUT })
                    AnimatedContent(targetState = page, label = "page") { current ->
                        when (current) {
                            Page.HOME -> HomePage(
                                onBrowse = { page = Page.BROWSE }, onClean = { page = Page.CLEANER },
                                onCategory = { page = it }, onHistory = { page = Page.HISTORY }
                            )
                            Page.BROWSE -> BrowsePage(
                                onCategory = { page = it }, onPick = { picker.launch(arrayOf("*/*")) },
                                media = deviceMedia, libraryStatus = libraryStatus,
                                onOpenMedia = { item ->
                                    selectedUri = item.uri; fileName = item.name; mime = item.mime
                                    readyBytes = null; status = "File selected from device library."; page = Page.CLEANER
                                }
                            )
                            Page.PHOTOS -> CategoryPage(Page.PHOTOS, "JPG, PNG and WEBP", "Inspect image metadata and create a cleaned image copy.", "Image metadata cleaning is available for JPG, PNG and WEBP.", onPick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, onCleaner = { page = Page.CLEANER })
                            Page.VIDEOS -> CategoryPage(Page.VIDEOS, "MP4, MOV and other video", "Browse video files and review the support status.", "Video metadata cleaning is not implemented yet. Files are never modified.", onPick = { picker.launch(arrayOf("video/*")) }, onCleaner = { page = Page.CLEANER })
                            Page.AUDIO -> CategoryPage(Page.AUDIO, "MP3, M4A, FLAC and other audio", "Browse music and audio files on your device.", "Audio metadata cleaning is not implemented yet. Files are never modified.", onPick = { picker.launch(arrayOf("audio/*")) }, onCleaner = { page = Page.CLEANER })
                            Page.PDFS -> CategoryPage(Page.PDFS, "PDF documents", "Choose PDFs to review future metadata-cleaning support.", "PDF metadata cleaning is not implemented yet. No PDF will be altered.", onPick = { picker.launch(arrayOf("application/pdf")) }, onCleaner = { page = Page.CLEANER })
                            Page.DOCS -> CategoryPage(Page.DOCS, "DOCX, XLSX, PPTX and more", "Browse documents using Android's secure file picker.", "Office document cleaning is planned, not yet available.", onPick = { picker.launch(arrayOf("application/*", "text/*")) }, onCleaner = { page = Page.CLEANER })
                            Page.CLEANER -> CleanerPage(
                                fileName, mime, selectedUri != null, preserve, { preserve = it },
                                removeMetadata, { removeMetadata = it }, output, { output = it },
                                busy, status, readyBytes != null, saveName,
                                inspection = inspection, showAllowLocation = needsLocationAccess,
                                onAllowLocation = { locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
                                onPick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "video/*", "audio/*", "application/pdf")) },
                                onClean = {
                                    val uri = selectedUri
                                    if (uri == null) { status = "Choose a file first." }
                                    else if (!removeMetadata) { status = "Enable metadata removal to continue." }
                                    else if (!mime.startsWith("image/")) { status = "This format is not supported for cleaning yet. Your file is unchanged." }
                                    else {
                                        busy = true; status = "Cleaning image locally…"; readyBytes = null
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("Could not read this image")
                                                    val targetMime = if (preserve) mime else output
                                                    val format = when (targetMime) {
                                                        "image/png" -> Bitmap.CompressFormat.PNG
                                                        "image/webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                                                        else -> Bitmap.CompressFormat.JPEG
                                                    }
                                                    val stream = ByteArrayOutputStream()
                                                    val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 96
                                                    check(bitmap.compress(format, quality, stream)) { "Image export failed" }
                                                    bitmap.recycle()
                                                    stream.toByteArray() to targetMime
                                                }
                                            }.onSuccess { (bytes, targetMime) ->
                                                readyBytes = bytes
                                                val ext = when (targetMime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                                                saveName = fileName.substringBeforeLast('.', fileName) + ".clean." + ext
                                                status = "Cleaned copy prepared. Original file is untouched."
                                            }.onFailure { status = "Could not clean file: ${it.message}" }
                                            busy = false
                                        }
                                    }
                                },
                                onSave = { saver.launch(saveName) }
                            )
                            Page.HISTORY -> HistoryPage(history)
                            Page.SETTINGS -> SettingsPage(onAbout = { page = Page.ABOUT }, onPermissions = {
                                if (Build.VERSION.SDK_INT >= 30) {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = AndroidUri.parse("package:${context.packageName}") }
                                    runCatching { context.startActivity(intent) }.onFailure { status = "Could not open Android settings." }
                                } else status = "Use Android's file picker to choose files."
                            }, status = status)
                            Page.ABOUT -> AboutPage()
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
private fun Header(onAbout: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(32.dp)
            Spacer(Modifier.width(10.dp))
            Text("MetaVault", color = TextMain, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.4).sp)
        }
        Surface(color = Color.Transparent, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, LineStrong), modifier = Modifier.clickable { onAbout() }) {
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

@Composable
private fun HomePage(onBrowse: () -> Unit, onClean: () -> Unit, onCategory: (Page) -> Unit, onHistory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Share the photo, not where you took it.", color = TextMain, style = MaterialTheme.typography.headlineLarge)
            Text("Photos quietly record the location, time and device behind them. MetaVault saves a clean copy without that data, and your original stays untouched.", color = Muted, style = MaterialTheme.typography.bodyLarge)
        }
        RedactionCard()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Browse files", Icons.Default.FolderOpen, onBrowse)
            SecondaryButton("Open cleaner", Icons.Default.AutoAwesome, onClean)
        }
        Text("File types", color = TextMain, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
        CategoryGrid(onCategory)
        VaultCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, tint = AccentSoft, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Processed on your device", color = TextMain, style = MaterialTheme.typography.titleMedium)
                    Text("Pick files with Android's secure file picker or browse the media you allow. Nothing is uploaded.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable { onHistory() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("View cleaning history", color = TextMain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = AccentSoft)
        }
    }
}

@Composable
private fun BrowsePage(onCategory: (Page) -> Unit, onPick: () -> Unit, media: List<DeviceMedia>, libraryStatus: String, onOpenMedia: (DeviceMedia) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("File library", "Browse by category and choose files securely.")
        VaultCard {
            Text("Browse files", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text("Use Android's system picker to open the files you choose. MetaVault does not silently scan your storage.", color = Muted, style = MaterialTheme.typography.bodySmall)
            PrimaryButton("Browse all files", Icons.Default.FolderOpen, onPick)
        }
        CategoryGrid(onCategory)
        VaultCard {
            Text("Access and privacy", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text("Only files you select are opened. Full-storage access is optional and is not required for the system picker.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        VaultCard {
            Text("On-device library", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text(libraryStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
            val grouped = media.groupBy { it.category }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Photo", "Video", "Audio").forEach { category ->
                    Column(Modifier.weight(1f)) {
                        Text((grouped[category]?.size ?: 0).toString(), color = TextMain, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text(category, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (media.isEmpty()) {
                Text("No accessible media found yet. Check permission or use Browse all files.", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                media.take(100).forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenMedia(item) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (item.category) { "Photo" -> Icons.Default.Image; "Video" -> Icons.Default.VideoLibrary; else -> Icons.Default.MusicNote }, null, tint = AccentSoft)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = TextMain, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.category + " · " + item.mime, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
    }
}

@Composable
private fun CategoryGrid(onCategory: (Page) -> Unit) {
    val items = listOf(
        Pair(Page.PHOTOS, Icons.Default.Image),
        Pair(Page.VIDEOS, Icons.Default.VideoLibrary),
        Pair(Page.AUDIO, Icons.Default.MusicNote),
        Pair(Page.PDFS, Icons.Default.PictureAsPdf),
        Pair(Page.DOCS, Icons.Default.Description)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { (target, icon) ->
                    VaultCard(Modifier.weight(1f).clickable { onCategory(target) }) {
                        Icon(icon, null, tint = AccentSoft, modifier = Modifier.size(24.dp))
                        Text(target.label, color = TextMain, style = MaterialTheme.typography.titleMedium)
                        Text(if (target == Page.PHOTOS) "Cleaning available" else "Cleaning coming soon", color = if (target == Page.PHOTOS) Clean else Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CategoryPage(page: Page, formats: String, description: String, support: String, onPick: () -> Unit, onCleaner: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading(page.label, description)
        VaultCard {
            Text(formats, color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text(support, color = Muted, style = MaterialTheme.typography.bodySmall)
            PrimaryButton("Choose files", Icons.Default.FolderOpen, onPick)
        }
        VaultCard {
            Text("How it works", color = TextMain, style = MaterialTheme.typography.titleMedium)
            InfoLine("1", "Choose files", "Select only the files you want to work with.")
            InfoLine("2", "Review support", "MetaVault tells you what it can process.")
            InfoLine("3", "Save safely", "The original is kept untouched.")
        }
        SecondaryButton("Open cleaner", Icons.Default.AutoAwesome, onCleaner)
    }
}

@Composable
private fun CleanerPage(
    fileName: String, mime: String, hasFile: Boolean, preserve: Boolean, onPreserve: (Boolean) -> Unit,
    remove: Boolean, onRemove: (Boolean) -> Unit, output: String, onOutput: (String) -> Unit,
    busy: Boolean, status: String, ready: Boolean, saveName: String,
    inspection: Inspection?, showAllowLocation: Boolean, onAllowLocation: () -> Unit,
    onPick: () -> Unit, onClean: () -> Unit, onSave: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Cleaner", "Choose a file, pick your options, then save a clean copy.")
        VaultCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Selected file", color = TextMain, style = MaterialTheme.typography.titleMedium)
                if (hasFile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = Clean, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ready", color = Clean, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Text("None yet", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, null, tint = AccentSoft, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (hasFile) fileName else "No file selected", color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (hasFile) mime else "Choose a file to get started", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            PrimaryButton("Choose file", Icons.Default.FolderOpen, onPick)
        }
        FindingsCard(hasFile, inspection, showAllowLocation, onAllowLocation)
        VaultCard {
            Text("What to remove", color = TextMain, style = MaterialTheme.typography.titleMedium)
            SettingRow("Remove image metadata", "Supported image metadata is stripped", remove, onRemove)
            HorizontalDivider(color = Line)
            SettingRow("Keep original format", "Best effort; image is re-encoded", preserve, onPreserve)
            if (!preserve) {
                Text("Output format", color = Muted, style = MaterialTheme.typography.bodySmall)
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
        Button(
            onClick = onClean, enabled = hasFile && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent, contentColor = Color.White,
                disabledContainerColor = Accent.copy(alpha = 0.45f), disabledContentColor = Color.White.copy(alpha = 0.75f)
            )
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null)
            Spacer(Modifier.width(9.dp))
            Text(if (busy) "Cleaning…" else "Clean file", fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        AnimatedVisibility(ready) {
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
                        Text("Clean copy ready", color = TextMain, style = MaterialTheme.typography.titleMedium)
                        Text(saveName, color = Muted, fontFamily = MonoFont, fontSize = 12.sp)
                    }
                }
                PrimaryButton("Save clean copy", Icons.Default.Download, onSave, container = Clean, content = Color(0xFF04210F))
                Text("Dimensions are retained. Re-encoding may change pixels or file size; this is not byte-for-byte lossless.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        VaultCard { Text(status, color = if (ready) Clean else Muted, style = MaterialTheme.typography.bodySmall) }
        VaultCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = AccentSoft, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Supported now: JPG, PNG and WEBP. Video, audio and PDF cleaning are not available yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FindingsCard(hasFile: Boolean, inspection: Inspection?, showAllowLocation: Boolean, onAllowLocation: () -> Unit) {
    if (!hasFile) return
    VaultCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Found in this file", color = TextMain, style = MaterialTheme.typography.titleMedium)
            if (inspection != null) {
                val sensitive = inspection.findings.count { it.sensitive }
                Text(
                    when {
                        sensitive > 0 -> "$sensitive sensitive " + (if (sensitive == 1) "item" else "items")
                        inspection.findings.isEmpty() -> "Nothing found"
                        else -> "No location or author"
                    },
                    color = if (sensitive > 0) Warn else Muted, style = MaterialTheme.typography.labelMedium
                )
            }
        }
        if (inspection == null) {
            Text("Reading metadata\u2026", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            inspection.findings.forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.sensitive) {
                            Icon(Icons.Default.Warning, "Sensitive", tint = Warn, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(f.label, color = if (f.sensitive) Warn else Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(f.value, color = if (f.sensitive) Warn else TextMain, fontFamily = MonoFont, fontSize = 13.sp)
                }
                HorizontalDivider(color = Line)
            }
            Text(inspection.note, color = Muted, style = MaterialTheme.typography.bodySmall)
            if (showAllowLocation) SecondaryButton("Allow photo location access", Icons.Default.LocationOn, onAllowLocation)
        }
    }
}

@Composable
private fun HistoryPage(history: List<HistoryEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("History", "A local record of files you have exported in this session.")
        if (history.isEmpty()) {
            VaultCard {
                Icon(Icons.Default.History, null, tint = AccentSoft, modifier = Modifier.size(32.dp))
                Text("Nothing here yet", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text("Your saved clean copies will appear here. History is kept in memory for this session only.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        } else history.forEach {
            VaultCard {
                Text(it.name, color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(it.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsPage(onAbout: () -> Unit, onPermissions: () -> Unit, status: String) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Settings", "Manage access and learn how MetaVault handles your files.")
        VaultCard {
            Text("Privacy and access", color = TextMain, style = MaterialTheme.typography.titleMedium)
            SettingsAction("Optional file access", "Open Android's app-specific access settings", Icons.Default.FolderOpen, onPermissions)
            HorizontalDivider(color = Line)
            SettingsAction("Privacy and about", "How local processing works", Icons.Default.Shield, onAbout)
        }
        VaultCard {
            Text("Current session", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("No cloud upload or account is required for the current image-cleaning workflow.", color = Muted, style = MaterialTheme.typography.bodySmall)
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
            Text("MetaVault is a privacy-first metadata cleaner built to help you take control of the information attached to your files. Photos can contain hidden details such as capture time, device information and location tags. When supported, MetaVault re-encodes the image to create a cleaner copy before you share it. Processing happens locally on your device, and your original file is never overwritten.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        VaultCard {
            InfoBullet("Local by design", "The current cleaning flow runs on your device.")
            InfoBullet("Originals stay safe", "Cleaned images are saved as a new file.")
            InfoBullet("Honest support", "Current support: JPG, PNG and WEBP image cleaning. Video, audio, PDF and Office document cleaning are planned and are not active in this build.")
        }
        Text("Android beta. Features are being developed incrementally.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = TextMain, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VaultCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(Surface1, RoundedCornerShape(18.dp))
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content
    )
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
private fun InfoLine(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).border(1.dp, LineStrong, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
            Text(number, color = AccentSoft, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
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
