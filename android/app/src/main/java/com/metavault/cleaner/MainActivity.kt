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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val Ink = Color(0xFF080D1C)
private val Mint = Color(0xFF75EBD0)
private val Violet = Color(0xFF9385FF)
private val Muted = Color(0xFFA6B2CE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 13, 28)
        window.navigationBarColor = android.graphics.Color.rgb(8, 13, 28)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            ), 410)
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 410)
        }
        setContent { MetaVaultScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaVaultScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No file selected") }
    var mime by remember { mutableStateOf("image/jpeg") }
    var preserve by remember { mutableStateOf(true) }
    var removeMetadata by remember { mutableStateOf(true) }
    var output by remember { mutableStateOf("image/jpeg") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Your files stay on this device.") }
    var readyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var saveMime by remember { mutableStateOf("image/jpeg") }
    var saveName by remember { mutableStateOf("metavault-clean.jpg") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
            readyBytes = null
            status = "File selected. Ready to clean."
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
                }.onSuccess { status = "Saved successfully to your chosen location." }
                    .onFailure { status = "Save failed: ${it.message}" }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = Color(0xFF151D35), primary = Mint,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111B38), Ink, Color(0xFF071C25))))) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 20.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(top = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✳", color = Mint, fontSize = 32.sp)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("metaVault", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("PRIVATE FILE WORKSPACE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                        }
                    }
                    Surface(color = Color(0x223DE0BF), shape = RoundedCornerShape(50)) {
                        Text("● LOCAL", color = Mint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 10.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("Clean the hidden.", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Keep the original.", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Mint)
                    Text("Remove hidden image metadata privately.", color = Muted, fontSize = 13.sp)
                }
                GlassCard {
                    Text("DEVICE LIBRARY ACCESS", color = Mint, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) "Full file access is enabled."
                        else "Normal media permissions and the file picker work without full access.",
                        color = Muted, fontSize = 11.sp
                    )
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = AndroidUri.parse("package:${context.packageName}")
                                }
                                runCatching { context.startActivity(intent) }
                                    .onFailure { status = "Open Android Settings and allow file access if available." }
                            } else {
                                status = "Full file access is already enabled, or this Android version uses normal permissions."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("OPTIONAL: ALLOW ACCESS TO ALL FILES")
                    }
                    Text("If you skip this, choose files manually. Android may restrict this special permission.", color = Muted, fontSize = 10.sp)
                }
                GlassCard {
                    Text("01  /  SELECT YOUR FILE", color = Mint, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().clickable { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "video/*", "audio/*", "application/pdf")) }
                        .border(1.dp, Color(0x667D8EB5), RoundedCornerShape(18.dp))
                        .background(Color(0x221D2A48), RoundedCornerShape(18.dp)).padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, null, tint = Mint, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (selectedUri == null) "Choose a file" else fileName, fontWeight = FontWeight.SemiBold)
                            Text("Tap to browse phone storage", color = Muted, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                    Text("SUPPORTED NOW: JPG · PNG · WEBP", color = Mint, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                GlassCard {
                    Text("02  /  CLEANING OPTIONS", color = Mint, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    SettingRow("Preserve original quality", "Keep dimensions; best-effort re-encoding", preserve) { preserve = it }
                    HorizontalDivider(color = Color(0x22FFFFFF))
                    SettingRow("Remove all image metadata", "EXIF · GPS · camera details", removeMetadata) { removeMetadata = it }
                    Text("OUTPUT FORMAT", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("JPG" to "image/jpeg", "PNG" to "image/png", "WEBP" to "image/webp").forEach { (label, value) ->
                            FilterChip(selected = output == value, onClick = { output = value }, enabled = !preserve, label = { Text(label) })
                        }
                    }
                    Text(if (preserve) "Turn OFF preserve quality to choose a format." else "Format conversion may change image data.", color = Muted, fontSize = 10.sp)
                }
                Button(
                    onClick = {
                        val uri = selectedUri ?: return@Button
                        if (!removeMetadata) { status = "Enable metadata removal to continue."; return@Button }
                        if (!mime.startsWith("image/")) { status = "This Android MVP currently processes images only. Video, audio and PDF are not cleaned yet."; return@Button }
                        busy = true; status = "Cleaning image locally…"; readyBytes = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                                        ?: error("Could not read this image")
                                    val targetMime = if (preserve) mime else output
                                    val format = when (targetMime) {
                                        "image/png" -> Bitmap.CompressFormat.PNG
                                        "image/webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                                        else -> Bitmap.CompressFormat.JPEG
                                    }
                                    val stream = ByteArrayOutputStream()
                                    val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 96
                                    check(bitmap.compress(format, quality, stream)) { "Image export failed" }
                                    bitmap.recycle()
                                    stream.toByteArray() to targetMime
                                }
                            }.onSuccess { (bytes, targetMime) ->
                                readyBytes = bytes; saveMime = targetMime
                                val ext = when (targetMime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                                saveName = fileName.substringBeforeLast('.', fileName) + ".clean." + ext
                                status = "Metadata-stripped copy ready. Original file is untouched."
                            }.onFailure { status = "Could not clean file: ${it.message}" }
                            busy = false
                        }
                    },
                    enabled = selectedUri != null && !busy,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, disabledContainerColor = Color(0x554F5680)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(10.dp))
                    Text(if (busy) "CLEANING…" else "CLEAN MY FILE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                AnimatedVisibility(readyBytes != null) {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Verified, null, tint = Mint, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Clean copy is ready", fontWeight = FontWeight.Bold)
                                Text(saveName, color = Muted, fontSize = 11.sp)
                            }
                        }
                        Button(onClick = { saver.launch(saveName) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF168F7D))) {
                            Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("SAVE CLEAN FILE")
                        }
                        Text("Image dimensions are retained. Re-encoding may change file size or pixels; this is not byte-for-byte lossless.", color = Muted, fontSize = 10.sp)
                    }
                }
                GlassCard { Text(status, color = if (readyBytes != null) Mint else Muted, fontSize = 12.sp) }
                Text("METAVAULT  •  PRIVATE BY DESIGN  •  ANDROID BETA", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xAA17223B), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0x337F94BD), RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
