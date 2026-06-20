package dev.akiskev.decentebar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.akiskev.decentebar.engine.CurveMath
import dev.akiskev.decentebar.model.CurvePoint
import dev.akiskev.decentebar.model.ExitCondition
import dev.akiskev.decentebar.model.ExitMode
import dev.akiskev.decentebar.model.FeedForwardConfig
import dev.akiskev.decentebar.model.FlowCurveType
import dev.akiskev.decentebar.model.ProfileConstraints
import dev.akiskev.decentebar.model.ProfileValidator
import dev.akiskev.decentebar.model.PressureCurveAxis
import dev.akiskev.decentebar.model.PressureCurveConfig
import dev.akiskev.decentebar.model.ProfileStage
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.model.StageSafety
import dev.akiskev.decentebar.model.StageType
import dev.akiskev.decentebar.model.TastePriorityMode
import dev.akiskev.decentebar.model.YieldTimeTrajectoryConfig
import dev.akiskev.decentebar.util.formatDecimals

@Composable
internal fun ProfileScreen(state: MainUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var editedProfile by remember(state.selectedProfile) {
        mutableStateOf(ProfileConstraints.normalize(state.selectedProfile))
    }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf("") }
    var showProfilePanel by remember { mutableStateOf(false) }
    var pendingProfileJson by remember { mutableStateOf<String?>(null) }
    // Index of the stage whose flow curve is open in the full-screen editor (null = closed). Hosted
    // here (not in a Dialog) so the editor uses the real screen width — a Dialog window gets
    // width-clipped in landscape.
    var curveEditorIndex by remember { mutableStateOf<Int?>(null) }
    val validationErrors = ProfileValidator.validate(editedProfile)
    val isDirty = editedProfile != state.selectedProfile

    fun updateEdited(next: ShotProfile) {
        editedProfile = ProfileConstraints.normalize(next)
    }

    fun currentDraft(): ShotProfile {
        focusManager.clearFocus(force = true)
        val draft = ProfileConstraints.normalize(editedProfile)
        editedProfile = draft
        return draft
    }

    fun saveDraft() {
        viewModel.saveProfile(currentDraft())
    }

    fun exportDraft(): String {
        val json = viewModel.profileJson(currentDraft())
        if (json.isNotBlank()) exportText = json
        return json
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
                                        updateEdited(profile)
                                    },
                                    label = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { saveDraft() }, enabled = validationErrors.isEmpty()) { Text("Save") }
                            OutlinedButton(onClick = viewModel::duplicateSelectedProfile) { Text("Dup") }
                            OutlinedButton(onClick = viewModel::deleteSelectedProfile) { Text("Del") }
                            OutlinedButton(onClick = { exportDraft() }, enabled = validationErrors.isEmpty()) { Text("Export") }
                        }
                        OutlinedButton(
                            onClick = {
                                updateEdited(newPressureCurveProfile(state.profiles.map { it.name }.toSet()))
                                viewModel.setProfileMessage("New profile draft")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("New Profile") }
                        Text(
                            when {
                                validationErrors.isNotEmpty() -> validationErrors.first()
                                isDirty -> "Unsaved changes"
                                else -> "Saved profile selected"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (validationErrors.isNotEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                        MessageLine(state.profileMessage)
                    }
                }

                item {
                    Panel("Profile Settings") {
                        OutlinedTextField(
                            value = editedProfile.name,
                            onValueChange = { updateEdited(editedProfile.copy(name = it)) },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        SliderField(
                            label = "Target weight",
                            value = editedProfile.targetWeightG,
                            valueRange = 1f..120f,
                            steps = 238,
                            unit = "g",
                            onChange = { updateEdited(editedProfile.copy(targetWeightG = it)) }
                        )
                        val stopOffsetMax = minOf(5.0, editedProfile.targetWeightG - ProfileConstraints.MIN_TARGET_WEIGHT_G)
                            .coerceAtLeast(ProfileConstraints.MIN_TARGET_WEIGHT_G)
                        SliderField(
                            label = "Stop offset",
                            value = editedProfile.stopOffsetG,
                            valueRange = 0f..stopOffsetMax.toFloat(),
                            steps = 0,
                            unit = "g",
                            onChange = { updateEdited(editedProfile.copy(stopOffsetG = it)) }
                        )
                        val minShotTimeS = maxOf(
                            1f,
                            ProfileConstraints.configuredStageMaxTimeMs(editedProfile) / 1000f
                        )
                        SliderDurationField(
                            label = "Max shot time",
                            valueMs = editedProfile.maxShotTimeMs,
                            valueRangeSeconds = minShotTimeS..maxOf(120f, minShotTimeS),
                            steps = 0,
                            onChange = { updateEdited(editedProfile.copy(maxShotTimeMs = it)) }
                        )
                        Text(
                            "Stops at ${(editedProfile.targetWeightG - editedProfile.stopOffsetG).formatDecimals(1)} g",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                item {
                    Panel("Profile JSON") {
                        if (state.devMode) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.importProfileJson(importText) }) { Text("Import") }
                                OutlinedButton(onClick = { importText = exportText }) { Text("Use Export") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val json = exportDraft()
                                if (json.isNotBlank()) {
                                    pendingProfileJson = json
                                    saveProfileLauncher.launch("${sanitizeFilename(editedProfile.name)}.json")
                                }
                            }, enabled = validationErrors.isEmpty()) { Text("Save to File") }
                            OutlinedButton(onClick = {
                                loadProfileLauncher.launch(arrayOf("application/json", "*/*"))
                            }) { Text("Load from File") }
                        }
                        if (state.devMode) {
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
                            "· ${editedProfile.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!showProfilePanel) {
                        Button(onClick = { saveDraft() }, enabled = validationErrors.isEmpty()) { Text("Save") }
                    }
                    OutlinedButton(onClick = {
                        updateEdited(
                            editedProfile.copy(stages = editedProfile.stages + newStage(editedProfile.targetWeightG))
                        )
                    }) { Text("Add Stage") }
                }
            }
            if (!showProfilePanel) {
                Text(
                    when {
                        validationErrors.isNotEmpty() -> validationErrors.first()
                        isDirty -> "Unsaved changes"
                        else -> "Saved profile selected"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (validationErrors.isNotEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.padding(bottom = 4.dp)
                )
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
                        profileTargetWeightG = editedProfile.targetWeightG,
                        remainingYieldG = ProfileConstraints.maxYieldForStage(editedProfile, index),
                        canMoveUp = index > 0,
                        canMoveDown = index < editedProfile.stages.lastIndex,
                        onStageChange = { updated ->
                            updateEdited(
                                editedProfile.copy(
                                    stages = editedProfile.stages.replaceAt(index, updated)
                                )
                            )
                        },
                        onEditCurve = { curveEditorIndex = index },
                        onRemove = {
                            updateEdited(
                                editedProfile.copy(
                                    stages = editedProfile.stages.removeAt(index)
                                )
                            )
                        },
                        onMoveUp = {
                            updateEdited(
                                editedProfile.copy(
                                    stages = editedProfile.stages.move(index, index - 1)
                                )
                            )
                        },
                        onMoveDown = {
                            updateEdited(
                                editedProfile.copy(
                                    stages = editedProfile.stages.move(index, index + 1)
                                )
                            )
                        }
                    )
                }
            }
        }
    }

        // Full-screen flow-curve editor overlay (covers the content area, so it gets the real
        // screen width — see curveEditorIndex).
        val editIdx = curveEditorIndex
        if (editIdx != null) {
            val st = editedProfile.stages.getOrNull(editIdx)
            // Dispatch on the stage TYPE, not on which configs are non-null: switching a stage's type
            // keeps the old config (so toggling back is lossless), so e.g. a PRESSURE_CURVE stage can
            // still carry a stale yieldTime.
            val yt = st?.yieldTime.takeIf { st?.type == StageType.YIELD_TIME_TRAJECTORY }
            val pc = (st?.pressureCurve ?: PressureCurveConfig(points = defaultPressurePoints()))
                .takeIf { st?.type == StageType.PRESSURE_CURVE }
            if (st != null && yt != null) {
                FlowCurveEditorContent(
                    initialPoints = yt.customPoints.ifEmpty { defaultCustomPoints() },
                    initialDurationS = yt.targetDurationS,
                    initialMaxFlowGps = yt.maxFlowGps,
                    onCancel = { curveEditorIndex = null },
                    onConfirm = { pts, computedYield, dur, mf ->
                        val yieldBudget = ProfileConstraints.maxYieldForStage(editedProfile, editIdx)
                        val scale = if (computedYield > yieldBudget && computedYield > 0.0) {
                            yieldBudget / computedYield
                        } else {
                            1.0
                        }
                        val finalPts = pts.map { it.copy(flowGps = it.flowGps * scale) }
                        val finalYield = computedYield * scale
                        updateEdited(
                            editedProfile.copy(
                                stages = editedProfile.stages.replaceAt(
                                    editIdx,
                                    st.copy(
                                        yieldTime = yt.copy(
                                            curveType = FlowCurveType.CUSTOM_POINTS,
                                            customPoints = finalPts,
                                            targetYieldG = finalYield,
                                            targetDurationS = dur,
                                            maxFlowGps = mf * scale
                                        )
                                    )
                                )
                            )
                        )
                        curveEditorIndex = null
                    }
                )
            } else if (st != null && pc != null) {
                PressureCurveEditorContent(
                    initialPoints = pc.points.ifEmpty { defaultPressurePoints() },
                    axis = pc.axis,
                    initialXMax = if (pc.axis == PressureCurveAxis.TIME) pc.durationS else pc.maxWeightG,
                    initialMaxPressureBar = pc.maxPressureBar,
                    onCancel = { curveEditorIndex = null },
                    onConfirm = { pts, xMax, maxBar ->
                        val finalMaxBar = maxBar.coerceIn(ProfileConstraints.MIN_POSITIVE, ProfileConstraints.MAX_PRESSURE_BAR)
                        val finalMinBar = pc.minPressureBar.coerceIn(0.0, finalMaxBar)
                        val finalPts = pts.map {
                            it.copy(pressureBar = it.pressureBar.coerceIn(finalMinBar, finalMaxBar))
                        }
                        updateEdited(
                            editedProfile.copy(
                                stages = editedProfile.stages.replaceAt(
                                    editIdx,
                                    st.copy(
                                        pressureCurve = pc.copy(
                                            points = finalPts,
                                            maxPressureBar = finalMaxBar,
                                            minPressureBar = finalMinBar,
                                            durationS = if (pc.axis == PressureCurveAxis.TIME) xMax else pc.durationS,
                                            maxWeightG = if (pc.axis == PressureCurveAxis.WEIGHT) {
                                                xMax.coerceAtMost(editedProfile.targetWeightG)
                                            } else {
                                                pc.maxWeightG
                                            }
                                        )
                                    )
                                )
                            )
                        )
                        curveEditorIndex = null
                    }
                )
            } else {
                curveEditorIndex = null
            }
        }
    }
}

@Composable
private fun StageEditor(
    index: Int,
    stage: ProfileStage,
    profileTargetWeightG: Double,
    remainingYieldG: Double,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onStageChange: (ProfileStage) -> Unit,
    onEditCurve: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showAdvancedTypes by remember { mutableStateOf(false) }

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
                    val visibleStageTypes = primaryStageTypes +
                        if (showAdvancedTypes) advancedStageTypes else advancedStageTypes.filter { it == stage.type }
                    visibleStageTypes.distinct().forEach { type ->
                        FilterChip(
                            selected = stage.type == type,
                            onClick = { onStageChange(stage.withTypeDefaults(type, profileTargetWeightG)) },
                            label = { Text(type.shortName()) }
                        )
                    }
                }
                LabeledSwitch("Advanced types", showAdvancedTypes) { showAdvancedTypes = it }

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
                        var showFlowAdvanced by remember { mutableStateOf(false) }
                        LabeledSwitch("Advanced", showFlowAdvanced) { showFlowAdvanced = it }
                        AnimatedVisibility(visible = showFlowAdvanced) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val feedForwardOn = stage.feedForward != null
                                LabeledSwitch("Resistance feed-forward", feedForwardOn) { on ->
                                    onStageChange(stage.copy(feedForward = if (on) FeedForwardConfig() else null))
                                }
                                Text(
                                    if (feedForwardOn) {
                                        "Feed-forward: commands pressure from learned puck resistance with gusher-safe recovery."
                                    } else {
                                        "Legacy auto-tune: deadband, step and correction interval are auto-tuned."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
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
                            valueRange = 0f..profileTargetWeightG.toFloat(),
                            steps = 0,
                            unit = "g",
                            onChange = { onStageChange(stage.copy(rampStartWeightG = it)) }
                        )
                        SliderField(
                            label = "End weight",
                            value = stage.rampEndWeightG ?: profileTargetWeightG,
                            valueRange = 0f..profileTargetWeightG.toFloat(),
                            steps = 0,
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
                        SliderDurationField(
                            label = "Duration",
                            valueMs = stage.rampDurationMs ?: 4000L,
                            valueRangeSeconds = 1f..30f,
                            steps = 0,
                            onChange = { onStageChange(stage.copy(rampDurationMs = it)) }
                        )
                    }
                    StageType.YIELD_TIME_TRAJECTORY -> {
                        val yt = stage.yieldTime ?: YieldTimeTrajectoryConfig()
                        fun updateYt(block: (YieldTimeTrajectoryConfig) -> YieldTimeTrajectoryConfig) {
                            onStageChange(stage.copy(yieldTime = block(yt)))
                        }

                        // For a hand-drawn custom curve, yield + duration come from the editor
                        // (yield = area), so the analytic yield/time sliders are hidden.
                        if (yt.curveType != FlowCurveType.CUSTOM_POINTS) {
                            SliderField(
                                label = "Stage yield",
                                value = yt.targetYieldG,
                                valueRange = ProfileConstraints.MIN_TARGET_WEIGHT_G.toFloat()..remainingYieldG.toFloat(),
                                steps = 0,
                                unit = "g",
                                onChange = { v -> updateYt { it.copy(targetYieldG = v.coerceAtMost(remainingYieldG)) } }
                            )
                            SliderField(
                                label = "Target time",
                                value = yt.targetDurationS,
                                valueRange = 0f..90f,
                                steps = 179,
                                unit = "s",
                                onChange = { v -> updateYt { it.copy(targetDurationS = v) } }
                            )
                        }

                        Text(
                            "Curve",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FlowCurveType.entries.forEach { curve ->
                                FilterChip(
                                    selected = yt.curveType == curve,
                                    onClick = {
                                        if (curve == FlowCurveType.CUSTOM_POINTS && yt.customPoints.size < 2) {
                                            // Seed from a sensible default so the editor opens with a curve.
                                            val seeded = defaultCustomPoints()
                                            updateYt {
                                                it.copy(
                                                    curveType = curve,
                                                    customPoints = seeded,
                                                    targetYieldG = CurveMath.areaG(seeded, it.targetDurationS)
                                                )
                                            }
                                        } else {
                                            // Only the type changes: the shape hints (start/peak/end)
                                            // are shared by all analytic presets, so mutating them here
                                            // would corrupt the other presets' previews. DECLINING is
                                            // guaranteed to decline by the planner itself (rawKnots).
                                            updateYt { it.copy(curveType = curve) }
                                        }
                                    },
                                    label = { Text(curve.uiLabel()) }
                                )
                            }
                        }

                        // Live preview of the curve under the type chips — for every curve type. For
                        // analytic types it samples the planner's normalized flow; for Custom it's the
                        // drawn points, with an Edit button into the full-screen editor.
                        run {
                            val previewPts = yt.previewPoints()
                            val previewMax = (previewPts.maxOfOrNull { it.flowGps } ?: 1.0)
                                .times(1.15).coerceAtLeast(0.5)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CurveThumbnail(
                                    points = previewPts,
                                    yMax = previewMax,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(64.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                )
                                Column {
                                    Text(
                                        "Yield ≈ ${yt.targetYieldG.formatDecimals(1)} g",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (yt.curveType == FlowCurveType.CUSTOM_POINTS) {
                                        OutlinedButton(onClick = onEditCurve) { Text("Edit curve") }
                                    }
                                }
                            }
                        }

                        Text(
                            "Taste mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TastePriorityMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = yt.tastePriorityMode == mode,
                                    onClick = { updateYt { it.copy(tastePriorityMode = mode) } },
                                    label = { Text(mode.uiLabel()) }
                                )
                            }
                        }

                        SliderField(
                            label = "Max pressure",
                            value = yt.maxPressureBar,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { v -> updateYt { it.copy(maxPressureBar = v) } }
                        )
                        SliderField(
                            label = "Max flow",
                            value = yt.maxFlowGps,
                            valueRange = 0f..8f,
                            steps = 159,
                            unit = "g/s",
                            onChange = { v -> updateYt { it.copy(maxFlowGps = v) } }
                        )
                        SliderField(
                            label = "Pre-infuse",
                            value = yt.preInfusionPressureBar,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { v -> updateYt { it.copy(preInfusionPressureBar = v) } }
                        )
                        SliderField(
                            label = "Extract floor",
                            value = yt.minExtractionPressureBar,
                            valueRange = 0f..12f,
                            steps = 119,
                            unit = "bar",
                            onChange = { v -> updateYt { it.copy(minExtractionPressureBar = v) } }
                        )

                        var showAdvanced by remember { mutableStateOf(false) }
                        LabeledSwitch("Advanced", showAdvanced) { showAdvanced = it }
                        AnimatedVisibility(visible = showAdvanced) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val feedForwardOn = yt.feedForward != null
                                LabeledSwitch("Resistance feed-forward", feedForwardOn) { on ->
                                    updateYt { it.copy(feedForward = if (on) FeedForwardConfig() else null) }
                                }
                                if (yt.curveType != FlowCurveType.CUSTOM_POINTS) {
                                SliderField(
                                    label = "Start flow",
                                    value = yt.startFlowGps,
                                    valueRange = 0f..4f,
                                    steps = 79,
                                    unit = "g/s",
                                    onChange = { v -> updateYt { it.copy(startFlowGps = v) } }
                                )
                                SliderField(
                                    label = "Peak flow",
                                    value = yt.peakFlowGps,
                                    valueRange = 0f..4f,
                                    steps = 79,
                                    unit = "g/s",
                                    onChange = { v -> updateYt { it.copy(peakFlowGps = v) } }
                                )
                                SliderField(
                                    label = "End flow",
                                    value = yt.endFlowGps,
                                    valueRange = 0f..4f,
                                    steps = 79,
                                    unit = "g/s",
                                    onChange = { v -> updateYt { it.copy(endFlowGps = v) } }
                                )
                                SliderField(
                                    label = "Peak position",
                                    value = yt.peakAtPct,
                                    valueRange = 0f..1f,
                                    steps = 19,
                                    unit = "",
                                    onChange = { v -> updateYt { it.copy(peakAtPct = v) } }
                                )
                                }
                                SliderField(
                                    label = "Correction strength",
                                    value = yt.correctionStrength,
                                    valueRange = 0f..1f,
                                    steps = 19,
                                    unit = "",
                                    onChange = { v -> updateYt { it.copy(correctionStrength = v) } }
                                )
                                SliderField(
                                    label = "Late-shot window",
                                    value = yt.lateShotCorrectionLimitS,
                                    valueRange = 0f..15f,
                                    steps = 29,
                                    unit = "s",
                                    onChange = { v -> updateYt { it.copy(lateShotCorrectionLimitS = v) } }
                                )
                                SliderField(
                                    label = "Max rise rate",
                                    value = yt.maxPressureRiseBarPerS,
                                    valueRange = 0f..5f,
                                    steps = 49,
                                    unit = "bar/s",
                                    onChange = { v -> updateYt { it.copy(maxPressureRiseBarPerS = v) } }
                                )
                                SliderField(
                                    label = "Max fall rate",
                                    value = yt.maxPressureFallBarPerS,
                                    valueRange = 0f..5f,
                                    steps = 49,
                                    unit = "bar/s",
                                    onChange = { v -> updateYt { it.copy(maxPressureFallBarPerS = v) } }
                                )
                                SliderField(
                                    label = "Pre-infuse max",
                                    value = yt.preInfusionMaxS,
                                    valueRange = 0f..40f,
                                    steps = 79,
                                    unit = "s",
                                    onChange = { v -> updateYt { it.copy(preInfusionMaxS = v) } }
                                )
                            }
                        }
                        Text(
                            "“${yt.targetYieldG.toInt()} g in ${yt.targetDurationS.toInt()} s”, measured from first drop. " +
                                "Pre-infuses at ${yt.preInfusionPressureBar.toInt()} bar until the puck yields, then the " +
                                "planner runs the flow trajectory for ${yt.targetDurationS.toInt()} s — the pre-infusion " +
                                "time isn't charged against the recipe. The extraction floor holds at least " +
                                "${yt.minExtractionPressureBar.toInt()} bar through the shot (released on a gush) so the " +
                                "tail doesn't sag into under-extraction. Pressure stays within the limits above.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    StageType.PRESSURE_CURVE -> {
                        val pc = stage.pressureCurve ?: PressureCurveConfig(
                            axis = PressureCurveAxis.WEIGHT,
                            points = defaultPressurePoints(),
                            maxWeightG = profileTargetWeightG
                        )
                        fun updatePc(block: (PressureCurveConfig) -> PressureCurveConfig) {
                            onStageChange(stage.copy(pressureCurve = block(pc)))
                        }

                        Text(
                            "Axis",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = pc.axis == PressureCurveAxis.TIME,
                                onClick = {
                                    val next = pc.copy(axis = PressureCurveAxis.TIME)
                                    onStageChange(
                                        stage.copy(
                                            pressureCurve = next,
                                            exit = ExitCondition(stageTimeGteMs = (next.durationS * 1000).toLong())
                                        )
                                    )
                                },
                                label = { Text("Time") }
                            )
                            FilterChip(
                                selected = pc.axis == PressureCurveAxis.WEIGHT,
                                onClick = {
                                    val next = pc.copy(axis = PressureCurveAxis.WEIGHT)
                                    onStageChange(
                                        stage.copy(
                                            pressureCurve = next,
                                            exit = ExitCondition(weightGte = next.maxWeightG.coerceAtMost(profileTargetWeightG))
                                        )
                                    )
                                },
                                label = { Text("Weight") }
                            )
                        }

                        if (pc.axis == PressureCurveAxis.TIME) {
                            SliderField(
                                label = "Duration",
                                value = pc.durationS,
                                valueRange = 5f..90f,
                                steps = 0,
                                unit = "s",
                                decimals = 0,
                                onChange = { v -> updatePc { it.copy(durationS = v) } }
                            )
                        } else {
                            SliderField(
                                label = "Max weight",
                                value = pc.maxWeightG,
                                valueRange = ProfileConstraints.MIN_TARGET_WEIGHT_G.toFloat()..profileTargetWeightG.toFloat(),
                                steps = 0,
                                unit = "g",
                                decimals = 0,
                                onChange = { v ->
                                    val capped = v.coerceAtMost(profileTargetWeightG)
                                    onStageChange(
                                        stage.copy(
                                            pressureCurve = pc.copy(maxWeightG = capped),
                                            exit = stage.exit.copy(weightGte = stage.exit.weightGte?.coerceAtMost(capped))
                                        )
                                    )
                                }
                            )
                        }
                        SliderField(
                            label = "Max pressure",
                            value = pc.maxPressureBar,
                            valueRange = 1f..12f,
                            steps = 0,
                            unit = "bar",
                            onChange = { v -> updatePc { it.copy(maxPressureBar = v) } }
                        )

                        // Curve preview (drawn points or the default) + Edit button into the editor.
                        run {
                            val previewPts = pc.points.ifEmpty { defaultPressurePoints() }
                                .map { CurvePoint(it.xPct, it.pressureBar) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CurveThumbnail(
                                    points = previewPts,
                                    yMax = pc.maxPressureBar.coerceAtLeast(0.5),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(64.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                )
                                Column {
                                    Text(
                                        "Peak ${(pc.points.maxOfOrNull { it.pressureBar } ?: pc.maxPressureBar).formatDecimals(1)} bar",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    OutlinedButton(onClick = onEditCurve) { Text("Edit curve") }
                                }
                            }
                        }

                        Text(
                            "Commands the drawn pressure directly against " +
                                "${if (pc.axis == PressureCurveAxis.TIME) "stage time" else "cup weight"}, capped at " +
                                "${pc.maxPressureBar.toInt()} bar. No feedback — the curve is the schedule.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    StageType.STOP -> {
                        Text("No parameters", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (stage.type != StageType.STOP) {
                    HorizontalDivider()
                    ExitEditor(stage.exit, profileTargetWeightG) { onStageChange(stage.copy(exit = it)) }
                    SafetyEditor(stage.safety) { onStageChange(stage.copy(safety = it)) }
                }
            }
        }
    }
}

@Composable
private fun ExitEditor(
    exit: ExitCondition,
    profileTargetWeightG: Double,
    onExitChange: (ExitCondition) -> Unit
) {
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
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OptionalSliderField(
                    label = "Weight >=",
                    value = exit.weightGte,
                    valueRange = 0f..profileTargetWeightG.toFloat(),
                    steps = 0,
                    unit = "g",
                    defaultValue = profileTargetWeightG,
                    labelWidth = 80.dp,
                    onChange = { onExitChange(exit.copy(weightGte = it)) }
                )
                OptionalSliderDurationField(
                    label = "Stage time >=",
                    valueMs = exit.stageTimeGteMs,
                    valueRangeSeconds = 1f..120f,
                    steps = 0,
                    defaultValueMs = 5_000L,
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
        OptionalSliderDurationField(
            label = "Stage max time",
            valueMs = safety.maxStageTimeMs,
            valueRangeSeconds = 1f..120f,
            steps = 0,
            defaultValueMs = 20_000L,
            modifier = Modifier.weight(1f),
            onChange = { onSafetyChange(safety.copy(maxStageTimeMs = it)) }
        )
        LabeledSwitch("2 reads for first drop", safety.requireTwoConsecutiveFirstDropReadings) {
            onSafetyChange(safety.copy(requireTwoConsecutiveFirstDropReadings = it))
        }
    }
}
