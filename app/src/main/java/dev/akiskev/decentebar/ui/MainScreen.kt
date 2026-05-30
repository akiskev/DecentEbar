package dev.akiskev.decentebar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.akiskev.decentebar.ble.ScaleConnectionState
import dev.akiskev.decentebar.engine.interpolatedPressurePoint
import dev.akiskev.decentebar.engine.nearestPressurePoint
import dev.akiskev.decentebar.model.ControllerState
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.ExitCondition
import dev.akiskev.decentebar.model.ExitMode
import dev.akiskev.decentebar.model.ProfileStage
import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.storage.ShotHtmlExporter
import dev.akiskev.decentebar.storage.ShotVideoExporter
import dev.akiskev.decentebar.model.ShotSample
import dev.akiskev.decentebar.model.StageSafety
import dev.akiskev.decentebar.model.StageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

private const val PAYPAL_DONATION_URL =
    "https://www.paypal.com/donate/?business=akiskev%40gmail.com&item_name=Decent%20E-Bar&currency_code=USD"

private enum class AppTab(val label: String, val icon: ImageVector) {
    CONTROL("Control", Icons.Default.PlayArrow),
    PROFILE("Profile", Icons.Default.Tune),
    LUT("LUT", Icons.Default.TableChart),
    DEBUG("Debug", Icons.Default.BugReport),
    LOG("Log", Icons.Default.Assessment),
    ABOUT("About", Icons.Default.Info)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    openAccessibilitySettings: () -> Unit,
    connectToScale: () -> Unit = {},
    disconnectScale: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(AppTab.CONTROL) }

    Scaffold { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            NavigationRail {
                val mainTabs = AppTab.entries.filter { it != AppTab.ABOUT }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Top
                ) {
                    mainTabs.forEach { tab ->
                        NavigationRailItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab }
                        )
                    }
                }
                NavigationRailItem(
                    icon = { Icon(AppTab.ABOUT.icon, contentDescription = AppTab.ABOUT.label) },
                    label = { Text(AppTab.ABOUT.label) },
                    selected = selectedTab == AppTab.ABOUT,
                    onClick = { selectedTab = AppTab.ABOUT }
                )
            }
            VerticalDivider()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Header(state)
                when (selectedTab) {
                    AppTab.CONTROL -> ControlScreen(state, viewModel, openAccessibilitySettings, connectToScale, disconnectScale)
                    AppTab.PROFILE -> ProfileScreen(state, viewModel)
                    AppTab.LUT -> LutScreen(state, viewModel)
                    AppTab.DEBUG -> DebugScreen(state)
                    AppTab.LOG -> LogScreen(state, viewModel)
                    AppTab.ABOUT -> AboutScreen()
                }
            }
        }
    }
}

@Composable
private fun Header(state: MainUiState) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Decent E-Bar",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${state.selectedProfile.name} | stop at ${state.stopAtWeightG.format(1)} g",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            StatusBadge(state.controllerState)
        }
    }
}

@Composable
private fun StatusBadge(controllerState: ControllerState) {
    val container = when (controllerState) {
        ControllerState.ERROR -> MaterialTheme.colorScheme.errorContainer
        ControllerState.RUNNING,
        ControllerState.STAGE_TRANSITION,
        ControllerState.STOPPING -> MaterialTheme.colorScheme.tertiaryContainer
        ControllerState.ARMED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            controllerState.name,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ControlScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    openAccessibilitySettings: () -> Unit,
    connectToScale: () -> Unit = {},
    disconnectScale: () -> Unit = {}
) {
    val scaleLabel = when (state.scaleConnectionState) {
        ScaleConnectionState.DISCONNECTED -> "Scale"
        ScaleConnectionState.SCANNING -> "Scanning…"
        ScaleConnectionState.CONNECTING -> "Connecting…"
        ScaleConnectionState.CONNECTED -> "Scale ●"
        ScaleConnectionState.ERROR -> "Scale ✕"
    }
    val scaleConnected = state.scaleConnectionState == ScaleConnectionState.CONNECTED
    val scaleBusy = state.scaleConnectionState == ScaleConnectionState.SCANNING ||
            state.scaleConnectionState == ScaleConnectionState.CONNECTING

    val metrics = listOf(
        "Controller" to state.controllerState.name,
        "Service" to if (state.serviceEnabled) "Enabled" else "Disabled",
        "E-Bar foreground" to yesNo(state.snapshot.isForeground),
        "LUT" to (state.loadedLut?.name ?: "Missing"),
        "LUT validation" to state.lutValidation.displayText,
        "Start visible" to yesNo(state.snapshot.hasStart),
        "Stop visible" to yesNo(state.snapshot.hasStop),
        "Weight" to (state.currentWeightG?.format(1)?.plus(" g") ?: "--"),
        "Flow" to "${state.currentFlowGps.format(2)} g/s",
        "Stage" to state.currentStageName,
        "Pressure" to (state.commandedPressureBar?.format(2)?.plus(" bar") ?: "--"),
        "Elapsed" to "${(state.elapsedShotTimeMs / 1000.0).format(1)} s",
        "Safety" to state.safetyStatus,
        "Last pressure" to state.lastPressureCommand,
        "Last stop" to state.lastStopCommand,
        "Scale" to state.scaleConnectionState.name,
        "Scale Batt" to (state.scaleBatteryPercent?.let { "$it %" } ?: "--")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { if (state.isArmed) viewModel.disarm() else viewModel.arm() }) {
                        Text(if (state.isArmed) "Disarm" else "Arm")
                    }
                    Button(
                        onClick = viewModel::emergencyStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("E-Stop")
                    }
                    OutlinedButton(onClick = viewModel::manualSkipStage) { Text("Skip Stage") }
                    TextButton(onClick = openAccessibilitySettings) { Text("Accessibility") }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (scaleConnected) {
                        Button(
                            onClick = disconnectScale,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) { Text(scaleLabel) }
                    } else {
                        OutlinedButton(
                            onClick = connectToScale,
                            enabled = !scaleBusy
                        ) { Text(scaleLabel) }
                    }
                    if (scaleConnected) {
                        Text(
                            "Using scale weight & flow",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        Panel("Live Status") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                metrics.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { (label, value) -> MetricCell(label, value, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var editedProfile by remember(state.selectedProfile) { mutableStateOf(state.selectedProfile) }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf("") }
    var showProfilePanel by remember { mutableStateOf(false) }
    var pendingProfileJson by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.exportedProfileJson) {
        if (state.exportedProfileJson.isNotBlank()) exportText = state.exportedProfileJson
    }

    val saveProfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingProfileJson
        pendingProfileJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        if (writeJsonToUri(context, uri, json)) {
            viewModel.setProfileMessage("Saved profile to file")
        } else {
            viewModel.setProfileMessage("Save failed")
        }
    }
    val loadProfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readJsonFromUri(context, uri)
        if (text != null) {
            viewModel.importProfileJson(text)
        } else {
            viewModel.setProfileMessage("Could not read file")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left pane: collapsible, hidden by default so stages get full width
        if (showProfilePanel) {
            LazyColumn(
                modifier = Modifier.width(260.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    Panel("Profiles") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.profiles.forEach { profile ->
                                FilterChip(
                                    selected = profile.name == state.selectedProfile.name,
                                    onClick = {
                                        viewModel.selectProfile(profile.name)
                                        editedProfile = profile
                                    },
                                    label = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { viewModel.saveProfile(editedProfile) }) { Text("Save") }
                            OutlinedButton(onClick = viewModel::duplicateSelectedProfile) { Text("Dup") }
                            OutlinedButton(onClick = viewModel::deleteSelectedProfile) { Text("Del") }
                            OutlinedButton(onClick = viewModel::exportSelectedProfile) { Text("Export") }
                        }
                        OutlinedButton(
                            onClick = { editedProfile = DefaultProfiles.flow34.copy(name = "New Profile") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("New Profile") }
                        MessageLine(state.profileMessage)
                    }
                }

                item {
                    Panel("Profile Settings") {
                        OutlinedTextField(
                            value = editedProfile.name,
                            onValueChange = { editedProfile = editedProfile.copy(name = it) },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SliderField(
                            label = "Target weight",
                            value = editedProfile.targetWeightG,
                            valueRange = 0f..120f,
                            steps = 239,
                            unit = "g",
                            onChange = { editedProfile = editedProfile.copy(targetWeightG = it) }
                        )
                        SliderField(
                            label = "Stop offset",
                            value = editedProfile.stopOffsetG,
                            valueRange = 0f..5f,
                            steps = 49,
                            unit = "g",
                            onChange = { editedProfile = editedProfile.copy(stopOffsetG = it) }
                        )
                        SliderLongField(
                            label = "Max shot time",
                            value = editedProfile.maxShotTimeMs,
                            valueRange = 0f..120000f,
                            steps = 119,
                            unit = "ms",
                            onChange = { editedProfile = editedProfile.copy(maxShotTimeMs = it) }
                        )
                    }
                }

                item {
                    Panel("Profile JSON") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.importProfileJson(importText) }) { Text("Import") }
                            OutlinedButton(onClick = { importText = exportText }) { Text("Use Export") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                pendingProfileJson = viewModel.selectedProfileJson()
                                saveProfileLauncher.launch("${sanitizeFilename(state.selectedProfile.name)}.json")
                            }) { Text("Save to File") }
                            OutlinedButton(onClick = {
                                loadProfileLauncher.launch(arrayOf("application/json", "*/*"))
                            }) { Text("Load from File") }
                        }
                        OutlinedTextField(
                            value = importText,
                            onValueChange = { importText = it },
                            label = { Text("Import JSON") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = exportText,
                            onValueChange = { exportText = it },
                            label = { Text("Export JSON") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            VerticalDivider()
        }

        // Right pane: stages — takes full width when panel is hidden
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showProfilePanel = !showProfilePanel }) {
                        Icon(
                            if (showProfilePanel) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                            contentDescription = if (showProfilePanel) "Hide profile panel" else "Show profile panel"
                        )
                    }
                    Text("Stages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!showProfilePanel) {
                        Text(
                            "· ${state.selectedProfile.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!showProfilePanel) {
                        Button(onClick = { viewModel.saveProfile(editedProfile) }) { Text("Save") }
                    }
                    OutlinedButton(onClick = {
                        editedProfile = editedProfile.copy(stages = editedProfile.stages + newStage())
                    }) { Text("Add Stage") }
                }
            }
            if (state.profileMessage.isNotBlank()) {
                Text(
                    state.profileMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(editedProfile.stages.indices.toList(), key = { it }) { index ->
                    StageEditor(
                        index = index,
                        stage = editedProfile.stages[index],
                        canMoveUp = index > 0,
                        canMoveDown = index < editedProfile.stages.lastIndex,
                        onStageChange = { updated ->
                            editedProfile = editedProfile.copy(
                                stages = editedProfile.stages.replaceAt(index, updated)
                            )
                        },
                        onRemove = {
                            editedProfile = editedProfile.copy(
                                stages = editedProfile.stages.removeAt(index)
                            )
                        },
                        onMoveUp = {
                            editedProfile = editedProfile.copy(
                                stages = editedProfile.stages.move(index, index - 1)
                            )
                        },
                        onMoveDown = {
                            editedProfile = editedProfile.copy(
                                stages = editedProfile.stages.move(index, index + 1)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StageEditor(
    index: Int,
    stage: ProfileStage,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onStageChange: (ProfileStage) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val cardColor = if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        // Always-visible header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(stage.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        stage.type.shortName(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Row {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove")
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
        }

        // Collapsible body
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = stage.name,
                    onValueChange = { onStageChange(stage.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StageType.entries.forEach { type ->
                        FilterChip(
                            selected = stage.type == type,
                            onClick = { onStageChange(stage.withTypeDefaults(type)) },
                            label = { Text(type.shortName()) }
                        )
                    }
                }

                when (stage.type) {
                    StageType.FIXED_PRESSURE -> {
                        SliderField(
                            label = "Fixed pressure",
                            value = stage.fixedPressureBar ?: 2.0,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { onStageChange(stage.copy(fixedPressureBar = it)) }
                        )
                    }
                    StageType.FLOW_LIMITED_PRESSURE -> {
                        SliderField(
                            label = "Pressure cap",
                            value = stage.pressureCapBar ?: 8.5,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { onStageChange(stage.copy(pressureCapBar = it)) }
                        )
                        SliderField(
                            label = "Target flow",
                            value = stage.targetFlowGps ?: 1.5,
                            valueRange = 0f..8f,
                            steps = 159,
                            unit = "g/s",
                            onChange = { onStageChange(stage.copy(targetFlowGps = it)) }
                        )
                        Text(
                            "Deadband, step and correction interval are auto-tuned" +
                                " (faster with BLE scale). Override via JSON import.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    StageType.WEIGHT_BASED_PRESSURE_RAMP -> {
                        SliderField(
                            label = "Start pressure",
                            value = stage.rampStartPressureBar ?: 2.0,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { onStageChange(stage.copy(rampStartPressureBar = it)) }
                        )
                        SliderField(
                            label = "End pressure",
                            value = stage.rampEndPressureBar ?: 5.0,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { onStageChange(stage.copy(rampEndPressureBar = it)) }
                        )
                        SliderField(
                            label = "Start weight",
                            value = stage.rampStartWeightG ?: 0.0,
                            valueRange = 0f..120f,
                            steps = 239,
                            unit = "g",
                            onChange = { onStageChange(stage.copy(rampStartWeightG = it)) }
                        )
                        SliderField(
                            label = "End weight",
                            value = stage.rampEndWeightG ?: 36.0,
                            valueRange = 0f..120f,
                            steps = 239,
                            unit = "g",
                            onChange = { onStageChange(stage.copy(rampEndWeightG = it)) }
                        )
                    }
                    StageType.TIME_BASED_PRESSURE_RAMP -> {
                        SliderField(
                            label = "Start pressure",
                            value = stage.rampStartPressureBar ?: 2.0,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { onStageChange(stage.copy(rampStartPressureBar = it)) }
                        )
                        SliderField(
                            label = "End pressure",
                            value = stage.rampEndPressureBar ?: 8.0,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { onStageChange(stage.copy(rampEndPressureBar = it)) }
                        )
                        SliderLongField(
                            label = "Duration",
                            value = stage.rampDurationMs ?: 4000L,
                            valueRange = 0f..30000f,
                            steps = 59,
                            unit = "ms",
                            onChange = { onStageChange(stage.copy(rampDurationMs = it)) }
                        )
                    }
                    StageType.STOP -> {
                        Text("No parameters", style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider()
                ExitEditor(stage.exit) { onStageChange(stage.copy(exit = it)) }
                SafetyEditor(stage.safety) { onStageChange(stage.copy(safety = it)) }
            }
        }
    }
}

@Composable
private fun ExitEditor(exit: ExitCondition, onExitChange: (ExitCondition) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Exit", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                FilterChip(
                    selected = exit.mode == ExitMode.ANY,
                    onClick = { onExitChange(exit.copy(mode = ExitMode.ANY)) },
                    label = { Text("ANY") }
                )
                FilterChip(
                    selected = exit.mode == ExitMode.ALL,
                    onClick = { onExitChange(exit.copy(mode = ExitMode.ALL)) },
                    label = { Text("ALL") }
                )
                Spacer(Modifier.weight(1f))
                LabeledSwitch("First drop", exit.firstDropDetected) {
                    onExitChange(exit.copy(firstDropDetected = it))
                }
                LabeledSwitch("Manual skip", exit.manualSkip) {
                    onExitChange(exit.copy(manualSkip = it))
                }
                LabeledSwitch("Safety timeout", exit.safetyTimeout) {
                    onExitChange(exit.copy(safetyTimeout = it))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OptionalSliderField(
                    label = "Weight >=",
                    value = exit.weightGte,
                    valueRange = 0f..120f,
                    steps = 239,
                    unit = "g",
                    defaultValue = 1.0,
                    labelWidth = 80.dp,
                    onChange = { onExitChange(exit.copy(weightGte = it)) }
                )
                OptionalSliderLongField(
                    label = "Stage time >=",
                    value = exit.stageTimeGteMs,
                    valueRange = 0f..60000f,
                    steps = 119,
                    unit = "ms",
                    defaultValue = 5000L,
                    labelWidth = 80.dp,
                    onChange = { onExitChange(exit.copy(stageTimeGteMs = it)) }
                )
                OptionalSliderField(
                    label = "Flow >=",
                    value = exit.flowGte,
                    valueRange = 0f..8f,
                    steps = 159,
                    unit = "g/s",
                    defaultValue = 0.5,
                    labelWidth = 80.dp,
                    onChange = { onExitChange(exit.copy(flowGte = it)) }
                )
                OptionalSliderField(
                    label = "Flow <=",
                    value = exit.flowLte,
                    valueRange = 0f..8f,
                    steps = 159,
                    unit = "g/s",
                    defaultValue = 3.0,
                    labelWidth = 80.dp,
                    onChange = { onExitChange(exit.copy(flowLte = it)) }
                )
            }
        }
    }
}

@Composable
private fun SafetyEditor(safety: StageSafety, onSafetyChange: (StageSafety) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OptionalSliderLongField(
            label = "Stage max time",
            value = safety.maxStageTimeMs,
            valueRange = 0f..60000f,
            steps = 119,
            unit = "ms",
            defaultValue = 20_000L,
            modifier = Modifier.weight(1f),
            onChange = { onSafetyChange(safety.copy(maxStageTimeMs = it)) }
        )
        LabeledSwitch("2 reads for first drop", safety.requireTwoConsecutiveFirstDropReadings) {
            onSafetyChange(safety.copy(requireTwoConsecutiveFirstDropReadings = it))
        }
    }
}

@Composable
private fun LutScreen(state: MainUiState, viewModel: MainViewModel) {
    var pressureValue by remember { mutableStateOf(8.0) }
    val nearest = state.loadedLut?.nearestPressurePoint(pressureValue)
    val interpolated = state.loadedLut?.interpolatedPressurePoint(pressureValue)

    val lutMetrics = listOf(
        "Loaded" to (state.loadedLut?.name ?: "No"),
        "Package" to (state.loadedLut?.packageName ?: "--"),
        "Resolution" to (state.loadedLut?.let { "${it.screenWidth} x ${it.screenHeight}" } ?: "--"),
        "Orientation" to (state.loadedLut?.orientation ?: "--"),
        "Points" to (state.loadedLut?.points?.size?.toString() ?: "0"),
        "Validation" to state.lutValidation.displayText,
        "Selected pressure" to "${pressureValue.format(2)} bar",
        "Nearest coordinate" to (nearest?.let { "${it.pressureBar.format(2)} bar at ${it.x.toInt()},${it.y.toInt()}" } ?: "--"),
        "Interpolated coordinate" to (interpolated?.let { "${it.pressureBar.format(2)} bar at ${it.x.format(1)},${it.y.format(1)}" } ?: "--")
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Panel("LUT Status") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lutMetrics.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { (label, value) -> MetricCell(label, value, Modifier.weight(1f)) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        item {
            Panel("Pressure Test") {
                SliderField(
                    label = "Pressure",
                    value = pressureValue,
                    valueRange = 0f..12f,
                    steps = 119,
                    unit = "bar",
                    onChange = { pressureValue = it }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { viewModel.testPressure(pressureValue.format(2)) }) { Text("Test Point") }
                    OutlinedButton(onClick = viewModel::exportLut) { Text("Export JSON") }
                }
                MessageLine(state.lutMessage)
            }
        }

        item {
            Panel("Export JSON") {
                OutlinedTextField(
                    value = state.exportedLutJson,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Computed LUT JSON") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DebugScreen(state: MainUiState) {
    val snapshotMetrics = listOf(
        "Active package" to (state.snapshot.activePackage ?: "--"),
        "Screen" to "${state.snapshot.screenWidth} x ${state.snapshot.screenHeight}",
        "Orientation" to state.snapshot.orientation,
        "Parsed weight" to (state.snapshot.weightG?.format(1)?.plus(" g") ?: "--"),
        "Has Start" to yesNo(state.snapshot.hasStart),
        "Has Stop" to yesNo(state.snapshot.hasStop),
        "Has Weigh" to yesNo(state.snapshot.hasWeigh),
        "Pressure controls" to yesNo(state.snapshot.hasPressureControls),
        "Last pressure cmd" to state.lastPressureCommand,
        "Last stop cmd" to state.lastStopCommand,
        "Last safety error" to state.lastSafetyError
    )
    val scaleConnected = state.scaleConnectionState == ScaleConnectionState.CONNECTED
    val flowDiff = state.currentFlowGps - state.currentCalcFlowGps

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(0.5f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (scaleConnected) {
                Panel("Flow Comparison (Scale vs Software)") {
                    val compMetrics = listOf(
                        "Scale flow" to "${state.currentFlowGps.format(2)} g/s",
                        "Calc flow" to "${state.currentCalcFlowGps.format(2)} g/s",
                        "Diff (S−C)" to "${if (flowDiff >= 0) "+" else ""}${flowDiff.format(2)} g/s"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        compMetrics.forEach { (label, value) ->
                            MetricCell(label, value, Modifier.weight(1f))
                        }
                    }
                }
            }
            Panel("Accessibility Snapshot") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    snapshotMetrics.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { (label, value) -> MetricCell(label, value, Modifier.weight(1f)) }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.weight(0.5f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Panel("Raw content-desc", modifier = Modifier.weight(1f), fillContent = true) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.snapshot.rawDescriptions.take(160)) { desc ->
                        Text(desc, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Panel("Raw text", modifier = Modifier.weight(1f), fillContent = true) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.snapshot.rawTexts.take(160)) { text ->
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var pendingLogJson by remember { mutableStateOf<String?>(null) }
    var pendingLogHtml by remember { mutableStateOf<String?>(null) }
    var pendingFilenameBase by remember { mutableStateOf("") }

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
                        val log = viewModel.currentShotLog() ?: return@OutlinedButton
                        val json = viewModel.currentShotLogJson()
                        if (json.isBlank()) return@OutlinedButton
                        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val base = "${sanitizeFilename(state.selectedProfile.name)}-$ts"
                        pendingLogJson = json
                        pendingLogHtml = ShotHtmlExporter.export(log)
                        pendingFilenameBase = base
                        saveLogLauncher.launch("$base.json")
                    }) { Text("Save to File") }
                    OutlinedButton(onClick = viewModel::resetShotLog) { Text("Clear") }
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
                androidx.compose.material3.LinearProgressIndicator(
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

@Composable
private fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    fillContent: Boolean = false,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        if (fillContent) {
            Column(Modifier.padding(14.dp).fillMaxSize()) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f)) {
                    content()
                }
            }
        } else {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AboutScreen() {
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Decent E-Bar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("v0.1.0-beta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Automates espresso pressure profiling on the Decent E-Bar by reading live weight and flow via Android Accessibility and dispatching precise tap gestures on the pressure slider.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Safety", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text(
                    "This app dispatches real gestures to live hardware. Always keep the E-Bar within reach. Use E-Stop or Disarm immediately if the shot behaves unexpectedly. Never leave an armed session unattended.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Feedback & Support", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "This is a beta release. Bugs, unexpected behaviour, and profile sharing are all welcome — please include an exported shot log when reporting issues.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { uriHandler.openUri("mailto:akiskev@gmail.com?subject=Decent%20E-Bar%20Feedback") },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("akiskev@gmail.com", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Support Development", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Donations help support continued development and testing.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { uriHandler.openUri(PAYPAL_DONATION_URL) },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Donate with PayPal", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            HorizontalDivider()
        }
        item {
            TextButton(
                onClick = { uriHandler.openUri("https://akiskev.dev") },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "akiskev.dev",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageLine(message: String) {
    if (message.isNotBlank()) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderField(
    label: String,
    value: Double,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 110.dp,
    decimals: Int = 2,
    onChange: (Double) -> Unit
) {
    var localText by remember { mutableStateOf(value.format(decimals)) }
    var textHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!textHasFocus) localText = value.format(decimals)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(labelWidth))
        Slider(
            value = value.toFloat().coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f)
        )
        BasicTextField(
            value = localText,
            onValueChange = { localText = it },
            singleLine = true,
            textStyle = TextStyle(
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    innerTextField()
                }
            },
            modifier = Modifier
                .width(60.dp)
                .onFocusChanged { focusState ->
                    textHasFocus = focusState.isFocused
                    if (!focusState.isFocused) {
                        val parsed = localText.toDoubleOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(
                                valueRange.start.toDouble(),
                                valueRange.endInclusive.toDouble()
                            )
                            onChange(clamped)
                            localText = clamped.format(decimals)
                        } else {
                            localText = value.format(decimals)
                        }
                    }
                }
        )
        Text(unit, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
    }
}

@Composable
private fun SliderLongField(
    label: String,
    value: Long,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 110.dp,
    onChange: (Long) -> Unit
) {
    var localText by remember { mutableStateOf(value.toString()) }
    var textHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!textHasFocus) localText = value.toString()
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(labelWidth))
        Slider(
            value = value.toFloat().coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { onChange(it.roundToLong()) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f)
        )
        BasicTextField(
            value = localText,
            onValueChange = { localText = it },
            singleLine = true,
            textStyle = TextStyle(
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    innerTextField()
                }
            },
            modifier = Modifier
                .width(72.dp)
                .onFocusChanged { focusState ->
                    textHasFocus = focusState.isFocused
                    if (!focusState.isFocused) {
                        val parsed = localText.toLongOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(
                                valueRange.start.toLong(),
                                valueRange.endInclusive.toLong()
                            )
                            onChange(clamped)
                            localText = clamped.toString()
                        } else {
                            localText = value.toString()
                        }
                    }
                }
        )
        Text(unit, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
    }
}

@Composable
private fun OptionalSliderField(
    label: String,
    value: Double?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    defaultValue: Double,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 130.dp,
    decimals: Int = 2,
    onChange: (Double?) -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Switch(
            checked = value != null,
            onCheckedChange = { enabled -> onChange(if (enabled) defaultValue else null) }
        )
        if (value != null) {
            SliderField(
                label = label,
                value = value,
                valueRange = valueRange,
                steps = steps,
                unit = unit,
                modifier = Modifier.weight(1f),
                labelWidth = labelWidth,
                decimals = decimals,
                onChange = { onChange(it) }
            )
        } else {
            Text(
                "$label: —",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OptionalSliderLongField(
    label: String,
    value: Long?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    defaultValue: Long,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 130.dp,
    onChange: (Long?) -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Switch(
            checked = value != null,
            onCheckedChange = { enabled -> onChange(if (enabled) defaultValue else null) }
        )
        if (value != null) {
            SliderLongField(
                label = label,
                value = value,
                valueRange = valueRange,
                steps = steps,
                unit = unit,
                modifier = Modifier.weight(1f),
                labelWidth = labelWidth,
                onChange = { onChange(it) }
            )
        } else {
            Text(
                "$label: —",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

