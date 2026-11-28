package com.metavault.cleaner

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Preview(val text: String, val bitmap: Bitmap? = null)

private fun preview(context: Context, uri: Uri, type: FileFamily, mime: String): Preview {
    val resolver = context.contentResolver
    return when {
        type == FileFamily.IMAGE -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 384) sample *= 2
            val image =
                resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            Preview(
                if (image == null) "Preview unavailable"
                else "${bounds.outWidth} × ${bounds.outHeight}",
                image,
            )
        }
        type == FileFamily.PDF -> {
            val fd = resolver.openFileDescriptor(uri, "r") ?: error("No PDF preview")
            fd.use {
                PdfRenderer(it).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val scale = 256f / maxOf(page.width, page.height)
                        val bmp =
                            Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        Preview("Page 1 of ${renderer.pageCount}", bmp)
                    }
                }
            }
        }
        type == FileFamily.VIDEO -> {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") retriever.setDataSource(uri.path)
                else retriever.setDataSource(context, uri)
                val w =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 1
                val h =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 1
                val scale = 256f / maxOf(w, h, 1)
                Preview(
                    "$w × $h",
                    if (android.os.Build.VERSION.SDK_INT >= 27)
                        retriever.getScaledFrameAtTime(
                            0,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1),
                        )
                    else if (w.toLong() * h <= 1_000_000) retriever.getFrameAtTime(0) else null,
                )
            } finally {
                retriever.release()
            }
        }
        mime == "text/plain" ->
            Preview(
                resolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.use {
                        val chars = CharArray(180)
                        val n = it.read(chars)
                        if (n > 0) String(chars, 0, n) else ""
                    }
                    .orEmpty()
            )
        type == FileFamily.OFFICE ->
            Preview(
                resolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(stream).use { zip -> zip.nextEntry?.name }
                } ?: "Office package"
            )
        else -> Preview("Binary file")
    }
}

@Composable
internal fun ToolComparison(result: ToolResult) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Original" to result.source.uri, "Final" to Uri.fromFile(result.file))
            .forEachIndexed { i, (label, uri) ->
                val m = if (i == 0) result.source.mime else result.mime
                val f =
                    if (i == 0) family(result.source.name, result.source.mime)
                    else family("file." + result.ext, m)
                var data by remember(uri, m) { mutableStateOf<Preview?>(null) }
                LaunchedEffect(uri, m) {
                    data =
                        withContext(Dispatchers.IO) {
                            try {
                                preview(context, uri, f, m)
                            } catch (_: Exception) {
                                Preview("Preview unavailable")
                            }
                        }
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    data?.bitmap?.let {
                        Image(it.asImageBitmap(), label, Modifier.fillMaxWidth().height(94.dp))
                    }
                    Text(data?.text ?: "Loading…", style = MaterialTheme.typography.bodySmall)
                }
            }
    }
}

@Composable
internal fun FileSizeEstimate(source: ToolSource, plan: TransformPlan) {
    if (source.family == FileFamily.VIDEO && !plan.metadataOnly) {
        val context = LocalContext.current
        var estimate by remember(source, plan) { mutableStateOf<Long?>(null) }
        LaunchedEffect(source, plan) {
            delay(350)
            estimate =
                withContext(Dispatchers.IO) {
                    runCatching {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, source.uri)
                                fun field(key: Int) = retriever.extractMetadata(key)
                                val duration =
                                    field(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                        ?.toDoubleOrNull()
                                        ?.div(1000) ?: return@runCatching null
                                val w =
                                    field(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                        ?.toIntOrNull() ?: return@runCatching null
                                val h =
                                    field(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                        ?.toIntOrNull() ?: return@runCatching null
                                val swapped =
                                    (field(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                                        ?.toIntOrNull() ?: 0) % 180 != 0
                                val (width, height) =
                                    plan.dimensions(
                                        if (swapped) h else w,
                                        if (swapped) w else h,
                                        true,
                                    )
                                val audio =
                                    if (
                                        field(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) ==
                                            "yes"
                                    )
                                        96000
                                    else 0
                                val fps =
                                    field(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                                        ?.toDoubleOrNull() ?: 30.0
                                val range = videoRates(width, height, fps, plan.quality)
                                ((plannedRate(range, duration, audio, plan.target) + audio) *
                                        duration / 8.0)
                                    .toLong()
                            } finally {
                                retriever.release()
                            }
                        }
                        .getOrNull()
                }
        }
        estimate?.let {
            Text(
                "Estimated video size · ${sizeText(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        return
    }
    if (
        source.family != FileFamily.IMAGE ||
            plan.image !in listOf(Output.JPG, Output.JPEG, Output.PNG, Output.WEBP) ||
            plan.metadataOnly
    )
        return
    val context = LocalContext.current
    var bytes by remember(source, plan) { mutableStateOf<Long?>(null) }
    LaunchedEffect(source, plan) {
        delay(350)
        bytes =
            withContext(Dispatchers.IO) {
                runCatching {
                        val uri = source.seed?.let(Uri::fromFile) ?: source.uri
                        val sample =
                            preview(context, uri, FileFamily.IMAGE, source.mime).bitmap
                                ?: return@runCatching null
                        try {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it, null, bounds)
                            }
                            val (w, h) = plan.dimensions(bounds.outWidth, bounds.outHeight)
                            val stream = ByteArrayOutputStream()
                            val format =
                                when (plan.image) {
                                    Output.PNG -> Bitmap.CompressFormat.PNG
                                    Output.WEBP -> Bitmap.CompressFormat.WEBP
                                    else -> Bitmap.CompressFormat.JPEG
                                }
                            if (sample.compress(format, plan.quality, stream))
                                (stream.size().toDouble() * w * h / sample.width / sample.height)
                                    .toLong()
                            else null
                        } finally {
                            sample.recycle()
                        }
                    }
                    .getOrNull()
            }
    }
    bytes?.let {
        Text(
            "Sample estimate at this quality · ${sizeText(it)}",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}
