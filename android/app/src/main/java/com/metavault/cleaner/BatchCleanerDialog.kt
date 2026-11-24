package com.metavault.cleaner

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class BatchSaveMode { FOLDER, OVERWRITE_ORIGINALS }

/** One selected file. [ext] is the extension the cleaned copy will have; it cannot be edited. */
internal class BatchEntry(val uri: Uri, val name: String, val ext: String)

/** A question waiting for the user: a file with this name is already in the output folder. */
internal class ConflictPrompt(
    val fileName: String, val existingName: String,
    val reply: CompletableDeferred<Pair<ConflictChoice, Boolean>>
)

internal class BatchCleanerModel(application: Application) : AndroidViewModel(application) {
    var visible by mutableStateOf(false)
    var running by mutableStateOf(false)
        private set
    var stopRequested by mutableStateOf(false)
        private set
    var entries by mutableStateOf(emptyList<BatchEntry>())
        private set
    /** Output name of each selected file, without the extension. Editable before the batch starts. */
    val bases = mutableStateMapOf<Uri, String>()
    var mode by mutableStateOf(BatchSaveMode.FOLDER)
    val results = mutableStateListOf<BatchResult>()
    var finished by mutableStateOf(false)
        private set
    var conflict by mutableStateOf<ConflictPrompt?>(null)
        private set
    val history = mutableStateOf(emptyList<HistoryEntry>())
    private var remembered: ConflictChoice? = null

    fun select(uris: List<Uri>) {
        if (running || uris.isEmpty()) return
        val chosen = uris.distinct()
        results.clear()
        finished = false
        stopRequested = false
        val context = getApplication<Application>()
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                chosen.map { uri ->
                    val name = sourceName(context, uri)
                    val ext = BatchFormat.resolve(name, runCatching { context.contentResolver.getType(uri) }.getOrNull())?.extension
                        ?: splitName(name).second
                    BatchEntry(uri, name, ext)
                }
            }
            bases.clear()
            list.forEach { bases[it.uri] = splitName(defaultOutputName(it.name)).first }
            entries = list
        }
    }

    /** One click: every output name gets _clean added. Names that already have it are left alone. */
    fun addCleanToAll() {
        entries.forEach { bases[it.uri] = withCleanBase(bases[it.uri] ?: splitName(it.name).first) }
    }

    fun useOriginalNames() {
        entries.forEach { bases[it.uri] = splitName(it.name).first }
    }

    fun answerConflict(choice: ConflictChoice, forRemaining: Boolean) {
        conflict?.reply?.complete(choice to forRemaining)
    }

    fun stop() { stopRequested = true }

    fun start(tree: Uri?) {
        if (running || finished || entries.isEmpty()) return
        if (mode == BatchSaveMode.FOLDER && tree == null) return
        running = true
        remembered = null
        val sources = entries.map { it.uri }
        val chosenMode = mode
        val chosenBases = bases.toMap()
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val keys = sources.map(::documentKey).toSet()
                val index = if (chosenMode == BatchSaveMode.FOLDER) {
                    withContext(Dispatchers.IO) { listFolder(context, tree!!) }
                } else null
                runBatch(sources, shouldStop = { stopRequested },
                    process = { uri -> processOne(context, uri, chosenMode, tree, index, keys, chosenBases[uri]) },
                    fallbackName = { "File ${results.size + 1}" },
                    onResult = {
                        results.add(it)
                        if (it.status == BatchStatus.SAVED) {
                            history.value = listOf(HistoryEntry(it.name, "Batch export · ${it.detail}")) + history.value
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results.add(BatchResult("Output folder", BatchStatus.FAILED, e.localizedMessage ?: "Could not read the folder"))
            } finally {
                running = false
                finished = true
            }
        }
    }

    private suspend fun processOne(
        context: Context, uri: Uri, chosenMode: BatchSaveMode, tree: Uri?, index: MutableMap<String, Uri>?,
        keys: Set<Pair<String?, String>>, base: String?
    ): BatchResult {
        val ready = when (val prepared = withContext(Dispatchers.IO) { prepareBatchDocument(context, uri) }) {
            is Prepared.Finished -> return prepared.result
            is Prepared.Ready -> prepared
        }
        return try {
            if (chosenMode == BatchSaveMode.OVERWRITE_ORIGINALS) {
                withContext(Dispatchers.IO) { overwriteOriginalDocument(context, uri, ready) }
            } else {
                val folder = index ?: error("Output folder is not ready")
                val stem = base ?: splitName(defaultOutputName(ready.name)).first
                val desired = cleanTypedName(stem, "metavault-file") + "." + ready.format.extension
                val existing = folder[nameKey(desired)]
                val choice = if (existing != null) askConflict(ready.name, desired) else null
                when (val target = decideTarget(desired, existing != null, choice) { folder.containsKey(nameKey(it)) }) {
                    SaveTarget.Skip -> BatchResult(ready.name, BatchStatus.SKIPPED, "$desired already exists, so it was skipped")
                    is SaveTarget.Write -> withContext(Dispatchers.IO) {
                        saveToFolder(context, tree ?: error("Output folder is not ready"), folder, ready, target, keys)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BatchResult(ready.name, BatchStatus.FAILED, e.localizedMessage ?: "Could not save file")
        }
    }

    private suspend fun askConflict(fileName: String, existingName: String): ConflictChoice {
        remembered?.let { return it }
        val reply = CompletableDeferred<Pair<ConflictChoice, Boolean>>()
        conflict = ConflictPrompt(fileName, existingName, reply)
        val (choice, forRemaining) = try {
            reply.await()
        } finally {
            conflict = null
        }
        if (forRemaining) remembered = choice
        return choice
    }
}

private object LocalFiles : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
}

private object LocalFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
private fun ModeRow(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun BatchCleanerDialog(model: BatchCleanerModel) {
    val files = rememberLauncherForActivityResult(LocalFiles, model::select)
    val folder = rememberLauncherForActivityResult(LocalFolder) { uri -> uri?.let(model::start) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    if (!model.visible) return
    val total = model.entries.size
    val done = model.results.size
    val saved = model.results.count { it.status == BatchStatus.SAVED }
    val setup = !model.running && !model.finished && total > 0

    model.conflict?.let { prompt ->
        var forRemaining by remember(prompt) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { model.answerConflict(ConflictChoice.SKIP, false) },
            title = { Text("File already exists") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${prompt.existingName} is already in the output folder. Overwrite it (a backup is kept in History), " +
                        "save this one under a new name, or skip it?")
                    Row(
                        Modifier.fillMaxWidth().clickable { forRemaining = !forRemaining }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = forRemaining, onCheckedChange = { forRemaining = it })
                        Text("Do this for the remaining files", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { model.answerConflict(ConflictChoice.RENAME, forRemaining) }) { Text("Rename") }
                    TextButton(onClick = { model.answerConflict(ConflictChoice.OVERWRITE, forRemaining) }) { Text("Overwrite") }
                }
            },
            dismissButton = { TextButton(onClick = { model.answerConflict(ConflictChoice.SKIP, forRemaining) }) { Text("Skip") } }
        )
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Overwrite $total original${if (total == 1) "" else "s"}?") },
            text = { Text("Each selected file is replaced by its cleaned version. MetaVault stores a backup of every file first, and you can restore them from History. Files Android will not let MetaVault change are left as they are.") },
            confirmButton = { TextButton(onClick = { confirmOverwrite = false; model.start(null) }) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") } }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!model.running) model.visible = false },
        title = { Text("Batch cleaner") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(when {
                        model.running -> "Processing file ${minOf(done + 1, total)} of $total"
                        model.finished -> "$saved saved · ${model.results.count { it.status == BatchStatus.SKIPPED }} skipped · " +
                            "${model.results.count { it.status == BatchStatus.FAILED }} failed · ${total - done} unprocessed"
                        total > 0 -> "$total selected. Choose how to save the cleaned files."
                        else -> "Choose local JPG, PNG, WebP, MP3 or Office files. Copies are checked before they are saved."
                    })
                }
                if (setup) {
                    item {
                        Column {
                            ModeRow(model.mode == BatchSaveMode.FOLDER, "Save copies in a folder",
                                "Originals stay untouched.") { model.mode = BatchSaveMode.FOLDER }
                            ModeRow(model.mode == BatchSaveMode.OVERWRITE_ORIGINALS, "Overwrite the originals",
                                "Each file is replaced. A backup is kept in History.") { model.mode = BatchSaveMode.OVERWRITE_ORIGINALS }
                        }
                    }
                    if (model.mode == BatchSaveMode.FOLDER) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = model::addCleanToAll) { Text("Add _clean to all") }
                                TextButton(onClick = model::useOriginalNames) { Text("Use original names") }
                            }
                        }
                        items(model.entries, key = { it.uri.toString() }) { entry ->
                            OutlinedTextField(
                                value = model.bases[entry.uri] ?: "",
                                onValueChange = { model.bases[entry.uri] = it },
                                label = { Text(entry.name) },
                                suffix = { if (entry.ext.isNotEmpty()) Text("." + entry.ext) },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        items(model.entries, key = { it.uri.toString() }) { entry -> Text(entry.name, style = MaterialTheme.typography.bodySmall) }
                    }
                    item { TextButton(onClick = { files.launch(arrayOf("*/*")) }) { Text("Change selection") } }
                }
                items(model.results) { result ->
                    Column {
                        Text(result.name, style = MaterialTheme.typography.titleSmall)
                        val status = when (result.status) {
                            BatchStatus.SAVED -> "Saved and verified"
                            BatchStatus.SKIPPED -> "Skipped"
                            BatchStatus.FAILED -> "Failed"
                        }
                        Text("$status · ${result.detail}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            when {
                model.running -> TextButton(enabled = !model.stopRequested, onClick = model::stop) {
                    Text(if (model.stopRequested) "Stopping…" else "Stop after this file")
                }
                model.finished || total == 0 -> TextButton(onClick = { files.launch(arrayOf("*/*")) }) {
                    Text(if (model.finished) "Choose another batch" else "Choose files")
                }
                model.mode == BatchSaveMode.OVERWRITE_ORIGINALS -> TextButton(onClick = { confirmOverwrite = true }) { Text("Overwrite originals") }
                else -> TextButton(onClick = { folder.launch(null) }) { Text("Choose output folder") }
            }
        },
        dismissButton = { if (!model.running) TextButton(onClick = { model.visible = false }) { Text("Close") } }
    )
}
