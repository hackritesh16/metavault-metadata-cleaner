package com.metavault.cleaner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
private fun ToolChoice(label: String,choices: List<String>,selected: String,onChange:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AuroraOutlinedButton(onClick={expanded=true}) { Text("$label · $selected ▾") }
        DropdownMenu(expanded,{expanded=false}) {
            choices.forEach { value -> DropdownMenuItem(text={Text(value)},onClick={expanded=false;onChange(value)}) }
        }
    }
}

@Composable
private fun NumberField(label: String,value: String,change:(String)->Unit,decimal: Boolean=false) {
    OutlinedTextField(value,change,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth(),
        keyboardOptions=KeyboardOptions(keyboardType=if(decimal) KeyboardType.Decimal else KeyboardType.Number))
}

@Composable
internal fun FileToolsDialog(model: FileToolsSession) {
    if(!model.visible) return
    val files=rememberLauncherForActivityResult(LocalFiles) { model.select(it) }
    val folder=rememberLauncherForActivityResult(LocalFolder) { if(it!=null) model.save(it) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Overwrite originals?")},text={
        Text("Replace the remaining originals with the reviewed files. MetaVault creates verified backups first. You can restore them in History.")
    },confirmButton={AuroraTextButton(onClick={confirm=false;model.save(null)}){Text("Overwrite",color=Warn)}},
        dismissButton={AuroraTextButton(onClick={confirm=false}){Text("Cancel")}})

    var resize by rememberSaveable { mutableStateOf(Resize.PERCENT) }
    var percent by rememberSaveable { mutableStateOf("100") }
    var width by rememberSaveable { mutableStateOf("1280") }
    var height by rememberSaveable { mutableStateOf("720") }
    var lock by rememberSaveable { mutableStateOf(true) }
    var resolution by rememberSaveable { mutableIntStateOf(720) }
    var mode by rememberSaveable { mutableStateOf("Medium") }
    var quality by rememberSaveable { mutableIntStateOf(82) }
    var target by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("KB") }
    var image by rememberSaveable { mutableStateOf(Output.JPG) }
    var video by rememberSaveable { mutableStateOf(Output.MP4) }
    var pdf by rememberSaveable { mutableStateOf(Output.PDF) }
    var metadata by rememberSaveable { mutableStateOf(false) }
    val optional by produceState<Set<Output>>(emptySet()) { value=withContext(Dispatchers.IO) { runCatching { optionalImageEncoders() }.getOrDefault(emptySet()) } }
    val targetValue=target.trim().toDoubleOrNull()?.times(if(unit=="KB") 1024 else 1048576)?.takeIf { it.isFinite() && it>=1 && it<=TRANSFORM_LIMIT }?.toLong()
    val groups=model.sources.map { it.family }.toSet()
    val visual=groups.any { it==FileFamily.IMAGE || it==FileFamily.VIDEO } && !metadata
    val valid=(!visual || when(resize) {
        Resize.PERCENT -> percent.toIntOrNull() in 1..400
        Resize.PIXELS -> width.toIntOrNull() in 1..8192 && (lock || height.toIntOrNull() in 1..8192)
        Resize.RESOLUTION -> true
    }) && (target.isBlank() || targetValue!=null || metadata)
    val plan=TransformPlan(resize,percent.toIntOrNull() ?: 100,width.toIntOrNull() ?: 1280,height.toIntOrNull() ?: 720,
        lock,resolution,quality,if(metadata) null else targetValue,image,video,pdf,metadata)
    val remaining=model.results.filter { it.source.uri !in model.saved }
    Dialog(onDismissRequest={if(!model.busy) model.close()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(color=Bg,contentColor=TextMain) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row { Text(if(model.reviewing) "Save options" else "File tools",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    AuroraTextButton(onClick={if(model.busy) model.visible=false else model.close()}){Text(if(model.busy) "Hide" else "Close")} }
                if(model.message.isNotBlank()) Text(model.message,style=MaterialTheme.typography.bodySmall,color=Muted)
                if(model.busy) {
                    LinearProgressIndicator(progress={model.progress},modifier=Modifier.fillMaxWidth())
                    AuroraTextButton(onClick=model::cancel){Text("Cancel operation")}
                }
                LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(!model.reviewing && !model.busy) {
                        item { AuroraOutlinedButton(onClick={files.launch(arrayOf("*/*"))}){Text(if(model.sources.isEmpty()) "Choose files" else "Change selection")} }
                        if(model.sources.isNotEmpty()) item { AuroraGlassCard {
                            ToolChoice("Operation",listOf("Resize or convert","Remove metadata"),if(metadata) "Remove metadata" else "Resize or convert"){metadata=it=="Remove metadata"}
                            if(visual) {
                                ToolChoice("Resize",Resize.entries.map { it.label },resize.label){v->resize=Resize.entries.first{it.label==v}}
                                when(resize) {
                                    Resize.PERCENT -> {
                                        NumberField("Scale (%) · 1–400",percent,{percent=it})
                                        Slider(value=(percent.toFloatOrNull() ?: 100f).coerceIn(1f,400f),onValueChange={percent=it.roundToInt().toString()},valueRange=1f..400f)
                                    }
                                    Resize.PIXELS -> {
                                        NumberField("Width (px)",width,{width=it})
                                        Row { Checkbox(lock,{lock=it});Text("Keep aspect ratio") }
                                        if(!lock) NumberField("Height (px)",height,{height=it})
                                    }
                                    Resize.RESOLUTION -> ToolChoice("Short edge",listOf("480p","720p","1080p","4K (2160p)"),
                                        if(resolution==2160) "4K (2160p)" else "${resolution}p"){resolution=it.removeSuffix("p").toIntOrNull() ?: 2160}
                                }
                                ToolChoice("Compression",listOf("Low","Medium","High","Custom"),mode){
                                    mode=it;quality=when(it){"Low"->94;"Medium"->82;"High"->58;else->quality}
                                }
                                if(mode=="Custom") { Text("Quality · $quality");Slider(quality.toFloat(),{quality=it.roundToInt()},valueRange=35f..100f) }
                            }
                            if(!metadata && FileFamily.IMAGE in groups) {
                                val formats=listOf(Output.JPG,Output.JPEG,Output.PNG,Output.WEBP)+optional+Output.PDF
                                ToolChoice("Image output",formats.map { it.name },image.name){image=Output.valueOf(it)}
                                Text("Still images only. JPEG, HEIC, AVIF and PDF flatten transparency on white. HEIC and AVIF require a compatible device encoder.",style=MaterialTheme.typography.bodySmall)
                            }
                            if(!metadata && FileFamily.VIDEO in groups) {
                                ToolChoice("Video output",listOf("MP4","WEBM","MKV"),video.name){video=Output.valueOf(it)}
                                Text("Exports the first video and audio tracks in SDR. Subtitles and additional tracks are omitted.",style=MaterialTheme.typography.bodySmall)
                            }
                            if(!metadata && FileFamily.PDF in groups) ToolChoice("PDF output",listOf("PDF","TXT"),pdf.name){pdf=Output.valueOf(it)}
                            if(!metadata && FileFamily.OFFICE in groups) Text("Office documents use lossless package compression and keep their format.",style=MaterialTheme.typography.bodySmall)
                            if(!metadata) {
                                NumberField("Target size per file (optional)",target,{target=it},true)
                                ToolChoice("Unit",listOf("KB","MB"),unit){unit=it}
                                if(target.isNotBlank() && targetValue==null) Text("Enter a positive size up to 512 MB.",color=Warn)
                                model.sources.firstOrNull()?.let { ImageSizeEstimate(it,plan) }
                            }
                        } }
                    }
                    if(!model.reviewing) items(model.sources,key={it.uri.toString()}) { item ->
                        Text("${item.name} · ${item.size?.let(::sizeText) ?: "Size unknown"}",style=MaterialTheme.typography.bodyMedium)
                        if(!metadata && item.family==FileFamily.OTHER) Text("Unsupported conversion format",color=Warn)
                        model.errors[item.uri]?.let { Text(it,color=Warn) }
                    }
                    if(!model.reviewing && model.sources.isNotEmpty() && !model.busy) item {
                        AuroraOutlinedButton(onClick={model.prepare(plan)},enabled=valid){Text("Prepare preview")}
                    }
                    if(model.reviewing) {
                        items(model.results,key={it.source.uri.toString()}) { result ->
                            var preview by remember(result) { mutableStateOf(model.results.size==1) }
                            AuroraGlassCard {
                                Text(result.source.name,style=MaterialTheme.typography.titleMedium)
                                Text(comparison(result.originalBytes,result.file.length()))
                                Text(result.detail,style=MaterialTheme.typography.bodySmall,color=Muted)
                                if(result.target!=null && result.file.length()>result.target) Text("Above target. A smaller result needs lower quality, smaller dimensions or another format.",color=Warn)
                                AuroraTextButton(onClick={preview=!preview}){Text(if(preview) "Hide comparison" else "Preview comparison")}
                                if(preview) ToolComparison(result)
                                val done=model.saved[result.source.uri]
                                if(done!=null) Text("Saved · $done",color=Clean)
                                else ExportNameField(model.names[result.source.uri].orEmpty(),{model.names[result.source.uri]=it},result.ext,"Output name",!model.busy)
                            }
                        }
                        items(model.errors.entries.toList()) { (uri,error) -> Text("${model.sources.find { it.uri==uri }?.name}: $error",color=Warn) }
                        if(!model.busy) item { AuroraGlassCard {
                            if(remaining.isNotEmpty()) {
                                AuroraOutlinedButton(onClick={folder.launch(null)},enabled=remaining.all { validStem(model.names[it.source.uri].orEmpty()) }) {
                                    Text("Save as new in folder (${remaining.size})")
                                }
                                Text("Original files are preserved. The folder provider assigns available names.",style=MaterialTheme.typography.bodySmall)
                                AuroraTextButton(onClick={confirm=true},enabled=remaining.all { it.overwriteAllowed }) { Text("Overwrite originals…",color=Warn) }
                                if(remaining.any { !it.overwriteAllowed }) Text("Replacement requires the same format and files within the backup limit.",style=MaterialTheme.typography.bodySmall)
                            }
                            AuroraTextButton(onClick=model::configure){Text("Change output format or settings")}
                        } }
                    }
                }
            }
        }
    }
}
