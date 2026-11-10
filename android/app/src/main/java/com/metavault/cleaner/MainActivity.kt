package com.metavault.cleaner

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val Ink = Color(0xFF050A16)
private val Panel = Color(0xCC101D32)
private val Mint = Color(0xFF39F5B5)
private val Aqua = Color(0xFF20C8FF)
private val Blue = Color(0xFF356BFF)
private val Violet = Color(0xFF9274FF)
private val Muted = Color(0xFFA8B8D0)
private val DisplayFont = FontFamily.SansSerif

private enum class Page(val label: String) {
    HOME("Home"), BROWSE("Browse"), PHOTOS("Photos"), VIDEOS("Videos"),
    AUDIO("Audio"), PDFS("PDF files"), DOCS("Documents"), CLEANER("Cleaner"),
    HISTORY("History"), SETTINGS("Settings"), ABOUT("About")
}

private data class HistoryEntry(val name: String, val detail: String)

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
    var saveName by remember { mutableStateOf("metavault-clean.jpg") }
    var history by remember { mutableStateOf(listOf<HistoryEntry>()) }

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
            background = Ink, surface = Panel, primary = Mint, secondary = Aqua,
            tertiary = Violet, onBackground = Color.White, onSurface = Color.White
        ),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Bold),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = DisplayFont),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = DisplayFont)
        )
    ) {
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
                                label = { Text(item.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
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
                        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
                        .padding(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Header(onAbout = { page = Page.ABOUT })
                    AnimatedContent(targetState = page, label = "page") { current ->
                        when (current) {
                            Page.HOME -> HomePage(
                                onBrowse = { page = Page.BROWSE }, onClean = { page = Page.CLEANER },
                                onCategory = { page = it }, onHistory = { page = Page.HISTORY }
                            )
                            Page.BROWSE -> BrowsePage(onCategory = { page = it }, onPick = { picker.launch(arrayOf("*/*")) })
                            Page.PHOTOS -> CategoryPage(Page.PHOTOS, "JPG · PNG · WEBP", "Inspect image metadata and create a cleaned image copy.", "Image metadata cleaning is available for JPG, PNG and WEBP.", onPick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, onCleaner = { page = Page.CLEANER })
                            Page.VIDEOS -> CategoryPage(Page.VIDEOS, "MP4 · MOV · OTHER VIDEO", "Browse video files and review the support status.", "Video metadata cleaning is not implemented yet. Files are never modified.", onPick = { picker.launch(arrayOf("video/*")) }, onCleaner = { page = Page.CLEANER })
                            Page.AUDIO -> CategoryPage(Page.AUDIO, "MP3 · M4A · FLAC · OTHER AUDIO", "Browse music and audio files on your device.", "Audio metadata cleaning is not implemented yet. Files are never modified.", onPick = { picker.launch(arrayOf("audio/*")) }, onCleaner = { page = Page.CLEANER })
                            Page.PDFS -> CategoryPage(Page.PDFS, "PDF DOCUMENTS", "Choose PDFs to review future metadata-cleaning support.", "PDF metadata cleaning is not implemented yet. No PDF will be altered.", onPick = { picker.launch(arrayOf("application/pdf")) }, onCleaner = { page = Page.CLEANER })
                            Page.DOCS -> CategoryPage(Page.DOCS, "DOCX · XLSX · PPTX · MORE", "Browse documents using Android's secure file picker.", "Office document cleaning is planned, not yet available.", onPick = { picker.launch(arrayOf("application/*", "text/*")) }, onCleaner = { page = Page.CLEANER })
                            Page.CLEANER -> CleanerPage(
                                fileName, mime, selectedUri != null, preserve, { preserve = it },
                                removeMetadata, { removeMetadata = it }, output, { output = it },
                                busy, status, readyBytes != null, saveName,
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
                    Text("METAVAULT  •  CLEAN · PROTECT · STAY PRIVATE", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                }
            }
        }
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
                Text("METADATA CLEANER", color = Muted, fontSize = 8.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Bold)
            }
        }
        Surface(color = Color(0x2239F5B5), shape = RoundedCornerShape(50), modifier = Modifier.clickable { onAbout() }) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = Mint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("LOCAL ONLY", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                    Text("PRIVATE FILE WORKSPACE  ·  BETA", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Your files.", color = Color.White, fontSize = 33.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.4).sp)
            Text("Your privacy.", color = Mint, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.3).sp)
            Text("A smarter, safer way to prepare photos and personal files before sharing them. Review, clean supported image metadata, and export a fresh copy — all on your device.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        GlassCard(accent = Mint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("PRIVATE FILE WORKSPACE", color = Mint, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
                    Text("Your privacy deserves a cleaner.", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("MetaVault helps remove hidden image details before sharing. Choose your files, review the options, and save a separate clean copy. Your original stays untouched.", color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Default.Security, null, tint = Mint, modifier = Modifier.size(52.dp))
            }
            GradientButton("OPEN FILE LIBRARY", Icons.Default.FolderOpen, onBrowse)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("03", "IMAGE FORMATS", Mint, Modifier.weight(1f))
            StatTile("LOCAL", "PROCESSING", Aqua, Modifier.weight(1f))
            StatTile("SAFE", "NEW COPIES", Violet, Modifier.weight(1f))
        }
        SectionTitle("EXPLORE CATEGORIES", "Choose a file type")
        CategoryGrid(onCategory)
        GlassCard(accent = Aqua) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = Aqua, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ready to clean?", fontWeight = FontWeight.Bold)
                    Text("Start with a supported image file.", color = Muted, fontSize = 11.sp)
                }
            }
            GradientButton("OPEN CLEANER", Icons.Default.CleaningServices, onClean)
        }
        Text("RECENT ACTIVITY", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onHistory() })
        Row(Modifier.fillMaxWidth().clickable { onHistory() }, verticalAlignment = Alignment.CenterVertically) {
            Text("View cleaning history", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = Mint)
        }
    }
}

@Composable
private fun BrowsePage(onCategory: (Page) -> Unit, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        PageHeading("File library", "Browse by category and choose files securely.")
        GlassCard {
            Text("BROWSE DEVICE FILES", color = Mint, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
            Text("Use Android's system picker to access the files you choose. MetaVault does not silently scan your storage.", color = Muted, fontSize = 12.sp)
            GradientButton("BROWSE ALL FILES", Icons.Default.FolderOpen, onPick)
        }
        CategoryGrid(onCategory)
        GlassCard(accent = Aqua) {
            Text("ACCESS & PRIVACY", color = Aqua, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text("Only files you select are opened. Full-storage access is optional and is not required for the system picker.", color = Muted, fontSize = 11.sp)
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
                        Text(if (target == Page.PHOTOS) "Clean supported images" else "Browse files", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CategoryPage(page: Page, formats: String, description: String, support: String, onPick: () -> Unit, onCleaner: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        PageHeading(page.label, description)
        GlassCard(accent = when (page) { Page.PHOTOS -> Mint; Page.VIDEOS -> Aqua; Page.AUDIO -> Violet; else -> Blue }) {
            Text("FILE TYPE", color = Mint, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
            Text(formats, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(support, color = Muted, fontSize = 12.sp)
            GradientButton("CHOOSE ${page.label.uppercase()}", Icons.Default.FolderOpen, onPick)
        }
        GlassCard {
            Text("HOW IT WORKS", color = Aqua, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            InfoLine("01", "Choose files", "Select only the files you want to work with.")
            InfoLine("02", "Review support", "MetaVault tells you what it can process.")
            InfoLine("03", "Save safely", "The original is kept untouched.")
        }
        OutlinedButton(onClick = onCleaner, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("OPEN CLEANER WORKSPACE") }
    }
}

@Composable
private fun CleanerPage(
    fileName: String, mime: String, hasFile: Boolean, preserve: Boolean, onPreserve: (Boolean) -> Unit,
    remove: Boolean, onRemove: (Boolean) -> Unit, output: String, onOutput: (String) -> Unit,
    busy: Boolean, status: String, ready: Boolean, saveName: String,
    onPick: () -> Unit, onClean: () -> Unit, onSave: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Cleaner studio", "Inspect your selection, choose options, then export a fresh copy.")
        GlassCard(accent = Mint) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SELECTED FILE", color = Mint, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
                Surface(color = if (hasFile) Color(0x2239F5B5) else Color(0x22A8B8D0), shape = RoundedCornerShape(50)) {
                    Text(if (hasFile) "READY TO REVIEW" else "AWAITING FILE", color = if (hasFile) Mint else Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.InsertDriveFile, null, tint = Aqua, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (hasFile) fileName else "No file selected", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (hasFile) mime else "Choose a file to get started", color = Muted, fontSize = 10.sp)
                }
            }
            GradientButton("CHOOSE FILE", Icons.Default.FolderOpen, onPick)
        }
        GlassCard {
            Text("CLEANING OPTIONS", color = Mint, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
            SettingRow("Remove image metadata", "Supported image metadata is stripped", remove, onRemove)
            HorizontalDivider(color = Color(0x22FFFFFF))
            SettingRow("Keep original format", "Best effort; image is re-encoded", preserve, onPreserve)
            if (!preserve) {
                Text("OUTPUT FORMAT", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("JPG" to "image/jpeg", "PNG" to "image/png", "WEBP" to "image/webp").forEach { (label, value) ->
                        FilterChip(selected = output == value, onClick = { onOutput(value) }, label = { Text(label) })
                    }
                }
            }
        }
        Button(onClick = onClean, enabled = hasFile && !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Default.AutoAwesome, null)
            Spacer(Modifier.width(9.dp)); Text(if (busy) "PROCESSING LOCALLY…" else "CLEAN SELECTED FILE", fontWeight = FontWeight.Bold)
        }
        AnimatedVisibility(ready) {
            GlassCard(accent = Mint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Verified, null, tint = Mint, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) { Text("Clean copy ready", fontWeight = FontWeight.Bold); Text(saveName, color = Muted, fontSize = 10.sp) }
                }
                GradientButton("SAVE CLEAN COPY", Icons.Default.Download, onSave)
                Text("Dimensions are retained. Re-encoding may change pixels or file size; this is not byte-for-byte lossless.", color = Muted, fontSize = 10.sp)
            }
        }
        GlassCard { Text(status, color = if (ready) Mint else Muted, fontSize = 12.sp) }
        GlassCard(accent = Aqua) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Aqua, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Supported now: JPG · PNG · WEBP. Video, audio and PDF cleaning are not available yet.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun HistoryPage(history: List<HistoryEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Activity history", "A local record of files you have exported in this session.")
        if (history.isEmpty()) {
            GlassCard(accent = Aqua) {
                Icon(Icons.Default.History, null, tint = Aqua, modifier = Modifier.size(34.dp))
                Text("Nothing here yet", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Your successful clean-copy exports will appear here. History is kept in memory for this session only.", color = Muted, fontSize = 11.sp)
            }
        } else history.forEach {
            GlassCard { Text(it.name, fontWeight = FontWeight.Bold); Text(it.detail, color = Muted, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun SettingsPage(onAbout: () -> Unit, onPermissions: () -> Unit, status: String) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeading("Settings", "Manage access and learn how MetaVault handles your files.")
        GlassCard(accent = Mint) {
            Text("PRIVACY & ACCESS", color = Mint, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            SettingsAction("Optional file access", "Open Android's app-specific access settings", Icons.Default.FolderOpen, onPermissions)
            HorizontalDivider(color = Color(0x22FFFFFF))
            SettingsAction("Privacy & about", "How local processing works", Icons.Default.Shield, onAbout)
        }
        GlassCard {
            Text("CURRENT SESSION", color = Aqua, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(status, color = Muted, fontSize = 11.sp)
            Text("No cloud upload or account is required for the current image-cleaning workflow.", color = Muted, fontSize = 10.sp)
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
            Text("MetaVault is a privacy-first metadata cleaner built to help you take control of the information attached to your files. Photos can contain hidden details such as capture time, device information and location tags. When supported, MetaVault re-encodes the image to create a cleaner copy before you share it. Processing happens locally on your device, and your original file is never overwritten.", color = Muted, fontSize = 12.sp)
        }
        GlassCard {
            InfoLine("01", "Local by design", "The current cleaning flow runs on your device.")
            InfoLine("02", "Originals stay safe", "Cleaned images are saved as a new file.")
            InfoLine("03", "Honest support", "Current support: JPG, PNG and WEBP image cleaning. Video, audio, PDF and Office document cleaning are planned and are not active in this build.")
        }
        Text("Android beta · Features are being developed incrementally.", color = Muted, fontSize = 10.sp)
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
        Text(eyebrow, color = Mint, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
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
private fun GradientButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(1.dp)
    ) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF17B98D), Color(0xFF168FC4), Color(0xFF5367DB))), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(9.dp)); Text(label, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier, tint) {
        Text(value, color = tint, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Muted, fontSize = 8.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoLine(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(number, color = Mint, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        Spacer(Modifier.width(11.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(detail, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SettingsAction(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Mint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp); Text(detail, color = Muted, fontSize = 10.sp) }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
