package dev.akiskev.decentebar.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.akiskev.decentebar.model.ControllerState
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.ExitCondition
import dev.akiskev.decentebar.model.ExitMode
import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.PressurePoint
import dev.akiskev.decentebar.model.ProfileStage
import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.model.ShotSample
import dev.akiskev.decentebar.model.StageSafety
import dev.akiskev.decentebar.model.StageType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class AppTab(val label: String, val icon: ImageVector) {
    CONTROL("Control", Icons.Default.PlayArrow),
    PROFILE("Profile", Icons.Default.Tune),
    LUT("LUT", Icons.Default.TableChart),
    DEBUG("Debug", Icons.Default.BugReport),
    LOG("Log", Icons.Default.Assessment)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    openAccessibilitySettings: () -> Unit
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
                AppTab.entries.forEach { tab ->
                    NavigationRailItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab }
                    )
                }
            }
            VerticalDivider()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Header(state)
                when (selectedTab) {
                    AppTab.CONTROL -> ControlScreen(state, viewModel, openAccessibilitySettings)
                    AppTab.PROFILE -> ProfileScreen(state, viewModel)
                    AppTab.LUT -> LutScreen(state, viewModel)
                    AppTab.DEBUG -> DebugScreen(state)
                    AppTab.LOG -> LogScreen(state, viewModel)
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
    openAccessibilitySettings: () -> Unit
) {
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
        "Last stop" to state.lastStopCommand
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
            Row(
                modifier = Modifier.padding(8.dp),
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
    var editedProfile by remember(state.selectedProfile) { mutableStateOf(state.selectedProfile) }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf("") }
    var showProfilePanel by remember { mutableStateOf(false) }

    LaunchedEffect(state.exportedProfileJson) {
        if (state.exportedProfileJson.isNotBlank()) exportText = state.exportedProfileJson
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
                            onClick = { editedProfile = DefaultProfiles.firstDropFlowFade.copy(name = "New Profile") },
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

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        SliderField(
                            label = "Flow deadband",
                            value = stage.flowDeadbandGps ?: 0.2,
                            valueRange = 0f..2f,
                            steps = 39,
                            unit = "g/s",
                            onChange = { onStageChange(stage.copy(flowDeadbandGps = it)) }
                        )
                        SliderField(
                            label = "Pressure step",
                            value = stage.pressureStepBar ?: 0.2,
                            valueRange = 0f..1f,
                            steps = 19,
                            unit = "bar",
                            onChange = { onStageChange(stage.copy(pressureStepBar = it)) }
                        )
                        SliderLongField(
                            label = "Correction interval",
                            value = stage.correctionIntervalMs ?: 500L,
                            valueRange = 100f..2000f,
                            steps = 189,
                            unit = "ms",
                            onChange = { onStageChange(stage.copy(correctionIntervalMs = it)) }
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
    var lutJson by remember { mutableStateOf("") }
    var pressureValue by remember { mutableStateOf(8.0) }
    val nearest = state.loadedLut?.nearest(pressureValue)
    val interpolated = state.loadedLut?.interpolated(pressureValue)

    LaunchedEffect(state.exportedLutJson) {
        if (state.exportedLutJson.isNotBlank()) lutJson = state.exportedLutJson
    }

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
                    OutlinedButton(onClick = viewModel::exportLut) { Text("Export") }
                    OutlinedButton(onClick = viewModel::deleteLut) { Text("Delete") }
                }
                MessageLine(state.lutMessage)
            }
        }

        item {
            Panel("Import / Export JSON") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.importLutJson(lutJson) }) { Text("Import LUT") }
                    OutlinedButton(onClick = { lutJson = state.exportedLutJson }) { Text("Use Export") }
                }
                OutlinedTextField(
                    value = lutJson,
                    onValueChange = { lutJson = it },
                    label = { Text("LUT JSON") },
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
                    OutlinedButton(onClick = viewModel::resetShotLog) { Text("Clear") }
                }
                Text("${state.samples.size} samples | ${state.events.size} events")
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

// — Private helpers —

private fun newStage(): ProfileStage {
    return ProfileStage(
        name = "New Stage",
        type = StageType.FIXED_PRESSURE,
        fixedPressureBar = 2.0,
        exit = ExitCondition(weightGte = 1.0),
        safety = StageSafety()
    )
}

private fun ProfileStage.withTypeDefaults(newType: StageType): ProfileStage {
    return when (newType) {
        StageType.FIXED_PRESSURE -> copy(
            type = newType,
            fixedPressureBar = fixedPressureBar ?: 2.0
        )
        StageType.FLOW_LIMITED_PRESSURE -> copy(
            type = newType,
            pressureCapBar = pressureCapBar ?: 8.5,
            targetFlowGps = targetFlowGps ?: 1.5,
            flowDeadbandGps = flowDeadbandGps ?: 0.2,
            pressureStepBar = pressureStepBar ?: 0.2,
            correctionIntervalMs = correctionIntervalMs ?: 500L
        )
        StageType.WEIGHT_BASED_PRESSURE_RAMP -> copy(
            type = newType,
            rampStartPressureBar = rampStartPressureBar ?: 2.0,
            rampEndPressureBar = rampEndPressureBar ?: 5.0,
            rampStartWeightG = rampStartWeightG ?: 0.0,
            rampEndWeightG = rampEndWeightG ?: 36.0
        )
        StageType.TIME_BASED_PRESSURE_RAMP -> copy(
            type = newType,
            rampStartPressureBar = rampStartPressureBar ?: 2.0,
            rampEndPressureBar = rampEndPressureBar ?: 8.0,
            rampDurationMs = rampDurationMs ?: 4_000L
        )
        StageType.STOP -> copy(type = newType)
    }
}

private fun StageType.shortName(): String {
    return when (this) {
        StageType.FIXED_PRESSURE -> "Fixed"
        StageType.FLOW_LIMITED_PRESSURE -> "Flow"
        StageType.WEIGHT_BASED_PRESSURE_RAMP -> "Weight Ramp"
        StageType.TIME_BASED_PRESSURE_RAMP -> "Time Ramp"
        StageType.STOP -> "Stop"
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, item -> if (i == index) value else item }

private fun <T> List<T>.removeAt(index: Int): List<T> =
    filterIndexed { i, _ -> i != index }

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    val mutable = toMutableList()
    val value = mutable.removeAt(from)
    mutable.add(to, value)
    return mutable
}

private fun PressureLut.nearest(pressure: Double) =
    points.minByOrNull { abs(it.pressureBar - pressure) }

private fun PressureLut.interpolated(pressure: Double): PressurePoint? {
    val sorted = points.sortedBy { it.pressureBar }
    if (sorted.isEmpty()) return null
    if (sorted.size == 1) return sorted[0]
    val first = sorted.first()
    if (pressure <= first.pressureBar) return first
    val last = sorted.last()
    if (pressure >= last.pressureBar) return last
    for (i in 0 until sorted.size - 1) {
        val p0 = sorted[i]
        val p1 = sorted[i + 1]
        if (pressure <= p1.pressureBar) {
            val range = p1.pressureBar - p0.pressureBar
            if (range == 0.0) return p0
            val t = (pressure - p0.pressureBar) / range
            return PressurePoint(
                pressureBar = pressure,
                x = (p0.x + t * (p1.x - p0.x)).roundToInt().toFloat(),
                y = (p0.y + t * (p1.y - p0.y)).roundToInt().toFloat()
            )
        }
    }
    return last
}

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

private fun Double.format(decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", this)

private fun Float.format(decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", this)
