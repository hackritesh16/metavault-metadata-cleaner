package com.metavault.cleaner

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BatchCleanerModel(application: Application) : AndroidViewModel(application) {
    var visible by mutableStateOf(false)
    var running by mutableStateOf(false)
        private set
    var stopRequested by mutableStateOf(false)
        private set
    var selected by mutableStateOf(emptyList<Uri>())
        private set
    val results = mutableStateListOf<BatchResult>()
    var finished by mutableStateOf(false)
        private set
    val history = mutableStateOf(emptyList<HistoryEntry>())

    fun select(uris: List<Uri>) {
        if (running || uris.isEmpty()) return
        selected = uris.distinct()
        results.clear()
        finished = false
        stopRequested = false
    }

    fun stop() { stopRequested = true }

    fun start(tree: Uri) {
        if (running || finished || selected.isEmpty()) return
        running = true
        val sources = selected
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val keys = sources.map(::documentKey).toSet()
                runBatch(sources, shouldStop = { stopRequested },
                    process = { uri -> withContext(Dispatchers.IO) { processBatchDocument(context, uri, tree, keys) } },
                    fallbackName = { "File ${results.size + 1}" },
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
            }
        }
    }
}

private object LocalFiles : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private object LocalFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@Composable
internal fun BatchCleanerDialog(model: BatchCleanerModel) {
    val files = rememberLauncherForActivityResult(LocalFiles, model::select)
    val folder = rememberLauncherForActivityResult(LocalFolder) { uri -> uri?.let(model::start) }
    if (!model.visible) return
    val total = model.selected.size
    val done = model.results.size
    val saved = model.results.count { it.status == BatchStatus.SAVED }
    AlertDialog(
        onDismissRequest = { if (!model.running) model.visible = false },
        title = { Text("Batch cleaner") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(when {
                        model.running -> "Processing file ${minOf(done + 1, total)} of $total"
                        model.finished -> "$saved saved · ${model.results.count { it.status == BatchStatus.SKIPPED }} skipped · " +
                            "${model.results.count { it.status == BatchStatus.FAILED }} failed · ${total - done} unprocessed"
                        total > 0 -> "$total selected. Choose a folder for the verified copies."
                        else -> "Choose local JPG, PNG, WebP, MP3 or Office files. Copies are checked and saved in a folder you choose. Originals stay in place."
                    })
                }
                if (!model.running && !model.finished && total > 0) {
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
                else -> TextButton(onClick = { folder.launch(null) }) { Text("Choose output folder") }
            }
        },
        dismissButton = { if (!model.running) TextButton(onClick = { model.visible = false }) { Text("Close") } }
    )
}
