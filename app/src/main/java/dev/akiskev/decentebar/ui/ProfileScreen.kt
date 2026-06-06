package dev.akiskev.decentebar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.ExitCondition
import dev.akiskev.decentebar.model.ExitMode
import dev.akiskev.decentebar.model.FeedForwardConfig
import dev.akiskev.decentebar.model.ProfileStage
import dev.akiskev.decentebar.model.StageSafety
import dev.akiskev.decentebar.model.StageType

@Composable
internal fun ProfileScreen(state: MainUiState, viewModel: MainViewModel) {
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
                            onClick = { editedProfile = DefaultProfiles.flow33Dark.copy(name = "New Profile") },
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
                        val feedForwardOn = stage.feedForward != null
                        LabeledSwitch("Resistance feed-forward", feedForwardOn) { on ->
                            onStageChange(stage.copy(feedForward = if (on) FeedForwardConfig() else null))
                        }
                        Text(
                            if (feedForwardOn) {
                                "Feed-forward (experimental): commands pressure from the puck's" +
                                    " learned resistance, with gusher-safe recovery. Tuning via JSON import."
                            } else {
                                "Legacy auto-tune: deadband, step and correction interval are auto-tuned" +
                                    " (faster with BLE scale). Override via JSON import."
                            },
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
