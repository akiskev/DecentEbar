package dev.akiskev.decentebar.ui

import android.app.Application
import android.os.Build
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.akiskev.decentebar.accessibility.EbarAccessibilityService
import dev.akiskev.decentebar.engine.FlowEstimator
import dev.akiskev.decentebar.engine.PressureLutManager
import dev.akiskev.decentebar.model.BuiltInPressureLut
import dev.akiskev.decentebar.model.ControllerState
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.ExitMode
import dev.akiskev.decentebar.model.LutValidationResult
import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.SafetyConfig
import dev.akiskev.decentebar.model.ScreenSpec
import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotEventType
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.model.ShotSample
import dev.akiskev.decentebar.model.StageType
import dev.akiskev.decentebar.storage.JsonCodec
import dev.akiskev.decentebar.storage.ProfileRepository
import dev.akiskev.decentebar.storage.ShotLogCodec
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class MainUiState(
    val controllerState: ControllerState = ControllerState.IDLE,
    val serviceEnabled: Boolean = false,
    val snapshot: EbarSnapshot = EbarSnapshot(),
    val profiles: List<ShotProfile> = listOf(DefaultProfiles.firstDropFlowFade),
    val selectedProfile: ShotProfile = DefaultProfiles.firstDropFlowFade,
    val loadedLut: PressureLut? = null,
    val lutValidation: LutValidationResult = LutValidationResult.Missing,
    val currentWeightG: Double? = null,
    val currentFlowGps: Double = 0.0,
    val currentStageIndex: Int = -1,
    val commandedPressureBar: Double? = null,
    val elapsedShotTimeMs: Long = 0L,
    val safetyStatus: String = "Idle",
    val lastPressureCommand: String = "--",
    val lastStopCommand: String = "--",
    val lastSafetyError: String = "--",
    val profileMessage: String = "",
    val lutMessage: String = "",
    val logMessage: String = "",
    val exportedProfileJson: String = "",
    val exportedLutJson: String = "",
    val exportedLogJson: String = "",
    val samples: List<ShotSample> = emptyList(),
    val events: List<ShotEvent> = emptyList()
) {
    val isArmed: Boolean
        get() = controllerState == ControllerState.ARMED ||
            controllerState == ControllerState.RUNNING ||
            controllerState == ControllerState.STAGE_TRANSITION ||
            controllerState == ControllerState.STOPPING

    val currentStageName: String
        get() = selectedProfile.stages.getOrNull(currentStageIndex)?.name ?: "--"

    val stopAtWeightG: Double
        get() = selectedProfile.targetWeightG - selectedProfile.stopOffsetG
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val safetyConfig = SafetyConfig()
    private val profileRepository = ProfileRepository(application)
    private val lutManager = PressureLutManager(safetyConfig)
    private val flowEstimator = FlowEstimator(safetyConfig.maxFlowGps)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var shotStartMs: Long? = null
    private var shotStoppedMs: Long? = null
    private var stageStartMs: Long? = null
    private var stageEntryPressureBar: Double? = null
    private var lastValidWeightMs: Long? = null
    private var stopSent = false
    private var manualSkipRequested = false
    private var firstDropConsecutiveReadings = 0
    private var firstDropDetected = false

    init {
        val profiles = profileRepository.loadProfiles()
        val selected = profiles.firstOrNull() ?: DefaultProfiles.firstDropFlowFade
        val (initW, initH) = resolveScreenSize()
        val lut = BuiltInPressureLut.buildFor(initW, initH)
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = selected,
                loadedLut = lut,
                lutValidation = validateLut(it.snapshot, lut, requireForegroundPackage = false),
                exportedLutJson = ""
            )
        }

        viewModelScope.launch {
            EbarAccessibilityService.isEnabled.collect { enabled ->
                _uiState.update { it.copy(serviceEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            EbarAccessibilityService.snapshots.collect(::handleSnapshot)
        }
    }

    fun arm() {
        val service = EbarAccessibilityService.current()
        if (service == null) {
            fail("Accessibility service is not enabled", attemptStop = false)
            return
        }

        val freshSnapshot = service.captureSnapshot()
        val state = _uiState.value
        val validation = validateLut(freshSnapshot, state.loadedLut, requireForegroundPackage = false)
        val profileErrors = profileRepository.validateProfile(state.selectedProfile)

        when {
            !validation.isValid -> fail("Cannot arm: ${validation.displayText}", attemptStop = false)
            profileErrors.isNotEmpty() -> fail("Cannot arm: ${profileErrors.joinToString("; ")}", attemptStop = false)
            else -> {
                resetShotRuntime()
                stageStartMs = now()
                EbarAccessibilityService.setShouldRun(true)
                _uiState.update {
                    it.copy(
                        snapshot = freshSnapshot,
                        lutValidation = validation,
                        controllerState = ControllerState.ARMED,
                        currentStageIndex = 0,
                        currentFlowGps = 0.0,
                        currentWeightG = freshSnapshot.weightG,
                        commandedPressureBar = null,
                        elapsedShotTimeMs = 0L,
                        safetyStatus = "Armed; waiting for E-Bar shot screen",
                        lastSafetyError = "--",
                        samples = emptyList(),
                        events = emptyList(),
                        exportedLogJson = ""
                    )
                }
                recordEvent(ShotEventType.ARM, "Controller armed")
                recordState(ControllerState.ARMED, "Controller armed")
            }
        }
    }

    fun disarm() {
        resetShotRuntime()
        EbarAccessibilityService.setShouldRun(false)
        _uiState.update {
            it.copy(
                controllerState = ControllerState.IDLE,
                currentStageIndex = -1,
                safetyStatus = "Disarmed",
                elapsedShotTimeMs = 0L
            )
        }
        recordEvent(ShotEventType.DISARM, "Controller disarmed")
    }

    fun emergencyStop() {
        val nowMs = now()
        EbarAccessibilityService.setShouldRun(true)
        sendStop("Emergency stop", nowMs, _uiState.value.currentWeightG, explicitRetry = true)
    }

    fun manualSkipStage() {
        manualSkipRequested = true
        recordEvent(ShotEventType.INFO, "Manual stage skip requested")
    }

    fun resetShotLog() {
        _uiState.update {
            it.copy(samples = emptyList(), events = emptyList(), exportedLogJson = "")
        }
    }

    fun exportShotLog() {
        currentShotLogJson()
    }

    fun currentShotLogJson(): String {
        val state = _uiState.value
        if (state.samples.isEmpty() && state.events.isEmpty()) {
            _uiState.update { it.copy(logMessage = "No shot data to export") }
            return ""
        }
        val log = ShotLog(
            profileName = state.selectedProfile.name,
            startedAtMs = shotStartMs,
            stoppedAtMs = shotStoppedMs,
            samples = state.samples,
            events = state.events
        )
        val encoded = ShotLogCodec.encode(log)
        _uiState.update { it.copy(exportedLogJson = encoded) }
        return encoded
    }

    fun setProfileMessage(msg: String) {
        _uiState.update { it.copy(profileMessage = msg) }
    }

    fun setLogMessage(msg: String) {
        _uiState.update { it.copy(logMessage = msg) }
    }

    fun selectProfile(name: String) {
        val profile = _uiState.value.profiles.firstOrNull { it.name == name } ?: return
        _uiState.update {
            it.copy(selectedProfile = profile, profileMessage = "Selected ${profile.name}")
        }
    }

    fun saveProfile(profile: ShotProfile) {
        val errors = profileRepository.validateProfile(profile)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(profileMessage = errors.joinToString("; ")) }
            return
        }

        val profiles = profileRepository.upsert(profile)
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = profile,
                profileMessage = "Saved ${profile.name}",
                exportedProfileJson = profileRepository.exportProfile(profile)
            )
        }
    }

    fun deleteSelectedProfile() {
        val state = _uiState.value
        val profiles = profileRepository.delete(state.selectedProfile.name)
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = profiles.first(),
                profileMessage = "Deleted ${state.selectedProfile.name}"
            )
        }
    }

    fun duplicateSelectedProfile() {
        val profiles = profileRepository.duplicate(_uiState.value.selectedProfile)
        val duplicated = profiles.last()
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = duplicated,
                profileMessage = "Duplicated as ${duplicated.name}"
            )
        }
    }

    fun exportSelectedProfile() {
        selectedProfileJson()
    }

    fun selectedProfileJson(): String {
        val profile = _uiState.value.selectedProfile
        val encoded = profileRepository.exportProfile(profile)
        _uiState.update {
            it.copy(exportedProfileJson = encoded, profileMessage = "Exported ${profile.name}")
        }
        return encoded
    }

    fun importProfileJson(rawJson: String) {
        profileRepository.importProfile(rawJson)
            .onSuccess { profile ->
                val profiles = profileRepository.upsert(profile)
                _uiState.update {
                    it.copy(
                        profiles = profiles,
                        selectedProfile = profile,
                        profileMessage = "Imported ${profile.name}",
                        exportedProfileJson = profileRepository.exportProfile(profile)
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(profileMessage = "Import failed: ${error.message}") }
            }
    }

    fun exportLut() {
        val lut = _uiState.value.loadedLut
        _uiState.update {
            it.copy(
                exportedLutJson = lut?.let { JsonCodec.json.encodeToString(it) }.orEmpty(),
                lutMessage = if (lut == null) "No LUT (screen size unknown)" else "Exported ${lut.name}"
            )
        }
    }

    fun testPressure(pressureText: String) {
        val pressure = pressureText.toDoubleOrNull()
        if (pressure == null) {
            _uiState.update { it.copy(lutMessage = "Enter a valid pressure") }
            return
        }
        EbarAccessibilityService.current()?.captureSnapshot()?.let { fresh ->
            val validation = validateLut(fresh, _uiState.value.loadedLut, requireForegroundPackage = false)
            _uiState.update { it.copy(snapshot = fresh, lutValidation = validation) }
        }
        commandPressure(pressure, now(), force = true, source = "Manual LUT test")
    }

    private fun handleSnapshot(snapshot: EbarSnapshot) {
        val nowMs = snapshot.timestampMs.takeIf { it > 0L } ?: now()
        val state = _uiState.value
        val nextLut = lutForScreen(snapshot.screenWidth, snapshot.screenHeight, state.loadedLut)
        val validation = validateLut(snapshot, nextLut, requireForegroundPackage = false)
        val nextWeight = snapshot.weightG ?: state.currentWeightG
        val serviceEnabled = EbarAccessibilityService.current() != null

        val needsUpdate = !snapshotsMaterialEqual(state.snapshot, snapshot) ||
            state.lutValidation != validation ||
            state.currentWeightG != nextWeight ||
            state.serviceEnabled != serviceEnabled ||
            state.loadedLut !== nextLut

        if (needsUpdate) {
            _uiState.update {
                it.copy(
                    snapshot = snapshot,
                    serviceEnabled = serviceEnabled,
                    loadedLut = nextLut,
                    lutValidation = validation,
                    currentWeightG = nextWeight
                )
            }
        }

        when (state.controllerState) {
            ControllerState.IDLE,
            ControllerState.STOPPED,
            ControllerState.ERROR -> Unit
            ControllerState.ARMED -> handleArmedSnapshot(snapshot, nowMs)
            ControllerState.RUNNING,
            ControllerState.STAGE_TRANSITION -> handleRunningSnapshot(snapshot, nowMs)
            ControllerState.STOPPING -> handleStoppingSnapshot(snapshot, nowMs)
        }
    }

    private fun snapshotsMaterialEqual(a: EbarSnapshot, b: EbarSnapshot): Boolean {
        return a.isForeground == b.isForeground &&
            a.activePackage == b.activePackage &&
            a.hasStart == b.hasStart &&
            a.hasStop == b.hasStop &&
            a.hasWeigh == b.hasWeigh &&
            a.hasPressurePriority == b.hasPressurePriority &&
            a.hasFlowRatePriority == b.hasFlowRatePriority &&
            a.hasPressureLabel == b.hasPressureLabel &&
            a.weightG == b.weightG &&
            a.screenWidth == b.screenWidth &&
            a.screenHeight == b.screenHeight &&
            a.orientation == b.orientation &&
            a.rawDescriptions == b.rawDescriptions &&
            a.rawTexts == b.rawTexts
    }

    private fun handleArmedSnapshot(snapshot: EbarSnapshot, nowMs: Long) {
        if (!snapshot.isForeground) {
            _uiState.update { it.copy(safetyStatus = "Armed; waiting for E-Bar foreground") }
            return
        }

        if (snapshot.hasStop) {
            beginRunning(nowMs, snapshot)
            return
        }

        if (!snapshot.hasStart) {
            fail("Neither Start nor Stop is visible while armed", attemptStop = false)
        } else {
            _uiState.update { it.copy(safetyStatus = "Armed; waiting for shot start") }
        }
    }

    private fun beginRunning(nowMs: Long, snapshot: EbarSnapshot) {
        shotStartMs = nowMs
        stageStartMs = nowMs
        lastValidWeightMs = snapshot.weightG?.let { nowMs }
        flowEstimator.reset()
        lutManager.resetThrottle()
        firstDropConsecutiveReadings = 0
        firstDropDetected = false
        stopSent = false
        recordState(ControllerState.RUNNING, "Shot running")
        _uiState.update {
            it.copy(
                controllerState = ControllerState.RUNNING,
                currentStageIndex = 0,
                elapsedShotTimeMs = 0L,
                safetyStatus = "Running",
                currentWeightG = snapshot.weightG
            )
        }
    }

    private fun handleRunningSnapshot(snapshot: EbarSnapshot, nowMs: Long) {
        if (!snapshot.isForeground) {
            fail("E-Bar moved out of foreground during shot", attemptStop = false)
            return
        }

        if (!snapshot.hasStop && shotStartMs != null) {
            finishStopped(nowMs, "Stop disappeared from E-Bar")
            return
        }

        val shotStart = shotStartMs ?: nowMs.also { shotStartMs = it }
        val elapsedMs = nowMs - shotStart
        val profile = _uiState.value.selectedProfile
        val weight = snapshot.weightG

        if (weight == null) {
            val missingSince = lastValidWeightMs ?: shotStart
            if (nowMs - missingSince > safetyConfig.missingWeightTimeoutMs) {
                fail("Missing live weight for more than ${safetyConfig.missingWeightTimeoutMs}ms", attemptStop = true)
            }
            return
        }

        lastValidWeightMs = nowMs
        val flow = flowEstimator.addSample(nowMs, weight)
        updateFirstDrop(weight)

        _uiState.update {
            it.copy(
                currentWeightG = weight,
                currentFlowGps = flow,
                elapsedShotTimeMs = elapsedMs,
                safetyStatus = "Running"
            )
        }

        appendSample(nowMs, weight, flow)

        if (elapsedMs >= profile.maxShotTimeMs) {
            sendStop("Max shot time reached", nowMs, weight)
            return
        }

        if (weight >= profile.targetWeightG - profile.stopOffsetG) {
            sendStop("Target stop threshold reached", nowMs, weight)
            return
        }

        runCurrentStage(nowMs, weight, flow)
    }

    private fun handleStoppingSnapshot(snapshot: EbarSnapshot, nowMs: Long) {
        val shotStart = shotStartMs
        _uiState.update {
            it.copy(
                snapshot = snapshot,
                elapsedShotTimeMs = if (shotStart == null) it.elapsedShotTimeMs else nowMs - shotStart
            )
        }

        if (snapshot.isForeground && !snapshot.hasStop) {
            finishStopped(nowMs, "Shot stopped")
        }
    }

    private fun runCurrentStage(nowMs: Long, weight: Double, flow: Double) {
        val state = _uiState.value
        val profile = state.selectedProfile
        val stage = profile.stages.getOrNull(state.currentStageIndex) ?: run {
            sendStop("Profile stages exhausted", nowMs, weight)
            return
        }

        val stageElapsedMs = nowMs - (stageStartMs ?: nowMs)
        val safetyTimeout = stage.safety.maxStageTimeMs?.let { stageElapsedMs >= it } ?: false
        if (safetyTimeout) manualSkipRequested = true

        when (stage.type) {
            StageType.FIXED_PRESSURE -> stage.fixedPressureBar?.let {
                commandPressure(it, nowMs, source = stage.name)
            }
            StageType.TIME_BASED_PRESSURE_RAMP -> {
                val start = stage.rampStartPressureBar ?: state.commandedPressureBar ?: safetyConfig.minPressureBar
                val end = stage.rampEndPressureBar ?: start
                val duration = max(1L, stage.rampDurationMs ?: 1L)
                val progress = (stageElapsedMs.toDouble() / duration).coerceIn(0.0, 1.0)
                commandPressure(lerp(start, end, progress), nowMs, source = stage.name)
            }
            StageType.FLOW_LIMITED_PRESSURE -> runFlowLimitedStage(nowMs, flow, stage.name)
            StageType.WEIGHT_BASED_PRESSURE_RAMP -> {
                val startWeight = stage.rampStartWeightG ?: weight
                val endWeight = stage.rampEndWeightG ?: startWeight
                val startPressure = stage.rampStartPressureBar
                    ?: stageEntryPressureBar
                    ?: state.commandedPressureBar
                    ?: safetyConfig.maxPressureBar
                val endPressure = stage.rampEndPressureBar ?: startPressure
                val progress = if (endWeight == startWeight) 1.0 else {
                    ((weight - startWeight) / (endWeight - startWeight)).coerceIn(0.0, 1.0)
                }
                commandPressure(lerp(startPressure, endPressure, progress), nowMs, source = stage.name)
            }
            StageType.STOP -> {
                sendStop("Stop stage reached", nowMs, weight)
                return
            }
        }

        if (shouldExitStage(nowMs, weight, flow, safetyTimeout)) {
            advanceStage(nowMs, weight)
        }
    }

    private fun runFlowLimitedStage(nowMs: Long, flow: Double, stageName: String) {
        val state = _uiState.value
        val stage = state.selectedProfile.stages.getOrNull(state.currentStageIndex) ?: return
        val target = stage.targetFlowGps ?: return
        val deadband = stage.flowDeadbandGps ?: 0.0
        val step = stage.pressureStepBar ?: 0.0
        val cap = min(stage.pressureCapBar ?: safetyConfig.maxPressureBar, safetyConfig.maxPressureBar)
        val currentPressure = state.commandedPressureBar ?: min(cap, stageEntryPressureBar ?: cap)
        val nextPressure = when {
            flow > target + deadband -> currentPressure - step
            flow < target - deadband -> currentPressure + step
            else -> currentPressure
        }.coerceIn(safetyConfig.minPressureBar, cap)

        if (nextPressure != currentPressure || state.commandedPressureBar == null) {
            commandPressure(nextPressure, nowMs, source = stageName)
        }
    }

    private fun shouldExitStage(nowMs: Long, weight: Double, flow: Double, safetyTimeout: Boolean): Boolean {
        val state = _uiState.value
        val stage = state.selectedProfile.stages.getOrNull(state.currentStageIndex) ?: return true
        val stageElapsedMs = nowMs - (stageStartMs ?: nowMs)
        val exit = stage.exit
        val conditions = buildList {
            exit.weightGte?.let { add(weight >= it) }
            exit.stageTimeGteMs?.let { add(stageElapsedMs >= it) }
            exit.flowGte?.let { add(flow >= it) }
            exit.flowLte?.let { add(flow <= it) }
            if (exit.firstDropDetected) add(firstDropDetected)
            if (exit.manualSkip || manualSkipRequested) add(manualSkipRequested)
            if (exit.safetyTimeout || safetyTimeout) add(safetyTimeout || manualSkipRequested)
        }

        if (conditions.isEmpty()) return false
        return when (exit.mode) {
            ExitMode.ANY -> conditions.any { it }
            ExitMode.ALL -> conditions.all { it }
        }
    }

    private fun advanceStage(nowMs: Long, weight: Double) {
        val state = _uiState.value
        val profile = state.selectedProfile
        val previousStage = profile.stages.getOrNull(state.currentStageIndex)
        val nextIndex = state.currentStageIndex + 1

        recordEvent(
            ShotEventType.STAGE_EXIT,
            "Exit ${previousStage?.name ?: "stage"} at ${weight.format(1)} g",
            weightG = weight
        )

        manualSkipRequested = false
        stageStartMs = nowMs
        stageEntryPressureBar = state.commandedPressureBar

        if (nextIndex >= profile.stages.size) {
            sendStop("Profile complete", nowMs, weight)
            return
        }

        _uiState.update {
            it.copy(
                controllerState = ControllerState.STAGE_TRANSITION,
                currentStageIndex = nextIndex,
                safetyStatus = "Entering ${profile.stages[nextIndex].name}"
            )
        }
        recordState(ControllerState.STAGE_TRANSITION, "Entering ${profile.stages[nextIndex].name}")
        _uiState.update { it.copy(controllerState = ControllerState.RUNNING) }

        if (profile.stages[nextIndex].type == StageType.STOP) {
            sendStop("Stop stage reached", nowMs, weight)
        }
    }

    private fun commandPressure(
        requestedPressureBar: Double,
        nowMs: Long,
        force: Boolean = false,
        source: String
    ) {
        val state = _uiState.value
        val service = EbarAccessibilityService.current()
        if (service == null) {
            fail("Accessibility service unavailable before pressure command", attemptStop = false)
            return
        }

        val result = lutManager.requestPressure(
            lut = state.loadedLut,
            screen = screenSpec(state.snapshot),
            requestedPressureBar = requestedPressureBar,
            nowMs = nowMs,
            force = force
        )

        if (!result.accepted) {
            _uiState.update {
                it.copy(lastPressureCommand = result.message)
            }
            return
        }

        val point = result.point ?: return
        val tapped = service.dispatchTap(point.x, point.y)
        if (!tapped) {
            fail("Failed to dispatch pressure tap", attemptStop = false)
            return
        }

        val pressure = result.pressureBar ?: requestedPressureBar
        _uiState.update {
            it.copy(
                commandedPressureBar = pressure,
                lastPressureCommand = "$source: ${pressure.format(2)} bar -> ${point.x.toInt()},${point.y.toInt()}"
            )
        }
        recordEvent(
            ShotEventType.PRESSURE_COMMAND,
            "$source commanded ${pressure.format(2)} bar",
            pressureBar = pressure
        )
    }

    private fun sendStop(
        reason: String,
        nowMs: Long,
        weight: Double?,
        explicitRetry: Boolean = false
    ) {
        if (stopSent && !explicitRetry) return

        val service = EbarAccessibilityService.current()
        if (service == null) {
            fail("Accessibility service unavailable before Stop command", attemptStop = false)
            return
        }

        stopSent = true
        val sent = service.clickStopOrFallback(safetyConfig)
        val message = if (sent) reason else "$reason; Stop dispatch failed"
        _uiState.update {
            it.copy(
                controllerState = if (sent) ControllerState.STOPPING else ControllerState.ERROR,
                safetyStatus = if (sent) "Stopping" else message,
                lastStopCommand = "${nowMs}: $message"
            )
        }
        recordEvent(ShotEventType.STOP_COMMAND, message, weightG = weight)
        recordState(if (sent) ControllerState.STOPPING else ControllerState.ERROR, message)
        if (!sent) resetRuntimeAfterTerminalState()
    }

    private fun finishStopped(nowMs: Long, reason: String) {
        shotStoppedMs = nowMs
        EbarAccessibilityService.setShouldRun(false)
        _uiState.update {
            it.copy(
                controllerState = ControllerState.STOPPED,
                safetyStatus = reason,
                elapsedShotTimeMs = shotStartMs?.let { started -> nowMs - started } ?: it.elapsedShotTimeMs
            )
        }
        recordState(ControllerState.STOPPED, reason)
        resetRuntimeAfterTerminalState()
    }

    private fun fail(message: String, attemptStop: Boolean) {
        val nowMs = now()
        val weight = _uiState.value.currentWeightG
        if (attemptStop && !stopSent) {
            EbarAccessibilityService.current()?.clickStopOrFallback(safetyConfig)
            stopSent = true
            recordEvent(ShotEventType.STOP_COMMAND, "Safety stop attempted: $message", weightG = weight)
        }

        EbarAccessibilityService.setShouldRun(false)
        _uiState.update {
            it.copy(
                controllerState = ControllerState.ERROR,
                safetyStatus = message,
                lastSafetyError = message
            )
        }
        recordEvent(ShotEventType.SAFETY_ERROR, message, weightG = weight)
        recordState(ControllerState.ERROR, message)
        resetRuntimeAfterTerminalState()
    }

    private fun updateFirstDrop(weight: Double) {
        if (firstDropDetected) return

        if (weight >= safetyConfig.firstDropThresholdG) {
            firstDropConsecutiveReadings += 1
        } else {
            firstDropConsecutiveReadings = 0
        }

        val currentStage = _uiState.value.selectedProfile.stages.getOrNull(_uiState.value.currentStageIndex)
        val requiresTwo = currentStage?.safety?.requireTwoConsecutiveFirstDropReadings == true
        val detected = if (requiresTwo) firstDropConsecutiveReadings >= 2 else firstDropConsecutiveReadings >= 1

        if (detected) {
            firstDropDetected = true
            recordEvent(ShotEventType.FIRST_DROP, "First drop detected at ${weight.format(1)} g", weightG = weight)
        }
    }

    private fun appendSample(nowMs: Long, weight: Double, flow: Double) {
        val state = _uiState.value
        val shotStart = shotStartMs ?: nowMs
        val sample = ShotSample(
            timeMs = nowMs - shotStart,
            weightG = weight,
            flowGps = flow,
            commandedPressureBar = state.commandedPressureBar,
            stageName = state.currentStageName
        )
        _uiState.update { it.copy(samples = (it.samples + sample).takeLast(MAX_LOG_ITEMS)) }
    }

    private fun recordState(state: ControllerState, message: String) {
        recordEvent(ShotEventType.STATE_TRANSITION, "${state.name}: $message")
    }

    private fun recordEvent(
        type: ShotEventType,
        message: String,
        weightG: Double? = null,
        pressureBar: Double? = null
    ) {
        val shotStart = shotStartMs
        val eventTime = if (shotStart == null) 0L else now() - shotStart
        val event = ShotEvent(
            timeMs = eventTime,
            type = type,
            message = message,
            weightG = weightG,
            pressureBar = pressureBar
        )
        _uiState.update { it.copy(events = (it.events + event).takeLast(MAX_LOG_ITEMS)) }
    }

    private fun resetShotRuntime() {
        shotStartMs = null
        shotStoppedMs = null
        stageStartMs = null
        stageEntryPressureBar = null
        lastValidWeightMs = null
        stopSent = false
        manualSkipRequested = false
        firstDropConsecutiveReadings = 0
        firstDropDetected = false
        flowEstimator.reset()
        lutManager.resetThrottle()
    }

    private fun resetRuntimeAfterTerminalState() {
        stageStartMs = null
        stageEntryPressureBar = null
        lastValidWeightMs = null
        manualSkipRequested = false
        flowEstimator.reset()
        lutManager.resetThrottle()
    }

    private fun validateLut(
        snapshot: EbarSnapshot,
        lut: PressureLut?,
        requireForegroundPackage: Boolean
    ): LutValidationResult {
        return lutManager.validate(lut, screenSpec(snapshot), requireForegroundPackage)
    }

    private fun screenSpec(snapshot: EbarSnapshot): ScreenSpec {
        return ScreenSpec(
            width = snapshot.screenWidth,
            height = snapshot.screenHeight,
            orientation = snapshot.orientation,
            packageName = snapshot.activePackage
        )
    }

    private fun lutForScreen(width: Int, height: Int, current: PressureLut?): PressureLut? {
        if (width <= 0 || height <= 0) return current
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        if (current != null && current.screenWidth == longSide && current.screenHeight == shortSide) return current
        return BuiltInPressureLut.buildFor(width, height) ?: current
    }

    private fun resolveScreenSize(): Pair<Int, Int> {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = app.getSystemService(WindowManager::class.java)
            val bounds = wm?.maximumWindowMetrics?.bounds
            if (bounds != null) bounds.width() to bounds.height() else 0 to 0
        } else {
            @Suppress("DEPRECATION")
            val m = app.resources.displayMetrics
            m.widthPixels to m.heightPixels
        }
    }

    private fun lerp(start: Double, end: Double, progress: Double): Double {
        return start + (end - start) * progress
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun Double.format(decimals: Int): String {
        return String.format(Locale.US, "%.${decimals}f", this)
    }

    companion object {
        private const val MAX_LOG_ITEMS = 2_000
    }
}
