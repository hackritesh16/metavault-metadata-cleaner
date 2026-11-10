package com.metavault.cleaner

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Amber = Color(0xFFFFB84D)

@Composable
internal fun ModeSelector(mode: Mode, onMode: (Mode) -> Unit) {
    GlassCard {
        Text("WHAT DO YOU WANT TO DO?", color = Mint, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(Mode.VIEW to "View", Mode.EDIT to "Edit", Mode.REMOVE to "Remove all").forEach { (m, label) ->
                FilterChip(selected = mode == m, onClick = { onMode(m) }, label = { Text(label) })
            }
        }
    }
}

@Composable
internal fun MetadataViewer(scan: MetadataScan?, scanning: Boolean) {
    GlassCard(accent = Aqua) {
        Text("METADATA IN THIS FILE", color = Aqua, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        when {
            scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("Reading metadata on this device…", color = Muted, fontSize = 12.sp)
            }
            scan == null -> Text("Choose a file to see its metadata.", color = Muted, fontSize = 12.sp)
            scan.format == "Unsupported" -> Text(scan.technical.firstOrNull() ?: "This format cannot be read yet.", color = Muted, fontSize = 12.sp)
            else -> {
                Text("${scan.fields.size} field(s) found in this ${scan.format} file", fontWeight = FontWeight.Bold)
                if (scan.hasGps) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Contains GPS location — it can reveal where this was taken.", color = Amber, fontSize = 11.sp)
                }
                if (scan.fields.isEmpty()) Text("No metadata found. This file is already clean.", color = Mint, fontSize = 12.sp)
                scan.fields.take(200).groupBy { it.group }.forEach { (group, list) ->
                    Text(group.uppercase(), color = Aqua, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                    list.forEach { f ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(f.name, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(0.4f))
                            Text(f.value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f))
                        }
                    }
                }
                if (scan.fields.size > 200) Text("…and ${scan.fields.size - 200} more", color = Muted, fontSize = 10.sp)
                if (scan.technical.isNotEmpty()) Text("Technical data kept for correct display: ${scan.technical.joinToString()}.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun MetadataEditor(scan: MetadataScan?, edits: SnapshotStateMap<String, String>) {
    GlassCard(accent = Violet) {
        Text("EDIT METADATA", color = Violet, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        val keys = if (scan == null) emptyList() else MetadataEdits.keys(scan.format)
        if (keys.isEmpty()) {
            Text("Editing is available for JPG and PNG files. Choose one first.", color = Muted, fontSize = 12.sp)
        } else {
            Text(
                "Only the fields below are written to the new copy. Everything else, including GPS location, is removed. Leave a field empty to remove it.",
                color = Muted, fontSize = 10.sp
            )
            keys.forEach { k ->
                OutlinedTextField(
                    value = edits[k] ?: "", onValueChange = { edits[k] = it.take(MetadataEdits.MAX_LEN) },
                    label = { Text(k) }, modifier = Modifier.fillMaxWidth(),
                    singleLine = k != "ImageDescription" && k != "Description" && k != "Comment"
                )
            }
            if (scan?.format == "JPEG") Text("DateTime format: 2024:05:17 14:30:00", color = Muted, fontSize = 10.sp)
        }
    }
}

private fun kb(n: Int) = String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
private fun shortHash(h: String) = h.take(12) + "…"

@Composable
private fun ReportLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp)
    }
}

@Composable
internal fun ProcessingComplete(report: CleanReport, originalName: String, outName: String) {
    GlassCard(accent = Mint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Mint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Processing complete", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        val change = String.format(java.util.Locale.US, "%+.1f%%", report.sizeChangePercent)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("1", "IMAGES PROCESSED", Mint, Modifier.weight(1f))
            StatTile(change, "SIZE CHANGE", Aqua, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(if (report.lossless) "0" else "Re-encoded", "PIXELS MODIFIED", Violet, Modifier.weight(1f))
            StatTile("${report.fieldsBefore} → ${report.remaining.size}", "FIELDS BEFORE → AFTER", Mint, Modifier.weight(1f))
        }
        Text("FILE DETAILS", color = Mint, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        Text("$originalName → $outName", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("CLEANING REPORT", color = Mint, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        ReportLine(Icons.Default.CheckCircle, Mint, if (report.fieldsBefore == 0) "No metadata fields detected in the input." else "Found ${report.fieldsBefore} metadata field(s) in the input.")
        report.notes.forEach { ReportLine(Icons.Default.CheckCircle, Mint, it) }
        if (report.remaining.isEmpty()) ReportLine(Icons.Default.Verified, Mint, "Verified: output re-scanned, no metadata remaining.")
        else ReportLine(Icons.Default.Info, Aqua, "Output re-scanned: ${report.remaining.size} field(s) remain (${report.remaining.joinToString { it.name }}).")
        if (report.kept.isNotEmpty()) ReportLine(Icons.Default.Info, Aqua, "Kept for correct display: ${report.kept.joinToString()}.")
        if (!report.lossless) ReportLine(Icons.Default.Warning, Amber, "Re-encoded: pixels may differ slightly; this is not byte-lossless.")
        Text(
            "This clears stored metadata only. It does not change what the picture shows, so reverse-image search still works, and it cannot remove invisible watermarks baked into the pixels.",
            color = Muted, fontSize = 10.sp
        )
        Text(
            (if (report.hashBefore == report.hashAfter) "Hash unchanged: " else "Hash changed: ") + shortHash(report.hashBefore) + " → " + shortHash(report.hashAfter),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )
        Text("Size: ${kb(report.sizeBefore)} → ${kb(report.sizeAfter)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
internal fun FilenameCard(customName: String, onName: (String) -> Unit, usePrefix: Boolean, onPrefix: (Boolean) -> Unit, preview: String) {
    GlassCard(accent = Aqua) {
        Text("CUSTOMIZE FILENAME", color = Aqua, fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = customName, onValueChange = { onName(it.take(120)) }, singleLine = true,
            label = { Text("Custom filename (without extension)") }, modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = usePrefix, onCheckedChange = onPrefix)
            Text("Include \"processed_\" prefix", fontSize = 12.sp)
        }
        Text("PREVIEW", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Text(preview, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
