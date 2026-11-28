package com.metavault.cleaner

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BatchCleanerModel(application: Application) : AndroidViewModel(application) {
    var visible by mutableStateOf(false)
    var running by mutableStateOf(false)
        private set
    var stopRequested by mutableStateOf(false)
        private set
    var selected by mutableStateOf(emptyList<ExportItem>())
        private set
    var loading by mutableStateOf(false)
        private set
    var originals by mutableStateOf(false)
    val stems = mutableStateMapOf<Uri, String>()
    val results = mutableStateListOf<BatchResult>()
    var finished by mutableStateOf(false)
        private set
    val history = mutableStateOf(emptyList<HistoryEntry>())
    var refresh by mutableIntStateOf(0)
        private set
    var clash by mutableStateOf<NameClash?>(null)
        private set
    private var answer: CompletableDeferred<CollisionChoice>? = null

    suspend fun decide(conflict: NameClash): CollisionChoice = withContext(Dispatchers.Main.immediate) {
        val pending = CompletableDeferred<CollisionChoice>()
        check(answer == null)
        answer = pending
        clash = conflict
        try { pending.await() } finally { answer = null; clash = null }
    }

    fun choose(choice: CollisionChoice) { answer?.complete(choice) }

    fun select(uris: List<Uri>) {
        if (running || loading || uris.isEmpty()) return
        loading = true
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                selected = withContext(Dispatchers.IO) {
                    uris.distinctBy(::documentKey).map { uri ->
                        val name = runCatching { displayName(context, uri) }.getOrNull()
                            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
                        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                        ExportItem(uri, name, BatchFormat.resolve(name, mime))
                    }
                }
                stems.clear()
                selected.forEach { stems[it.uri] = sanitizeName(it.name.substringBeforeLast('.', it.name)) }
                results.clear()
                originals = false
                finished = false
                stopRequested = false
            } finally { loading = false }
        }
    }

    fun renameAll() {
        selected.filter { it.format != null }.forEach { stems[it.uri] = cleanStem(stems.getValue(it.uri)) }
    }

    fun remaining() = selected.filterIndexed { index, item ->
        item.format != null && results.getOrNull(index)?.status != BatchStatus.SAVED
    }

    fun reviewRemaining() {
        if (!finished) return
        selected = remaining()
        results.clear()
        originals = false
        finished = false
        stopRequested = false
    }

    fun stop() { stopRequested = true }

    fun start(tree: Uri?) {
        if (running || loading || finished || selected.isEmpty()) return
        if (tree == null && !originals) return
        running = true
        val sources = SelectedSources(getApplication(), selected.map { it.uri })
        val names = stems.toMap()
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                runBatch(selected, shouldStop = { stopRequested },
                    process = { item ->
                        val format = item.format
                        if (format == null) BatchResult(item.name, BatchStatus.SKIPPED, "Unsupported file format")
                        else withContext(Dispatchers.IO) {
                            val copy = prepareBatchCopy(format, MetadataEngine.load(context, originalUri(context, item.uri)))
                            val destination = if (tree == null) {
                                retainWriteGrant(context, item.uri)
                                replaceVerified(context, item.uri, item.name, copy.bytes)
                            } else saveCopy(context, tree, copy.bytes, names.getValue(item.uri), item.extension,
                                format.mime, sources, ::decide)
                            if (destination == null) BatchResult(item.name, BatchStatus.SKIPPED, "Skipped at name conflict")
                            else BatchResult(item.name, BatchStatus.SAVED, "$destination · ${copy.scope}")
                        }
                    },
                    fallbackName = { it.name },
                    onResult = {
                        results.add(it)
                        if (it.status == BatchStatus.SAVED) {
                            history.value = listOf(HistoryEntry(it.name, "Batch export · ${it.detail}")) + history.value
                        }
                    }
                )
            } finally {
                running = false
                finished = true
                refresh++
            }
        }
    }
}

internal data class ExportItem(val uri: Uri, val name: String, val format: BatchFormat?) {
    val extension = name.substringAfterLast('.', "").ifBlank { format?.extension.orEmpty() }
}

internal object LocalFiles : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal object LocalFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
internal fun BatchCleanerDialog(model: BatchCleanerModel) {
    val files = rememberLauncherForActivityResult(LocalFiles, model::select)
    val folder = rememberLauncherForActivityResult(LocalFolder) { uri -> uri?.let(model::start) }
    model.clash?.let { conflict ->
        var rename by remember(conflict) { mutableStateOf(false) }
        var stem by remember(conflict) { mutableStateOf(conflict.stem) }
        AlertDialog(
            onDismissRequest = { model.choose(CollisionChoice.Skip) },
            title = { Text(if (rename) "Rename copy" else "File already exists") },
            text = { Column {
                Text(conflict.name)
                if (rename) OutlinedTextField(stem, { stem = it }, singleLine = true,
                    label = { Text("New file name") }, suffix = { Text(".${conflict.ext}") })
                else if (conflict.canOverwrite) Text("Overwrite saves a backup of the existing file in History.")
                else Text("Choose a different name or skip. For a selected original, use its overwrite action. For limited gallery access, choose the destination with the folder picker.")
            } },
            confirmButton = { Column {
                if (rename) AuroraTextButton(enabled = validStem(stem) && !stem.equals(conflict.stem, true),
                    onClick = { model.choose(CollisionChoice.Rename(stem)) }) { Text("Use this name") }
                else {
                    AuroraTextButton(onClick = { rename = true }) { Text("Rename") }
                    if (conflict.canOverwrite) AuroraTextButton(onClick = { model.choose(CollisionChoice.Overwrite) }) { Text("Overwrite") }
                }
            } },
            dismissButton = { AuroraTextButton(onClick = { model.choose(CollisionChoice.Skip) }) { Text("Skip") } }
        )
        return
    }
    if (!model.visible) return
    var confirmOriginals by remember { mutableStateOf(false) }
    if (confirmOriginals) {
        AlertDialog(onDismissRequest = { confirmOriginals = false },
            title = { Text("Overwrite selected originals?") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { Text("Each listed original will be backed up in History before replacement.") }
                items(model.selected.filter { it.format != null }) { Text(it.name) }
            } },
            confirmButton = { AuroraTextButton(onClick = { confirmOriginals = false; model.start(null) }) { Text("Back up and overwrite") } },
            dismissButton = { AuroraTextButton(onClick = { confirmOriginals = false }) { Text("Cancel") } })
        return
    }
    val total = model.selected.size
    val done = model.results.size
    val saved = model.results.count { it.status == BatchStatus.SAVED }
    AlertDialog(
        onDismissRequest = { if (!model.running) model.visible = false },
        title = { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AuroraLayout.compact)) {
            if (model.finished && saved > 0) AuroraSuccessMark()
            Text(when {
            model.finished -> "Batch results"
            model.running -> "Processing files"
            total > 0 -> "Review export"
            else -> "Batch cleaner"
        }) } },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    if (model.running) AuroraProgress(done, total,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    Text(when {
                        model.loading -> "Reading file names…"
                        model.running -> "Processing file ${minOf(done + 1, total)} of $total"
                        model.finished -> "$saved saved · ${model.results.count { it.status == BatchStatus.SKIPPED }} skipped · " +
                            "${model.results.count { it.status == BatchStatus.FAILED }} failed · ${total - done} unprocessed"
                        total > 0 -> "$total selected. Review names and choose how to save."
                        else -> "Select JPG, PNG, WebP, MP3 or Office files from your device."
                    }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (!model.running && !model.loading && !model.finished && total > 0) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Save mode", style = MaterialTheme.typography.labelLarge)
                            FilterChip(selected = !model.originals, onClick = { model.originals = false }, label = { Text("Save as new files") })
                            FilterChip(selected = model.originals, onClick = { model.originals = true }, label = { Text("Overwrite originals") })
                            if (!model.originals) AuroraTextButton(onClick = model::renameAll) { Text("Rename all · add _clean") }
                        }
                    }
                    items(model.selected, key = { it.uri.toString() }) { item ->
                        if (item.format == null || model.originals) Text(item.name + if (item.format == null) " · Unsupported" else "")
                        else ExportNameField(model.stems[item.uri].orEmpty(), { model.stems[item.uri] = it },
                            item.extension, item.name)
                    }
                    item { AuroraTextButton(onClick = { files.launch(arrayOf("*/*")) }) { Text("Change selection") } }
                }
                items(model.results) { result ->
                    val (icon, label) = when (result.status) {
                        BatchStatus.SAVED -> Icons.Default.CheckCircle to "Saved and verified"
                        BatchStatus.SKIPPED -> Icons.Default.SkipNext to "Skipped"
                        BatchStatus.FAILED -> Icons.Default.Warning to "Failed"
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(result.name, style = MaterialTheme.typography.titleSmall)
                            Text("$label · ${result.detail}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
                if (model.finished && model.remaining().isNotEmpty()) {
                    item { AuroraOutlinedButton(onClick = model::reviewRemaining, modifier = Modifier.fillMaxWidth()) {
                        Text("Review unfinished files (${model.remaining().size})")
                    } }
                }
            }
        },
        confirmButton = {
            when {
                model.loading -> AuroraLoadingLabel("Loading…")
                model.running -> AuroraTextButton(enabled = !model.stopRequested, onClick = model::stop) {
                    Text(if (model.stopRequested) "Stopping…" else "Stop after this file")
                }
                model.finished || total == 0 -> AuroraTextButton(onClick = { files.launch(arrayOf("*/*")) }) {
                    Text(if (model.finished) "Choose another batch" else "Choose files")
                }
                model.originals -> AuroraTextButton(enabled = model.selected.any { it.format != null },
                    onClick = { confirmOriginals = true }) { Text("Review originals") }
                else -> AuroraTextButton(enabled = model.selected.filter { it.format != null }.all { validStem(model.stems[it.uri].orEmpty()) },
                    onClick = { folder.launch(null) }) { Text("Choose output folder") }
            }
        },
        dismissButton = { if (!model.running) AuroraTextButton(onClick = { model.visible = false }) { Text("Close") } }
    )
}

@Composable
internal fun ExportNameField(stem: String, onChange: (String) -> Unit, extension: String,
                            label: String, enabled: Boolean = true) {
    val valid = validStem(stem)
    OutlinedTextField(value = stem, onValueChange = onChange, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
        suffix = { Text(".$extension") }, isError = !valid,
        supportingText = { Text(if (valid) "Save as $stem.$extension" else "Enter a name without paths or special characters.") })
}
