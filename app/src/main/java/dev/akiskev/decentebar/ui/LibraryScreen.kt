package dev.akiskev.decentebar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.akiskev.decentebar.model.ShotLibraryEntry
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.storage.ShotCompareRenderer
import dev.akiskev.decentebar.storage.ShotFrameRenderer
import dev.akiskev.decentebar.storage.ShotHtmlExporter
import dev.akiskev.decentebar.storage.ShotLogCodec
import dev.akiskev.decentebar.storage.ShotRenderTiming
import dev.akiskev.decentebar.storage.ShotShareFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LibraryScreen(state: MainUiState, viewModel: MainViewModel) {
    var compareSelection by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var compareMode by rememberSaveable { mutableStateOf(false) }

    val filtered = state.libraryEntries
    val compareReady = LibraryCompareSelection.isReady(compareSelection)

    LaunchedEffect(filtered.map { it.shotId }, state.selectedLibraryShotId) {
        if (state.selectedLibraryShotId == null && filtered.isNotEmpty()) {
            viewModel.selectLibraryShot(filtered.first().shotId)
        }
    }

    LaunchedEffect(state.libraryEntries.map { it.shotId }) {
        val pruned = LibraryCompareSelection.prune(
            selected = compareSelection,
            availableShotIds = state.libraryEntries.map { it.shotId }
        )
        if (pruned != compareSelection) {
            compareSelection = pruned
            if (!LibraryCompareSelection.isReady(pruned)) {
                compareMode = false
                viewModel.clearLibraryCompareShots()
            }
        }
    }

    LaunchedEffect(compareMode, compareSelection) {
        if (compareMode && LibraryCompareSelection.isReady(compareSelection)) {
            viewModel.loadLibraryCompareShots(compareSelection)
        } else if (!compareMode) {
            viewModel.clearLibraryCompareShots()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Panel(
            title = "Shot Library",
            modifier = Modifier.weight(0.42f).fillMaxHeight(),
            fillContent = true
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${filtered.size} shots",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${compareSelection.size}/2",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            compareSelection = emptyList()
                            compareMode = false
                            viewModel.clearLibraryCompareShots()
                        },
                        enabled = compareSelection.isNotEmpty()
                    ) {
                        Text("Clear")
                    }
                    Button(
                        onClick = { compareMode = true },
                        enabled = compareReady
                    ) {
                        Text("Compare")
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.shotId }) { entry ->
                        val compareSelected = entry.shotId in compareSelection
                        LibraryEntryRow(
                            entry = entry,
                            selected = entry.shotId == state.selectedLibraryShotId,
                            compareSelected = compareSelected,
                            compareEnabled = compareSelected ||
                                compareSelection.size < LibraryCompareSelection.MAX_SELECTED_SHOTS,
                            onCompareToggle = {
                                compareSelection = LibraryCompareSelection.toggle(compareSelection, entry.shotId)
                            },
                            onClick = {
                                compareMode = false
                                viewModel.selectLibraryShot(entry.shotId)
                            }
                        )
                    }
                }
            }
        }

        Panel(
            title = if (compareMode) "Shot Compare" else "Shot Detail",
            modifier = Modifier.weight(0.58f).fillMaxHeight(),
            fillContent = true,
            showTitle = compareMode
        ) {
            if (compareMode) {
                LibraryCompareDetail(
                    entries = state.libraryEntries,
                    selectedShotIds = compareSelection,
                    compareShots = state.libraryCompareShots,
                    viewModel = viewModel
                )
            } else {
                val log = state.selectedLibraryShot
                val entry = state.libraryEntries.firstOrNull { it.shotId == state.selectedLibraryShotId }
                when {
                    state.libraryEntries.isEmpty() -> EmptyLibraryDetail()
                    log == null || entry == null -> Text("Select a shot.", style = MaterialTheme.typography.bodyMedium)
                    else -> LibraryShotDetail(entry, log, viewModel)
                }
            }
        }
    }
}

@Composable
private fun LibraryEntryRow(
    entry: ShotLibraryEntry,
    selected: Boolean,
    compareSelected: Boolean,
    compareEnabled: Boolean,
    onCompareToggle: () -> Unit,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = when {
            selected -> colors.primaryContainer
            compareSelected -> colors.secondaryContainer
            else -> colors.surfaceContainerHighest
        },
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = compareSelected,
                onCheckedChange = { onCompareToggle() },
                enabled = compareEnabled
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        entry.beansName ?: "Unknown beans",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (entry.bestForBean) Text("Best", style = MaterialTheme.typography.labelMedium)
                    if (compareSelected) Text("Compare", style = MaterialTheme.typography.labelMedium)
                    entry.rating?.let { Text(ratingText(it), style = MaterialTheme.typography.labelMedium) }
                }
                Text(entry.profileName, style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(
                        entry.finalYieldG?.let { "${it.format(1)} g" },
                        entry.durationMs?.let { "${(it / 1000.0).format(1)} s" },
                        entry.doseG?.let { "${it.format(1)} g dose" },
                        entry.grindSetting?.takeIf { it.isNotBlank() }?.let { "grind $it" },
                        entry.savedAtMs.let(::formatDateTime)
                    ).joinToString(" | "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryShotDetail(
    entry: ShotLibraryEntry,
    log: ShotLog,
    viewModel: MainViewModel
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Save-to-file: write JSON to a user-chosen location, then chain into an HTML report (mirrors
    // the Log tab's "Save to File"). The two-step SAF flow keeps each file at the path the user picks.
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var pendingHtml by remember { mutableStateOf<String?>(null) }
    var pendingBase by remember { mutableStateOf("") }

    val saveHtmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        val html = pendingHtml ?: return@rememberLauncherForActivityResult
        pendingHtml = null
        if (uri == null) {
            viewModel.setLibraryMessage("Saved JSON (HTML skipped)")
            return@rememberLauncherForActivityResult
        }
        if (writeJsonToUri(context, uri, html)) {
            viewModel.setLibraryMessage("Saved JSON + HTML report")
        } else {
            viewModel.setLibraryMessage("Saved JSON, HTML write failed")
        }
    }

    val saveJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingJson ?: return@rememberLauncherForActivityResult
        pendingJson = null
        if (uri == null) { pendingHtml = null; return@rememberLauncherForActivityResult }
        if (!writeJsonToUri(context, uri, json)) {
            viewModel.setLibraryMessage("Save failed")
            pendingHtml = null
            return@rememberLauncherForActivityResult
        }
        if (pendingHtml != null) {
            saveHtmlLauncher.launch("$pendingBase.html")
        } else {
            viewModel.setLibraryMessage("Saved log to file")
        }
    }

    val detailScrollState = rememberScrollState()
    val notes = log.tasteNotes ?: log.notes

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(detailScrollState)
                    .padding(bottom = 34.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShotReportPreview(log, Modifier.fillMaxWidth().height(260.dp))
                MetricGrid(
                    metrics = listOf(
                        "Beans" to (entry.beansName ?: "--"),
                        "Profile" to entry.profileName,
                        "Dose" to (entry.doseG?.let { "${it.format(1)} g" } ?: "--"),
                        "Grind" to (entry.grindSetting ?: "--"),
                        "Yield" to (entry.finalYieldG?.let { "${it.format(1)} g" } ?: "--"),
                        "Time" to (entry.durationMs?.let { "${(it / 1000.0).format(1)} s" } ?: "--"),
                        "Rating" to (entry.rating?.let(::ratingText) ?: "--"),
                        "Roast" to (entry.roastLevel ?: "--"),
                        "Basket" to (entry.basket ?: "--"),
                        "Flow" to (entry.flowSource ?: "--"),
                        "Target yield" to (entry.targetYieldG?.let { "${it.format(1)} g" } ?: "--"),
                        "Target time" to (entry.targetTimeS?.let { "${it.format(1)} s" } ?: "--"),
                        "Saved" to formatDateTime(entry.savedAtMs)
                    ),
                    columns = 3
                )
                if (!notes.isNullOrBlank()) {
                    Text(notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ScrollHintOverlay(
                canScrollForward = detailScrollState.value < detailScrollState.maxValue,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = { viewModel.setLibraryShotBest(entry.shotId, !entry.bestForBean) }
            ) {
                Text(if (entry.bestForBean) "Clear Best" else "Mark Best")
            }
            OutlinedButton(onClick = { viewModel.shareLibraryShot(entry.shotId, ShotShareFormat.PNG) }) {
                Text("Share PNG")
            }
            OutlinedButton(onClick = { viewModel.shareLibraryShot(entry.shotId, ShotShareFormat.HTML) }) {
                Text("Share HTML")
            }
            OutlinedButton(onClick = { viewModel.shareLibraryShot(entry.shotId, ShotShareFormat.JSON) }) {
                Text("Share JSON")
            }
            OutlinedButton(
                onClick = {
                    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val base = "${sanitizeFilename(entry.beansName ?: log.profileName)}-$ts"
                    pendingJson = ShotLogCodec.encode(log)
                    pendingHtml = ShotHtmlExporter.export(log)
                    pendingBase = base
                    saveJsonLauncher.launch("$base.json")
                }
            ) {
                Icon(Icons.Default.SaveAlt, contentDescription = "Save to file (JSON + HTML)")
            }
            Button(onClick = { confirmDelete = true }) {
                Text("Delete")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete shot?") },
            text = { Text("This removes the library copy stored on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteLibraryShot(entry.shotId)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LibraryCompareDetail(
    entries: List<ShotLibraryEntry>,
    selectedShotIds: List<String>,
    compareShots: Map<String, ShotLog>,
    viewModel: MainViewModel
) {
    val ids = selectedShotIds.distinct().take(2)
    if (!LibraryCompareSelection.isReady(ids)) {
        CompareEmptyState()
        return
    }

    val entryA = entries.firstOrNull { it.shotId == ids[0] }
    val entryB = entries.firstOrNull { it.shotId == ids[1] }
    val logA = compareShots[ids[0]]
    val logB = compareShots[ids[1]]

    when {
        entryA == null || entryB == null -> CompareEmptyState()
        logA == null || logB == null -> Text("Loading compare shots.", style = MaterialTheme.typography.bodyMedium)
        else -> LibraryShotCompare(
            entryA = entryA,
            logA = logA,
            entryB = entryB,
            logB = logB,
            onSharePng = { viewModel.shareLibraryComparison(ids, ShotShareFormat.PNG) },
            onShareHtml = { viewModel.shareLibraryComparison(ids, ShotShareFormat.HTML) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryShotCompare(
    entryA: ShotLibraryEntry,
    logA: ShotLog,
    entryB: ShotLibraryEntry,
    logB: ShotLog,
    onSharePng: () -> Unit,
    onShareHtml: () -> Unit
) {
    val compareListState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = compareListState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ShotComparePreview(logA, logB, Modifier.fillMaxWidth().height(430.dp))
                }
                item {
                    Text("Summary deltas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                item {
                    MetricGrid(compareDeltaMetrics(entryA, entryB), columns = 2)
                }
                item {
                    Spacer(Modifier.height(26.dp))
                }
            }
            ScrollHintOverlay(
                canScrollForward = compareListState.canScrollForward,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = onSharePng) {
                Text("Share PNG")
            }
            OutlinedButton(onClick = onShareHtml) {
                Text("Share HTML")
            }
        }
    }
}

@Composable
private fun ShotComparePreview(logA: ShotLog, logB: ShotLog, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width.toInt().coerceAtLeast(1)
        val height = size.height.toInt().coerceAtLeast(1)
        ShotCompareRenderer(logA, logB, width, height).render(drawContext.canvas.nativeCanvas)
    }
}

@Composable
private fun ScrollHintOverlay(canScrollForward: Boolean, modifier: Modifier = Modifier) {
    if (!canScrollForward) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f)
                    )
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "More content below",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun CompareEmptyState() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Select two shots to compare", style = MaterialTheme.typography.titleMedium)
    }
}

private fun compareDeltaMetrics(entryA: ShotLibraryEntry, entryB: ShotLibraryEntry): List<Pair<String, String>> =
    listOf(
        "Yield" to numericCompare(
            a = entryA.finalYieldG,
            b = entryB.finalYieldG,
            unit = "g",
            digits = 1
        ),
        "Time" to numericCompare(
            a = entryA.durationMs?.let { it / 1000.0 },
            b = entryB.durationMs?.let { it / 1000.0 },
            unit = "s",
            digits = 1
        ),
        "Dose" to numericCompare(
            a = entryA.doseG,
            b = entryB.doseG,
            unit = "g",
            digits = 1
        ),
        "Grind" to textCompare(entryA.grindSetting, entryB.grindSetting),
        "Rating" to ratingCompare(entryA.rating, entryB.rating),
        "Profile" to textCompare(entryA.profileName, entryB.profileName),
        "Bean" to textCompare(entryA.beansName, entryB.beansName)
    )

private fun numericCompare(a: Double?, b: Double?, unit: String, digits: Int): String {
    val left = a?.let { "${it.format(digits)} $unit" } ?: "--"
    val right = b?.let { "${it.format(digits)} $unit" } ?: "--"
    val delta = if (a != null && b != null) {
        "${signedFormat(b - a, digits)} $unit"
    } else {
        "--"
    }
    return "A $left | B $right | d $delta"
}

private fun ratingCompare(a: Int?, b: Int?): String {
    val left = a?.let(::ratingText) ?: "--"
    val right = b?.let(::ratingText) ?: "--"
    val delta = if (a != null && b != null) signedFormat((b - a).toDouble(), 0) else "--"
    return "A $left | B $right | d $delta"
}

private fun textCompare(a: String?, b: String?): String {
    val left = a?.takeIf { it.isNotBlank() } ?: "--"
    val right = b?.takeIf { it.isNotBlank() } ?: "--"
    val state = if (left == right && left != "--") "same" else "diff"
    return "A $left | B $right | $state"
}

private fun signedFormat(value: Double, digits: Int): String {
    val sign = if (value >= 0.0) "+" else ""
    return sign + value.format(digits)
}

@Composable
private fun ShotReportPreview(log: ShotLog, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width.toInt().coerceAtLeast(1)
        val height = size.height.toInt().coerceAtLeast(1)
        val renderer = ShotFrameRenderer(log, width, height)
        renderer.render(drawContext.canvas.nativeCanvas, ShotRenderTiming.finalFrameTimeMs(log))
    }
}

@Composable
private fun EmptyLibraryDetail() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No saved shots yet.", style = MaterialTheme.typography.titleMedium)
        Text(
            "Save a shot from the Log tab after pulling it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun ratingText(rating: Int): String = "$rating/5"

private fun formatDateTime(timeMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timeMs))
