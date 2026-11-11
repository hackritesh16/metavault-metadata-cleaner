package com.metavault.cleaner

import android.Manifest
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.provider.Settings
import android.net.Uri as AndroidUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page(val label: String) {
    HOME("Home"), BROWSE("Browse"), PHOTOS("Photos"), VIDEOS("Videos"),
    AUDIO("Audio"), PDFS("PDF files"), DOCS("Documents"), CLEANER("Cleaner"),
    HISTORY("History"), SETTINGS("Settings"), ABOUT("About")
}

private data class HistoryEntry(val name: String, val detail: String)
private data class DeviceMedia(val uri: Uri, val name: String, val mime: String, val category: String)

/** Metadata shown for the selected file. [loading] is true while the engine reads the file. */
private data class Inspection(val fields: List<MetaField> = emptyList(), val loading: Boolean = false, val error: String? = null)

/** A cleaned copy waiting to be saved. The original file is never written. */
private class CleanOutput(val saveName: String, val result: CleanResult)

private val OFFICE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
)
private val CLEANER_MIMES = arrayOf("image/jpeg", "image/png", "image/webp", "audio/*") + OFFICE_MIMES

private fun displayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

/** "photo.jpg" becomes "photo.clean.jpg", so the copy never replaces the original name. */
private fun cleanName(name: String): String {
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    return if (ext.isEmpty()) "$base.clean" else "$base.clean.$ext"
}

private fun coverageText(kind: MetadataEngine.Kind): String = when (kind) {
    MetadataEngine.Kind.IMAGE -> "Removes camera, date, location, author, software and maker-note fields. Orientation is kept and pixels are not re-encoded. XMP and IPTC blocks are not covered yet."
    MetadataEngine.Kind.MP3 -> "Removes ID3 tags, including title, artist, album and cover art. Audio data is not changed. APE tags are not covered yet."
    MetadataEngine.Kind.OFFICE -> "Clears author, last-modified-by, title, subject, keywords, description, company and manager. Dates and revision history are kept. Comments, tracked-change authors and custom properties are not cleared yet."
    MetadataEngine.Kind.OTHER_MEDIA -> "View only. Cleaning is not available for this format yet, so your file is not changed."
    MetadataEngine.Kind.UNSUPPORTED -> "This file type is not supported yet. Your file is not changed."
}

/** Reports only what the engine actually did. */
private fun summaryText(result: CleanResult): String {
    val removed = if (result.removed == 0) "Nothing to remove. No supported metadata was found." else "Removed ${result.removed} item(s)."
    return if (result.remaining > 0) "$removed ${result.remaining} known field(s) still present." else removed
}

private fun tabFor(page: Page): Page = when (page) {
    Page.PHOTOS, Page.VIDEOS, Page.AUDIO, Page.PDFS, Page.DOCS -> Page.BROWSE
    Page.ABOUT -> Page.SETTINGS
    else -> page
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
        window.statusBarColor = android.graphics.Color.rgb(5, 8, 15)
        window.navigationBarColor = android.graphics.Color.rgb(11, 18, 36)
        window.decorView.systemUiVisibility = 0
        setContent { MetaVaultApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaVaultApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var page by remember { mutableStateOf(Page.HOME) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No file selected") }
    var mime by remember { mutableStateOf("application/octet-stream") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Your files stay on this device.") }
    var inspection by remember { mutableStateOf(Inspection(loading = true)) }
    var cleanOutput by remember { mutableStateOf<CleanOutput?>(null) }
    var history by remember { mutableStateOf(listOf<HistoryEntry>()) }
    var deviceMedia by remember { mutableStateOf(emptyList<DeviceMedia>()) }
    var libraryStatus by remember { mutableStateOf("Requesting media access…") }

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

    // Read the metadata of whichever file is selected. Runs off the main thread.
    LaunchedEffect(selectedUri, fileName, mime) {
        val uri = selectedUri ?: return@LaunchedEffect
        cleanOutput = null
        inspection = Inspection(loading = true)
        inspection = withContext(Dispatchers.IO) {
            runCatching { MetadataEngine.inspect(context, uri, fileName, mime) }.fold(
                onSuccess = { Inspection(fields = it) },
                onFailure = { Inspection(error = it.message ?: "Could not read this file") }
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            fileName = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
            mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            cleanOutput = null
            inspection = Inspection(loading = true)
            status = "File selected. Review it in Cleaner."
            page = Page.CLEANER
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val output = cleanOutput
        if (uri != null && output != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(output.result.bytes) }
                            ?: error("Could not open destination")
                    }
                }.onSuccess {
                    status = "Saved. Original file is untouched."
                    history = listOf(HistoryEntry(fileName, "Clean copy saved · " + summaryText(output.result))) + history
                    cleanOutput = null
                }.onFailure { status = "Save failed: ${it.message}" }
            }
        }
    }

    VaultTheme {
        Box(Modifier.fillMaxSize()) {
            AmbientBackground()
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { GlassNavBar(selected = tabFor(page), onSelect = { page = it }) }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).statusBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
                        .padding(top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Header(onAbout = { page = Page.ABOUT })
                    AnimatedContent(
                        targetState = page,
                        label = "page",
                        transitionSpec = {
                            if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                            else (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 14 }) togetherWith fadeOut(tween(140))
                        }
                    ) { current ->
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
                                    cleanOutput = null; inspection = Inspection(loading = true)
                                    status = "File selected from device library."; page = Page.CLEANER
                                }
                            )
                            Page.PHOTOS -> CategoryPage(Page.PHOTOS, "JPG, PNG and WEBP", "Inspect image metadata and create a cleaned image copy.", "Image metadata cleaning is available for JPG, PNG and WEBP. Pixels are not re-encoded.", onPick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, onCleaner = { page = Page.CLEANER })
                            Page.VIDEOS -> CategoryPage(Page.VIDEOS, "MP4, MOV and other video", "Browse video files and review the support status.", "Video metadata cleaning is not implemented yet. Files are never modified.", onPick = { picker.launch(arrayOf("video/*")) }, onCleaner = { page = Page.CLEANER })
                            Page.AUDIO -> CategoryPage(Page.AUDIO, "MP3 and other audio", "Browse music and audio files on your device.", "MP3 ID3 tags can be removed. Other audio is view-only for now. Originals are never changed.", onPick = { picker.launch(arrayOf("audio/*")) }, onCleaner = { page = Page.CLEANER })
                            Page.PDFS -> CategoryPage(Page.PDFS, "PDF documents", "Choose PDFs to review future metadata-cleaning support.", "PDF metadata cleaning is not implemented yet. No PDF will be altered.", onPick = { picker.launch(arrayOf("application/pdf")) }, onCleaner = { page = Page.CLEANER })
                            Page.DOCS -> CategoryPage(Page.DOCS, "DOCX, XLSX and PPTX", "Browse Office documents using Android's secure file picker.", "Author, company, title and similar document properties can be cleared. Originals are never changed.", onPick = { picker.launch(OFFICE_MIMES) }, onCleaner = { page = Page.CLEANER })
                            Page.CLEANER -> CleanerPage(
                                fileName = fileName,
                                mime = mime,
                                kind = if (selectedUri != null) MetadataEngine.kindOf(fileName, mime) else null,
                                inspection = inspection,
                                busy = busy,
                                status = status,
                                output = cleanOutput,
                                onPick = { picker.launch(CLEANER_MIMES) },
                                onClean = {
                                    val uri = selectedUri
                                    val kind = MetadataEngine.kindOf(fileName, mime)
                                    if (uri == null) {
                                        status = "Choose a file first."
                                    } else if (kind != MetadataEngine.Kind.IMAGE && kind != MetadataEngine.Kind.MP3 && kind != MetadataEngine.Kind.OFFICE) {
                                        status = "Cleaning is not available for this file type yet. Your file is unchanged."
                                    } else {
                                        busy = true
                                        status = "Cleaning locally…"
                                        cleanOutput = null
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) { MetadataEngine.clean(context, uri, fileName, mime) }
                                            }.onSuccess { result ->
                                                if (result == null) {
                                                    status = "Cleaning is not available for this file type yet. Your file is unchanged."
                                                } else {
                                                    cleanOutput = CleanOutput(cleanName(fileName), result)
                                                    status = "Clean copy ready. Original file is untouched."
                                                }
                                            }.onFailure { status = "Could not clean file: ${it.message}" }
                                            busy = false
                                        }
                                    }
                                },
                                onSave = { cleanOutput?.let { saver.launch(it.saveName) } }
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

/** Floating glass tab bar. The active tab gets a tinted pill. */
@Composable
private fun GlassNavBar(selected: Page, onSelect: (Page) -> Unit) {
    val items = listOf(
        Page.HOME to Icons.Default.Home,
        Page.BROWSE to Icons.Default.FolderOpen,
        Page.CLEANER to Icons.Default.AutoAwesome,
        Page.HISTORY to Icons.Default.History,
        Page.SETTINGS to Icons.Default.Settings
    )
    GlassSurface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        cornerRadius = 30.dp,
        elevation = 20.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEach { (page, icon) ->
                val active = selected == page
                val pill = RoundedCornerShape(22.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(pill)
                        .background(if (active) AccentTint else Color.Transparent, pill)
                        .clickable(role = Role.Tab, onClick = { onSelect(page) })
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(icon, contentDescription = page.label, tint = if (active) AccentSoft else Muted, modifier = Modifier.size(22.dp))
                    Text(page.label, color = if (active) AccentSoft else Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
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
            BrandMark(34.dp)
            Spacer(Modifier.width(10.dp))
            Text("MetaVault", color = TextMain, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.4).sp)
        }
        GlassSurface(
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onAbout() },
            cornerRadius = 50.dp,
            elevation = 0.dp
        ) {
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
    // Glass tray with a paper sheet inside, so the redaction bars stay high contrast.
    GlassSurface(cornerRadius = 28.dp, elevation = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Column(
                Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
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
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextMain, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
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
        SectionTitle("File types")
        CategoryGrid(onCategory)
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Default.Security)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Processed on your device", color = TextMain, style = MaterialTheme.typography.titleMedium)
                    Text("Pick files with Android's secure file picker or browse the media you allow. Nothing is uploaded.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        GlassCard(modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable { onHistory() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("View cleaning history", color = TextMain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AccentSoft)
            }
        }
    }
}

@Composable
private fun BrowsePage(onCategory: (Page) -> Unit, onPick: () -> Unit, media: List<DeviceMedia>, libraryStatus: String, onOpenMedia: (DeviceMedia) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("File library", "Browse by category and choose files securely.")
        GlassCard {
            Text("Browse files", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text("Use Android's system picker to open the files you choose. MetaVault does not silently scan your storage.", color = Muted, style = MaterialTheme.typography.bodySmall)
            PrimaryButton("Browse all files", Icons.Default.FolderOpen, onPick)
        }
        CategoryGrid(onCategory)
        GlassCard {
            Text("Access and privacy", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text("Only files you select are opened. Full-storage access is optional and is not required for the system picker.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        GlassCard {
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
                        Icon(when (item.category) { "Photo" -> Icons.Default.Image; "Video" -> Icons.Default.VideoLibrary; else -> Icons.Default.MusicNote }, contentDescription = null, tint = AccentSoft)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = TextMain, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.category + " · " + item.mime, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
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
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { (target, icon) ->
                    val label = when (target) {
                        Page.PHOTOS, Page.DOCS -> "Cleaning available"
                        Page.AUDIO -> "MP3 cleaning available"
                        else -> "Cleaning coming soon"
                    }
                    val available = target != Page.VIDEOS && target != Page.PDFS
                    GlassCard(
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).clickable { onCategory(target) }
                    ) {
                        IconBadge(icon = icon, size = 42.dp)
                        Text(target.label, color = TextMain, style = MaterialTheme.typography.titleMedium)
                        Text(label, color = if (available) Clean else Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CategoryPage(page: Page, formats: String, description: String, support: String, onPick: () -> Unit, onCleaner: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading(page.label, description)
        GlassCard {
            Text(formats, color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text(support, color = Muted, style = MaterialTheme.typography.bodySmall)
            PrimaryButton("Choose files", Icons.Default.FolderOpen, onPick)
        }
        GlassCard {
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
    fileName: String, mime: String, kind: MetadataEngine.Kind?, inspection: Inspection,
    busy: Boolean, status: String, output: CleanOutput?,
    onPick: () -> Unit, onClean: () -> Unit, onSave: () -> Unit
) {
    val cleanable = kind == MetadataEngine.Kind.IMAGE || kind == MetadataEngine.Kind.MP3 || kind == MetadataEngine.Kind.OFFICE
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Cleaner", "Pick a file, review what it contains, then save a clean copy.")
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Selected file", color = TextMain, style = MaterialTheme.typography.titleMedium)
                if (kind != null) {
                    StatusDot("Ready", Clean)
                } else {
                    Text("None yet", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Default.InsertDriveFile)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (kind != null) fileName else "No file selected", color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (kind != null) mime else "Choose a file to get started", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            SecondaryButton("Choose file", Icons.Default.FolderOpen, onPick)
        }
        if (kind != null) {
            GlassCard {
                Text("Metadata found", color = TextMain, style = MaterialTheme.typography.titleMedium)
                MetadataSection(kind, inspection)
            }
            GlassCard {
                Text("What gets removed", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text(coverageText(kind), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        PrimaryButton(
            label = if (busy) "Cleaning…" else "Clean file",
            icon = Icons.Default.AutoAwesome,
            onClick = onClean,
            enabled = kind != null && cleanable && !busy
        )
        if (output != null) {
            CleanReadyCard(output, onSave)
        }
        GlassCard {
            Text(status, color = if (output != null) Clean else Muted, style = MaterialTheme.typography.bodySmall)
        }
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = AccentSoft, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Supported now: JPG, PNG and WEBP photos, MP3 ID3 tags, and DOCX, XLSX and PPTX document properties. Video, other audio and PDF cleaning are not available yet.",
                    color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetadataSection(kind: MetadataEngine.Kind, inspection: Inspection) {
    when {
        inspection.loading -> Text("Reading metadata on this device…", color = Muted, style = MaterialTheme.typography.bodySmall)
        inspection.error != null -> Text("Could not read this file: " + inspection.error, color = Muted, style = MaterialTheme.typography.bodySmall)
        kind == MetadataEngine.Kind.UNSUPPORTED -> Text("Metadata view is not available for this file type yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
        inspection.fields.isEmpty() -> Text("No supported metadata was found in this file.", color = Muted, style = MaterialTheme.typography.bodySmall)
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            inspection.fields.forEach { field ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(field.key, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(112.dp))
                    Text(
                        field.value, color = TextMain, style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanReadyCard(output: CleanOutput, onSave: () -> Unit) {
    val result = output.result
    GlassCard(tint = Clean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Default.Verified, tint = Clean, iconColor = Clean)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Clean copy ready", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text(output.saveName, color = Muted, fontFamily = MonoFont, fontSize = 12.sp)
            }
        }
        Text(summaryText(result), color = TextMain, style = MaterialTheme.typography.bodySmall)
        Text(result.note, color = Muted, style = MaterialTheme.typography.bodySmall)
        PrimaryButton(
            label = "Save clean copy",
            icon = Icons.Default.Download,
            onClick = onSave,
            gradient = listOf(Clean, Color(0xFF7BE8B8)),
            contentColor = CleanInk,
            glow = Clean
        )
    }
}

@Composable
private fun HistoryPage(history: List<HistoryEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("History", "A local record of files you have exported in this session.")
        if (history.isEmpty()) {
            GlassCard {
                IconBadge(icon = Icons.Default.History, size = 48.dp)
                Text("Nothing here yet", color = TextMain, style = MaterialTheme.typography.titleMedium)
                Text("Your saved clean copies will appear here. History is kept in memory for this session only.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        } else history.forEach {
            GlassCard {
                Text(it.name, color = TextMain, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(it.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsPage(onAbout: () -> Unit, onPermissions: () -> Unit, status: String) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Settings", "Manage access and learn how MetaVault handles your files.")
        GlassCard {
            Text("Privacy and access", color = TextMain, style = MaterialTheme.typography.titleMedium)
            SettingsAction("Optional file access", "Open Android's app-specific access settings", Icons.Default.FolderOpen, onPermissions)
            HorizontalDivider(color = Line)
            SettingsAction("Privacy and about", "How local processing works", Icons.Default.Shield, onAbout)
        }
        GlassCard {
            Text("Current session", color = TextMain, style = MaterialTheme.typography.titleMedium)
            Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("No cloud upload or account is required for cleaning.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AboutPage() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("About MetaVault", "A private workspace for cleaner file sharing.")
        GlassCard {
            BrandMark(48.dp)
            Text("MetaVault", color = TextMain, style = MaterialTheme.typography.headlineMedium)
            Text("MetaVault is a privacy-first metadata cleaner built to help you take control of the information attached to your files. Photos, audio and documents can carry details such as capture time, device, location or author. MetaVault removes the supported metadata and saves a new copy. Processing happens on your device, and your original file is never overwritten.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        GlassCard {
            InfoBullet("Local by design", "Cleaning runs on your device.")
            InfoBullet("Originals stay safe", "Cleaned files are saved as a new copy.")
            InfoBullet("Honest support", "JPG, PNG and WEBP photos, MP3 ID3 tags, and DOCX, XLSX and PPTX document properties can be cleaned. Video, other audio and PDF cleaning are planned and are not active in this build.")
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
private fun InfoLine(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        GlassSurface(modifier = Modifier.size(26.dp), cornerRadius = 13.dp, elevation = 0.dp) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(number, color = AccentSoft, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
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
        IconBadge(icon = icon, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
    }
}
