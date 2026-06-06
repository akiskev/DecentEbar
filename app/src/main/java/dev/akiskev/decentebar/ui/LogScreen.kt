package dev.akiskev.decentebar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotSample
import dev.akiskev.decentebar.storage.ShotHtmlExporter
import dev.akiskev.decentebar.storage.ShotLogCodec
import dev.akiskev.decentebar.storage.ShotVideoExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LogScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var pendingLogJson by remember { mutableStateOf<String?>(null) }
    var pendingLogHtml by remember { mutableStateOf<String?>(null) }
    var pendingFilenameBase by remember { mutableStateOf("") }

    // Save dialog: required shot metadata, pre-filled with the last entry for the session.
    var showSaveDialog by remember { mutableStateOf(false) }
    var mdBeans by rememberSaveable { mutableStateOf("") }
    var mdGrind by rememberSaveable { mutableStateOf("") }
    var mdDose by rememberSaveable { mutableStateOf("") }
    var mdNotes by rememberSaveable { mutableStateOf("") }

    val saveHtmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val html = pendingLogHtml ?: return@rememberLauncherForActivityResult
        pendingLogHtml = null
        if (uri == null) {
            viewModel.setLogMessage("Saved JSON (HTML skipped)")
            return@rememberLauncherForActivityResult
        }
        if (writeJsonToUri(context, uri, html)) {
            viewModel.setLogMessage("Saved JSON + HTML report")
        } else {
            viewModel.setLogMessage("Saved JSON, HTML write failed")
        }
    }

    val saveLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingLogJson ?: return@rememberLauncherForActivityResult
        pendingLogJson = null
        if (uri == null) { pendingLogHtml = null; return@rememberLauncherForActivityResult }
        if (!writeJsonToUri(context, uri, json)) {
            viewModel.setLogMessage("Save failed")
            pendingLogHtml = null
            return@rememberLauncherForActivityResult
        }
        val html = pendingLogHtml
        if (html != null) {
            saveHtmlLauncher.launch("$pendingFilenameBase.html")
        } else {
            viewModel.setLogMessage("Saved log to file")
        }
    }

    var pendingVideoFormat by remember { mutableStateOf<ShotVideoExporter.Format?>(null) }

    val saveVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val fmt = pendingVideoFormat ?: return@rememberLauncherForActivityResult
        pendingVideoFormat = null
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.exportShotVideo(uri, fmt)
    }

    var pendingImportedLogJson by remember { mutableStateOf<String?>(null) }
    var pendingImportedLogHtml by remember { mutableStateOf<String?>(null) }
    var pendingImportedFilenameBase by remember { mutableStateOf("") }

    val saveImportedHtmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val html = pendingImportedLogHtml ?: return@rememberLauncherForActivityResult
        pendingImportedLogHtml = null
        if (uri == null) {
            viewModel.setLogMessage("Saved JSON (HTML skipped)")
            return@rememberLauncherForActivityResult
        }
        if (writeJsonToUri(context, uri, html)) {
            viewModel.setLogMessage("Saved JSON + HTML report")
        } else {
            viewModel.setLogMessage("Saved JSON, HTML write failed")
        }
    }

    val saveImportedJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingImportedLogJson ?: return@rememberLauncherForActivityResult
        pendingImportedLogJson = null
        if (uri == null) { pendingImportedLogHtml = null; return@rememberLauncherForActivityResult }
        if (!writeJsonToUri(context, uri, json)) {
            viewModel.setLogMessage("Save failed")
            pendingImportedLogHtml = null
            return@rememberLauncherForActivityResult
        }
        val html = pendingImportedLogHtml
        if (html != null) {
            saveImportedHtmlLauncher.launch("$pendingImportedFilenameBase.html")
        } else {
            viewModel.setLogMessage("Saved imported log")
        }
    }

    var pendingImportedVideoFormat by remember { mutableStateOf<ShotVideoExporter.Format?>(null) }

    val saveImportedVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val fmt = pendingImportedVideoFormat ?: return@rememberLauncherForActivityResult
        val log = state.importedShotLog ?: return@rememberLauncherForActivityResult
        pendingImportedVideoFormat = null
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.exportShotVideo(uri, fmt, log)
    }

    val importLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importShotLogFromUri(uri)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(0.5f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Panel("Shot Log") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::exportShotLog) { Text("Export Log") }
                    OutlinedButton(onClick = {
                        if (state.samples.isEmpty() && state.events.isEmpty()) {
                            viewModel.setLogMessage("No shot data to save")
                        } else {
                            showSaveDialog = true
                        }
                    }) { Text("Save to File") }
                    OutlinedButton(onClick = viewModel::resetShotLog) { Text("Clear") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        importLogLauncher.launch(arrayOf("application/json", "text/html", "*/*"))
                    }) { Text("Load Log") }
                }
                Spacer(Modifier.height(4.dp))
                VideoExportRow(
                    progress = state.videoExportProgress,
                    hasData = state.samples.isNotEmpty(),
                    onExport = { fmt ->
                        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val base = "${sanitizeFilename(state.selectedProfile.name)}-$ts"
                        pendingVideoFormat = fmt
                        saveVideoLauncher.launch("$base.mp4")
                    }
                )
                Text("${state.samples.size} samples | ${state.events.size} events")
                MessageLine(state.logMessage)
                if (state.importShotLogMessage.isNotBlank()) {
                    MessageLine(state.importShotLogMessage)
                }
            }
            Panel("Events", modifier = Modifier.weight(1f), fillContent = true) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.events.takeLast(80).reversed()) { event ->
                        EventRow(event)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.weight(0.5f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val importedLog = state.importedShotLog
            if (importedLog != null) {
                val durationSec = if (importedLog.startedAtMs != null && importedLog.stoppedAtMs != null) {
                    " · ${((importedLog.stoppedAtMs - importedLog.startedAtMs) / 1000.0).format(1)}s"
                } else ""
                Panel("Imported Shot Log") {
                    Text(
                        "${importedLog.profileName} · ${importedLog.samples.size} samples · ${importedLog.events.size} events$durationSec",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val json = ShotLogCodec.encode(importedLog)
                            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            val base = "${sanitizeFilename(importedLog.profileName)}-$ts"
                            pendingImportedLogJson = json
                            pendingImportedLogHtml = ShotHtmlExporter.export(importedLog)
                            pendingImportedFilenameBase = base
                            saveImportedJsonLauncher.launch("$base.json")
                        }) { Text("Save to File") }
                        OutlinedButton(onClick = viewModel::clearImportedShotLog) { Text("Clear") }
                    }
                    Spacer(Modifier.height(4.dp))
                    VideoExportRow(
                        progress = state.videoExportProgress,
                        hasData = true,
                        onExport = { fmt ->
                            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            val base = "${sanitizeFilename(importedLog.profileName)}-$ts"
                            pendingImportedVideoFormat = fmt
                            saveImportedVideoLauncher.launch("$base.mp4")
                        }
                    )
                }
            }
            Panel("Recent Samples", modifier = Modifier.weight(1f), fillContent = true) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.samples.takeLast(40).reversed()) { sample ->
                        SampleRow(sample)
                    }
                }
            }
            Panel("Export JSON") {
                OutlinedTextField(
                    value = state.exportedLogJson,
                    onValueChange = {},
                    label = { Text("Shot log JSON") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showSaveDialog) {
        val dose = mdDose.toDoubleOrNull()
        val canSave = mdBeans.isNotBlank() && mdGrind.isNotBlank() && dose != null && dose > 0
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Shot details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Required before saving, so the log is useful for analysis.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = mdBeans, onValueChange = { mdBeans = it },
                        label = { Text("Beans *") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mdGrind, onValueChange = { mdGrind = it },
                        label = { Text("Grind setting *") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mdDose, onValueChange = { mdDose = it },
                        label = { Text("Dose (g) *") }, singleLine = true,
                        isError = mdDose.isNotBlank() && dose == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mdNotes, onValueChange = { mdNotes = it },
                        label = { Text("Notes (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val metadata = ShotMetadata(mdBeans.trim(), mdGrind.trim(), dose, mdNotes.trim())
                        val log = viewModel.currentShotLog(metadata)
                        showSaveDialog = false
                        if (log == null) {
                            viewModel.setLogMessage("No shot data to save")
                            return@TextButton
                        }
                        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val base = "${sanitizeFilename(state.selectedProfile.name)}-$ts"
                        pendingLogJson = ShotLogCodec.encode(log)
                        pendingLogHtml = ShotHtmlExporter.export(log)
                        pendingFilenameBase = base
                        saveLogLauncher.launch("$base.json")
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun VideoExportRow(
    progress: Float?,
    hasData: Boolean,
    onExport: (ShotVideoExporter.Format) -> Unit
) {
    var selectedFormat by remember { mutableStateOf(ShotVideoExporter.Format.LANDSCAPE) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShotVideoExporter.Format.entries.forEach { fmt ->
                FilterChip(
                    selected = selectedFormat == fmt,
                    onClick = { selectedFormat = fmt },
                    label = { Text(fmt.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { onExport(selectedFormat) },
                enabled = hasData && progress == null
            ) {
                Text("Save Video")
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f)
                )
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EventRow(event: ShotEvent) {
    Text(
        "${event.timeMs}ms | ${event.type.name} | ${event.message}",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SampleRow(sample: ShotSample) {
    Text(
        "${sample.timeMs}ms | ${sample.stageName} | ${sample.weightG.format(1)} g | ${sample.flowGps.format(2)} g/s | ${sample.commandedPressureBar?.format(2) ?: "--"} bar",
        style = MaterialTheme.typography.bodySmall
    )
}
