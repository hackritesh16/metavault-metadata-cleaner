package com.metavault.cleaner

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.compose.runtime.*
import java.io.File
import kotlinx.coroutines.*

/** A user-started session outlives the Activity; only prepared copies live in private cache. */
internal class FileToolsSession private constructor(private val context: Application) {
    companion object {
        @Volatile private var instance: FileToolsSession? = null

        fun get(context: Context): FileToolsSession =
            instance
                ?: synchronized(this) {
                    instance
                        ?: FileToolsSession(context.applicationContext as Application).also {
                            instance = it
                        }
                }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dir by lazy {
        File(context.cacheDir, "file-tools").apply {
            // The first access in a new process clears abandoned private scratch files.
            deleteRecursively()
            check(mkdirs() || isDirectory)
        }
    }
    var visible by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set

    var reviewing by mutableStateOf(false)
        private set

    var sources by mutableStateOf(emptyList<ToolSource>())
        private set

    val results = mutableStateListOf<ToolResult>()
    val names = mutableStateMapOf<Uri, String>()
    val saved = mutableStateMapOf<Uri, String>()
    val errors = mutableStateMapOf<Uri, String>()
    var message by mutableStateOf("")
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    var refresh by mutableIntStateOf(0)
        private set

    val history = mutableStateListOf<HistoryEntry>()
    private var job: Job? = null
    private var pending: (suspend () -> Unit)? = null

    fun close() {
        if (!busy) {
            visible = false
            reset()
        }
    }

    private fun clearResults() {
        results.forEach { if (it.file != it.source.seed) it.file.delete() }
        results.clear()
        saved.clear()
        errors.clear()
        reviewing = false
    }

    private fun reset() {
        clearResults()
        sources.forEach { it.seed?.delete() }
        sources = emptyList()
        names.clear()
        message = ""
    }

    fun configure() {
        if (!busy) {
            clearResults()
            message = ""
        }
    }

    fun open(uri: Uri?) {
        visible = true
        if (!busy && sources.isEmpty() && uri != null) select(listOf(uri))
    }

    fun select(uris: List<Uri>) {
        if (busy || uris.isEmpty()) return
        if (uris.size > 50) {
            message = "Choose up to 50 files per batch."
            return
        }
        reset()
        busy = true
        job =
            scope.launch {
                try {
                    sources =
                        withContext(Dispatchers.IO) {
                            uris.distinctBy(::documentKey).map { uri ->
                                runCatching {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                    )
                                }
                                toolSource(context, uri)
                            }
                        }
                    for (item in sources) names[item.uri] =
                        sanitizeName(item.name.substringBeforeLast('.')) + "_export"
                } catch (e: Exception) {
                    message = e.localizedMessage ?: "Could not open selection."
                } finally {
                    busy = false
                }
            }
    }

    fun reviewCleaned(uri: Uri, bytes: ByteArray, stem: String, ext: String, mime: String) {
        if (busy) return
        reset()
        visible = true
        busy = true
        job =
            scope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            val original = toolSource(context, uri)
                            val file = File.createTempFile("cleaned-", ".$ext", dir)
                            try {
                                file.writeBytes(bytes)
                                ToolResult(
                                    original.copy(seed = file, seedExt = ext),
                                    file,
                                    original.size ?: 0,
                                    ext,
                                    mime,
                                    "Prepared metadata workflow output",
                                    null,
                                    null,
                                )
                            } catch (e: Exception) {
                                file.delete()
                                throw e
                            }
                        }
                    sources = listOf(result.source)
                    results.add(result)
                    names[uri] = stem
                    reviewing = true
                } catch (e: Exception) {
                    message = e.localizedMessage ?: "Could not prepare the review."
                } finally {
                    busy = false
                }
            }
    }

    private fun background(action: suspend () -> Unit) {
        if (busy) return
        pending = action
        busy = true
        progress = 0f
        try {
            context.startForegroundService(Intent(context, FileToolsService::class.java))
        } catch (e: Exception) {
            pending = null
            busy = false
            message = "Open MetaVault and try again. ${e.localizedMessage}"
        }
    }

    fun begin(service: FileToolsService, startId: Int) {
        val work = pending
        if (work == null) {
            if (!busy) service.stopSelf(startId)
            return
        }
        pending = null
        job =
            scope.launch {
                try {
                    work()
                } catch (e: CancellationException) {
                    message = "Cancelled. Completed items are shown below."
                } catch (e: Exception) {
                    message = e.localizedMessage ?: "File operation failed."
                } finally {
                    busy = false
                    service.stopSelf(startId)
                }
            }
    }

    fun cancel() {
        if (pending != null) {
            pending = null
            busy = false
            message = "Cancelled."
        }
        job?.cancel()
    }

    fun prepare(plan: TransformPlan) {
        if (busy || sources.isEmpty()) return
        clearResults()
        background {
            try {
                for ((index, item) in sources.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    message = "Preparing ${index+1} of ${sources.size} · ${item.name}"
                    progress = index.toFloat() / sources.size
                    try {
                        val result =
                            withContext(Dispatchers.IO) {
                                require(
                                    dir.listFiles().orEmpty().sumOf { it.length() } <
                                        TRANSFORM_LIMIT * 2
                                ) {
                                    "Batch cache is full. Save a smaller batch."
                                }
                                prepareTransform(context, item, plan, dir, { ensureActive() }) {
                                    fraction ->
                                    progress = (index + fraction) / sources.size
                                }
                            }
                        results.add(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors[item.uri] = e.localizedMessage ?: "Cannot process this file."
                    }
                }
                progress = 1f
                message = "${results.size} ready · ${errors.size} failed"
            } finally {
                reviewing = results.isNotEmpty()
            }
        }
    }

    fun save(tree: Uri?) {
        val remaining = results.filter { it.source.uri !in saved }
        if (busy || remaining.isEmpty()) return
        background {
            val selected = SelectedSources(context, sources.map { it.uri })
            for ((i, result) in remaining.withIndex()) {
                currentCoroutineContext().ensureActive()
                val uri = result.source.uri
                errors.remove(uri)
                message = "Saving ${i+1} of ${remaining.size}"
                val stem = names.getValue(uri)
                try {
                    // Treat verified replacement as one transaction. Record it even if cancellation
                    // arrives during the write.
                    withContext(NonCancellable) {
                        val parentJob = job
                        val name =
                            withContext(Dispatchers.IO) {
                                saveTransform(context, tree, result, stem, selected) {
                                    parentJob?.ensureActive()
                                }
                            }
                        saved[uri] = name
                        history.add(
                            0,
                            HistoryEntry(
                                name,
                                "Verified file export · ${sizeText(result.file.length())}",
                            ),
                        )
                        refresh++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors[uri] = e.localizedMessage ?: "Could not save. Try another folder."
                }
                progress = (i + 1f) / remaining.size
            }
            message = "${saved.size} saved · ${errors.size} errors"
        }
    }
}

class FileToolsService : Service() {
    companion object {
        const val SHOW = "com.metavault.cleaner.SHOW_FILE_TOOLS"
    }

    private val session
        get() = FileToolsSession.get(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            session.cancel()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = "local_file_tools"
        manager.createNotificationChannel(
            NotificationChannel(channel, "File processing", NotificationManager.IMPORTANCE_LOW)
        )
        val flagsPI = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .setAction(SHOW)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flagsPI,
            )
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, FileToolsService::class.java).setAction("cancel"),
                flagsPI,
            )
        val notification =
            Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("MetaVault file tools")
                .setContentText("Processing files on this device")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
                .build()
        startForeground(42, notification)
        session.begin(this, startId)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        session.cancel()
        stopSelf(startId)
    }
}
