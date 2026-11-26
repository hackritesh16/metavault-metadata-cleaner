package com.metavault.cleaner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel

@Composable
internal fun AdvancedMetadataEntry(uri: Uri, enabled: Boolean) = key(uri) {
    var open by rememberSaveable { mutableStateOf(false) }
    AuroraOutlinedButton(onClick = { open = true }, enabled = enabled) { Text("View More Data") }
    if (open) AdvancedMetadataViewer(uri) { open = false }
}

@Composable
private fun AdvancedMetadataViewer(uri: Uri, close: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf<Pair<FileChannel, BlockIndex>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(uri) {
        var snapshot: FileChannel? = null
        try {
            loaded = withContext(Dispatchers.IO) {
                val file = snapshotChannel(context.cacheDir)
                snapshot = file
                val input = context.contentResolver.openInputStream(originalUri(context, uri))
                    ?: error("Could not open this image.")
                input.use { file.snapshot(it, { ensureActive() }) }
                file to MetadataBlockIndex(file) { ensureActive() }.scan()
            }
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Could not read this image."
        } finally {
            snapshot?.close()
        }
    }

    val session = loaded
    val block = remember(session, selected) { session?.second?.blocks?.find { it.offset == selected } }
    fun back() { if (selected != null) selected = null else close() }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = AuroraLayout.contentMaxWidth).fillMaxSize(), color = Bg) {
            Column(Modifier.safeDrawingPadding().padding(AuroraLayout.padding),
                verticalArrangement = Arrangement.spacedBy(AuroraLayout.gap)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(block?.name ?: "Advanced Metadata Viewer", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge, color = TextMain)
                    AuroraTextButton(onClick = ::back) { Text(if (block == null) "Close" else "Back") }
                }
                when {
                    error != null -> Text(error.orEmpty(), color = Warn)
                    session == null -> AuroraLoadingLabel("Reading metadata blocks…")
                    block != null -> key(block.offset) { BlockDetails(uri, session.first, block) }
                    else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(AuroraLayout.compact)) {
                        session.second.warning?.let { item { Text(it, color = Warn) } }
                        if (session.second.blocks.isEmpty() && session.second.warning == null) item {
                            Text("No metadata blocks found.", color = Muted)
                        }
                        items(session.second.blocks, key = { it.offset }) { entry ->
                            AuroraGlassCard(onClick = { selected = entry.offset }) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium, color = TextMain)
                                Text("${entry.type} · ${entry.size} bytes", style = TechnicalLabel, color = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyDump(context: Context, text: String) {
    val clip = ClipData.newPlainText("Metadata block", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private const val COPY_LIMIT = 16 * 1024L

@Composable
private fun BlockDetails(source: Uri, file: FileChannel, block: MetadataBlock) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ascii by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file, block) {
        preview = withContext(Dispatchers.IO) {
            try { file.blockPreview(block) } catch (_: java.io.IOException) { null }
        } ?: "No decoded preview available."
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val newFile = DocumentsContract.createDocument(resolver, parent, "application/octet-stream",
                        "metadata-${block.offset}.bin") ?: error("Could not create the export.")
                    check(!SelectedSources(context, listOf(source)).contains(newFile)) { "Choose a different destination." }
                    try {
                        val output = resolver.openOutputStream(newFile) ?: error("Could not open the export.")
                        output.use { file.exportBlock(block, it) { ensureActive() } }
                    } catch (e: Exception) {
                        if (!DocumentsContract.deleteDocument(resolver, newFile)) error("Export interrupted. A partial export may remain in the folder.")
                        throw e
                    }
                }
                notice = "Exported ${block.size} bytes."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.localizedMessage ?: "Export failed." }
            finally { exporting = false }
        }
    }
    val pages = ((block.size + BLOCK_PAGE - 1) / BLOCK_PAGE).toInt()
    val list = rememberLazyListState()
    var jump by rememberSaveable { mutableStateOf(false) }
    if (pages > 8) {
        AuroraTextButton(onClick = { jump = true }) { Text("Go to byte…") }
        if (jump) {
            var address by rememberSaveable { mutableStateOf("") }
            val target = address.toLongOrNull()?.takeIf { it in 0 until block.size }
            AlertDialog(onDismissRequest = { jump = false }, title = { Text("Go to byte") }, text = {
                OutlinedTextField(address, { address = it.take(12) }, singleLine = true,
                    label = { Text("Offset (0–${block.size - 1})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }, confirmButton = {
                AuroraTextButton(enabled = target != null, onClick = {
                    target?.let { scope.launch { list.scrollToItem(3 + (it / BLOCK_PAGE).toInt()) } }
                    jump = false
                }) { Text("Go") }
            }, dismissButton = { AuroraTextButton(onClick = { jump = false }) { Text("Cancel") } })
        }
    }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(AuroraLayout.compact)) {
        item {
            AuroraGlassCard {
                Text("${block.type} · ${block.size} bytes", style = TechnicalLabel, color = TextMain)
                Text("Payload starts at file byte ${block.offset}. Export saves all payload bytes in a new .bin file.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                AuroraOutlinedButton(onClick = { export.launch(null) }, enabled = !exporting) {
                    Text(if (exporting) "Exporting…" else "Export block")
                }
                if (notice.isNotEmpty()) Text(notice, color = TextMain)
            }
        }
        item {
            AuroraGlassCard {
                Text("Decoded preview", style = MaterialTheme.typography.titleMedium, color = TextMain)
                Text(preview ?: "Reading…", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        item {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(AuroraLayout.compact)) {
                    FilterChip(selected = !ascii, onClick = { ascii = false }, label = { Text("HEX") })
                    FilterChip(selected = ascii, onClick = { ascii = true }, label = { Text("ASCII") })
                }
                if (ascii) Text("Dots represent non-printable bytes.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (block.size in 1..COPY_LIMIT) AuroraTextButton(onClick = {
                    scope.launch {
                        try {
                            val text = withContext(Dispatchers.IO) { file.readRange(block.offset, block.size.toInt()).blockDump(ascii) }
                            copyDump(context, text)
                            notice = "Block copied."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { notice = "Could not copy this block." }
                    }
                }) { Text(if (ascii) "Copy block as ASCII" else "Copy block as HEX") }
                if (pages == 0) Text("Empty payload.", color = Muted)
            }
        }
        items(pages, key = { "page-$it" }) { i ->
            val offset = i.toLong() * BLOCK_PAGE
            val count = minOf(BLOCK_PAGE.toLong(), block.size - offset).toInt()
            key(ascii) {
                var page by remember { mutableStateOf<Result<String>?>(null) }
                LaunchedEffect(file, offset) {
                    page = withContext(Dispatchers.IO) {
                        try { Result.success(file.readRange(block.offset + offset, count).blockDump(ascii, if (ascii) 32 else 8)) }
                        catch (e: java.io.IOException) { Result.failure(e) }
                    }
                }
                AuroraGlassCard {
                    Text("Bytes $offset–${offset + count - 1}", style = TechnicalLabel, color = Muted)
                    val columns = if (ascii) 32 else 8
                    val lines = (count + columns - 1) / columns
                    val text = page?.getOrNull()
                    Text(text ?: if (page == null) "" else "Could not read these bytes.", minLines = lines, style = TechnicalLabel, color = TextMain)
                    if (block.size > COPY_LIMIT) AuroraTextButton(onClick = {
                        text?.let { copyDump(context, it) }
                    }, enabled = text != null) { Text("Copy these bytes") }
                }
            }
        }
    }
}
