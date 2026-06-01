package dev.akiskev.decentebar.ui

import dev.akiskev.decentebar.accessibility.EbarAccessibilityService
import dev.akiskev.decentebar.ble.ScaleConnectionState
import dev.akiskev.decentebar.ble.ScaleReading
import dev.akiskev.decentebar.engine.FlowEstimator
import dev.akiskev.decentebar.engine.PressureLutManager
import dev.akiskev.decentebar.model.BuiltInPressureLut
import dev.akiskev.decentebar.model.ControllerState
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.ExitMode
import dev.akiskev.decentebar.model.LutValidationResult
import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.ProfileValidator
import dev.akiskev.decentebar.model.SafetyConfig
import dev.akiskev.decentebar.model.ScreenSpec
import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotEventType
import dev.akiskev.decentebar.model.ShotSample
import dev.akiskev.decentebar.model.StageType
import dev.akiskev.decentebar.util.formatDecimals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the shot state machine: ARMED → RUNNING → STAGE_TRANSITION → STOPPING → STOPPED / ERROR.
 * It reads weight/flow from either accessibility snapshots ([onSnapshot]) or a BLE scale
 * ([onScaleReading]), drives the per-stage pressure logic, applies safety stops, and records
 * samples/events into the shared [state] flow.
 *
 * Threading: [onSnapshot] and [onScaleReading] mutate the runtime `var`s below with no locking.
 * That is only safe because the ViewModel collects both source flows on Dispatchers.Main.immediate
 * — every call is confined to the main thread and runs to completion before the next. Do NOT call
 * into this controller from a background thread without adding synchronization.
 */
class ShotController(
    private val state: MutableStateFlow<MainUiState>,
    private val safetyConfig: SafetyConfig,
    private val lutManager: PressureLutManager,
    private val flowEstimator: FlowEstimator,
    private val startScaleTimer: () -> Unit,
) {
    var shotStartMs: Long? = null
        private set
    var shotStoppedMs: Long? = null
        private set

    private var stageStartMs: Long? = null
    private var stageEntryPressureBar: Double? = null
    private var lastValidWeightMs: Long? = null
    private var stopSent = false
    private var manualSkipRequested = false
    private var firstDropConsecutiveReadings = 0
    private var firstDropDetected = false
    private var flowCapWarningLoggedForCurrentStage = false
    private var lastFlowCorrectionMs: Long? = null
    private var lastCorrectionFlowGps: Double? = null

    fun arm() {
        val service = EbarAccessibilityService.current()
        if (service == null) {
            fail("Accessibility service is not enabled", attemptStop = false)
            return
        }

        val freshSnapshot = service.captureSnapshot()
        val current = state.value
        val validation = validateLut(freshSnapshot, current.loadedLut, requireForegroundPackage = false)
        val profileErrors = ProfileValidator.validate(current.selectedProfile)

        when {
            !validation.isValid -> fail("Cannot arm: ${validation.displayText}", attemptStop = false)
            profileErrors.isNotEmpty() -> fail("Cannot arm: ${profileErrors.joinToString("; ")}", attemptStop = false)
            else -> {
                resetShotRuntime()
                stageStartMs = now()
                EbarAccessibilityService.setShouldRun(true)
                state.update {
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
        state.update {
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
        sendStop("Emergency stop", nowMs, state.value.currentWeightG, explicitRetry = true)
    }

    fun manualSkipStage() {
        manualSkipRequested = true
        recordEvent(ShotEventType.INFO, "Manual stage skip requested")
    }

    fun onSnapshot(snapshot: EbarSnapshot) {
        val nowMs = snapshot.timestampMs.takeIf { it > 0L } ?: now()
        val current = state.value
        val nextLut = lutForScreen(snapshot.screenWidth, snapshot.screenHeight, current.loadedLut)
        val validation = validateLut(snapshot, nextLut, requireForegroundPackage = false)
        val nextWeight = snapshot.weightG ?: current.currentWeightG
        val serviceEnabled = EbarAccessibilityService.current() != null

        val needsUpdate = !snapshotsMaterialEqual(current.snapshot, snapshot) ||
            current.lutValidation != validation ||
            current.currentWeightG != nextWeight ||
            current.serviceEnabled != serviceEnabled ||
            current.loadedLut !== nextLut

        if (needsUpdate) {
            state.update {
                it.copy(
                    snapshot = snapshot,
                    serviceEnabled = serviceEnabled,
                    loadedLut = nextLut,
                    lutValidation = validation,
                    currentWeightG = nextWeight
                )
            }
        }

        when (current.controllerState) {
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
            state.update { it.copy(safetyStatus = "Armed; waiting for E-Bar foreground") }
            return
        }

        if (snapshot.hasStop) {
            beginRunning(nowMs, snapshot)
            return
        }

        if (!snapshot.hasStart) {
            fail("Neither Start nor Stop is visible while armed", attemptStop = false)
        } else {
            state.update { it.copy(safetyStatus = "Armed; waiting for shot start") }
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
        if (state.value.scaleConnectionState == ScaleConnectionState.CONNECTED) {
            startScaleTimer()
        }
        recordState(ControllerState.RUNNING, "Shot running")
        state.update {
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

        // When scale is connected it drives weight/flow and calls runCurrentStage directly.
        // Snapshots still handle safety (foreground, stop-button disappearance) and the
        // missing-data watchdog.
        if (state.value.scaleConnectionState == ScaleConnectionState.CONNECTED) {
            val missingSince = lastValidWeightMs ?: shotStart
            if (nowMs - missingSince > safetyConfig.missingWeightTimeoutMs) {
                fail("Scale: no weight data for ${safetyConfig.missingWeightTimeoutMs}ms", attemptStop = true)
            }
            state.update { it.copy(elapsedShotTimeMs = elapsedMs, safetyStatus = "Running") }
            return
        }

        val profile = state.value.selectedProfile
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

        state.update {
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

    fun onScaleReading(reading: ScaleReading) {
        val nowMs = reading.timestampMs
        val current = state.value
        val weight = reading.weightG
        val scaleFlow = reading.flowGps
        // Run our software estimator in parallel — result logged as altFlowGps for comparison
        val calcFlow = flowEstimator.addSample(nowMs, weight)

        when (current.controllerState) {
            ControllerState.RUNNING, ControllerState.STAGE_TRANSITION -> {
                val shotStart = shotStartMs ?: return
                val elapsedMs = nowMs - shotStart

                lastValidWeightMs = nowMs
                updateFirstDrop(weight)

                state.update {
                    it.copy(
                        currentWeightG = weight,
                        currentFlowGps = scaleFlow,
                        currentCalcFlowGps = calcFlow,
                        elapsedShotTimeMs = elapsedMs,
                        scaleBatteryPercent = reading.batteryPercent,
                        safetyStatus = "Running"
                    )
                }

                appendSample(nowMs, weight, scaleFlow, altFlowGps = calcFlow)

                val profile = current.selectedProfile
                if (elapsedMs >= profile.maxShotTimeMs) {
                    sendStop("Max shot time reached", nowMs, weight)
                    return
                }
                if (weight >= profile.targetWeightG - profile.stopOffsetG) {
                    sendStop("Target stop threshold reached", nowMs, weight)
                    return
                }

                runCurrentStage(nowMs, weight, scaleFlow)
            }
            else -> {
                state.update {
                    it.copy(
                        currentWeightG = weight,
                        currentFlowGps = scaleFlow,
                        currentCalcFlowGps = calcFlow,
                        scaleBatteryPercent = reading.batteryPercent
                    )
                }
            }
        }
    }

    private fun handleStoppingSnapshot(snapshot: EbarSnapshot, nowMs: Long) {
        val shotStart = shotStartMs
        state.update {
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
        val current = state.value
        val profile = current.selectedProfile
        val stage = profile.stages.getOrNull(current.currentStageIndex) ?: run {
            sendStop("Profile stages exhausted", nowMs, weight)
            return
        }

        val stageElapsedMs = nowMs - (stageStartMs ?: nowMs)
        val safetyTimeout = stage.safety.maxStageTimeMs?.let { stageElapsedMs >= it } ?: false

        when (stage.type) {
            StageType.FIXED_PRESSURE -> stage.fixedPressureBar?.let {
                commandPressure(it, nowMs, source = stage.name)
            }
            StageType.TIME_BASED_PRESSURE_RAMP -> {
                val start = stage.rampStartPressureBar ?: current.commandedPressureBar ?: safetyConfig.minPressureBar
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
                    ?: current.commandedPressureBar
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

        val reason = exitReason(nowMs, weight, flow, safetyTimeout)
        if (reason != null) {
            advanceStage(nowMs, weight, reason)
        }
    }

    private fun runFlowLimitedStage(nowMs: Long, flow: Double, stageName: String) {
        val current = state.value
        val stage = current.selectedProfile.stages.getOrNull(current.currentStageIndex) ?: return
        val target = stage.targetFlowGps ?: return
        val cap = min(stage.pressureCapBar ?: safetyConfig.maxPressureBar, safetyConfig.maxPressureBar)
        val currentPressure = current.commandedPressureBar ?: min(cap, stageEntryPressureBar ?: cap)

        // Auto-tune: with BLE scale the control loop runs much faster, so use a shorter
        // correction interval and a proportionally smaller step to keep the max rate of
        // pressure change constant (bar/s) regardless of interval.
        val scaleOn = current.scaleConnectionState == ScaleConnectionState.CONNECTED
        val autoIntervalMs = if (scaleOn) 250L else 600L
        val intervalMs = stage.correctionIntervalMs ?: autoIntervalMs
        val autoStep = 0.6 * (autoIntervalMs.toDouble() / 600.0)  // 0.25 with BLE, 0.6 without
        val step = stage.pressureStepBar ?: autoStep
        val deadband = stage.flowDeadbandGps ?: 0.1
        val maxMult = stage.pressureStepMultiplierMax ?: 8.0

        val lastCorrection = lastFlowCorrectionMs
        val dueForCorrection = lastCorrection == null || (nowMs - lastCorrection) >= intervalMs

        if (dueForCorrection) {
            val error = flow - target
            val absError = abs(error)

            // Derivative guard: if the previous correction is already moving flow toward the
            // target, reduce the multiplier to 30% to avoid stacking corrections faster than
            // the system can respond (dead-time over-correction).
            val lastFlow = lastCorrectionFlowGps
            val movingTowardTarget = when {
                error > deadband -> lastFlow != null && flow < lastFlow
                error < -deadband -> lastFlow != null && flow > lastFlow
                else -> false
            }
            val rawMultiplier = if (absError > deadband) (absError / deadband).coerceAtMost(maxMult) else 0.0
            val multiplier = if (movingTowardTarget) rawMultiplier * 0.3 else rawMultiplier
            val scaledStep = step * multiplier

            val nextPressure = when {
                flow > target + deadband -> currentPressure - scaledStep
                flow < target - deadband -> currentPressure + scaledStep
                else -> currentPressure
            }.coerceIn(safetyConfig.minPressureBar, cap)

            if (nextPressure != currentPressure || current.commandedPressureBar == null) {
                // Only advance the correction timer when the LUT actually accepts the command.
                // If it throttles (too soon after last tap), leave lastFlowCorrectionMs unchanged
                // so we retry on the next check instead of wasting the correction budget.
                val accepted = commandPressure(nextPressure, nowMs, source = stageName)
                if (accepted) {
                    lastFlowCorrectionMs = nowMs
                    lastCorrectionFlowGps = flow
                }
            } else {
                // In deadband — no pressure change needed, but still advance the timer so we
                // don't check again on every single BLE notification.
                lastFlowCorrectionMs = nowMs
                lastCorrectionFlowGps = flow
            }

            if (!flowCapWarningLoggedForCurrentStage && nextPressure >= cap && flow < target - deadband) {
                flowCapWarningLoggedForCurrentStage = true
                recordEvent(
                    ShotEventType.INFO,
                    "$stageName: flow ${flow.fmt(2)} g/s below target ${target.fmt(2)} g/s — pressure capped at ${cap.fmt(2)} bar"
                )
            }
        }
    }

    private fun exitReason(nowMs: Long, weight: Double, flow: Double, safetyTimeout: Boolean): String? {
        val current = state.value
        val stage = current.selectedProfile.stages.getOrNull(current.currentStageIndex) ?: return null
        val stageElapsedMs = nowMs - (stageStartMs ?: nowMs)
        val exit = stage.exit

        // Unconditional overrides: the manual-skip button and the per-stage hard time cap
        // (StageSafety.maxStageTimeMs) always advance the stage, regardless of ExitMode. This
        // guarantees the user can always skip a stage and that a stuck stage always times out,
        // even when the configured exit conditions use ALL mode and never all become true.
        if (manualSkipRequested) return "manual skip"
        if (safetyTimeout) return "safety timeout"

        data class Cond(val triggered: Boolean, val reason: String)

        val conditions = buildList<Cond> {
            exit.weightGte?.let { add(Cond(weight >= it, "weight ${weight.fmt(1)}g ≥ ${it.fmt(1)}g")) }
            exit.stageTimeGteMs?.let { ms -> add(Cond(stageElapsedMs >= ms, "time limit ${ms}ms reached")) }
            exit.flowGte?.let { add(Cond(flow >= it, "flow ${flow.fmt(2)} g/s ≥ ${it.fmt(2)} g/s")) }
            exit.flowLte?.let { add(Cond(flow <= it, "flow ${flow.fmt(2)} g/s ≤ ${it.fmt(2)} g/s")) }
            if (exit.firstDropDetected) add(Cond(firstDropDetected, "first drop detected"))
        }

        if (conditions.isEmpty()) return null
        return when (exit.mode) {
            ExitMode.ANY -> conditions.firstOrNull { it.triggered }?.reason
            ExitMode.ALL -> if (conditions.all { it.triggered }) conditions.joinToString(", ") { it.reason } else null
        }
    }

    private fun advanceStage(nowMs: Long, weight: Double, exitReason: String) {
        val current = state.value
        val profile = current.selectedProfile
        val previousStage = profile.stages.getOrNull(current.currentStageIndex)
        val nextIndex = current.currentStageIndex + 1

        flowCapWarningLoggedForCurrentStage = false
        lastFlowCorrectionMs = null
        lastCorrectionFlowGps = null
        recordEvent(
            ShotEventType.STAGE_EXIT,
            "Exit ${previousStage?.name ?: "stage"} — $exitReason at ${weight.fmt(1)}g",
            weightG = weight
        )

        manualSkipRequested = false
        stageStartMs = nowMs
        stageEntryPressureBar = current.commandedPressureBar

        if (nextIndex >= profile.stages.size) {
            sendStop("Profile complete", nowMs, weight)
            return
        }

        state.update {
            it.copy(
                controllerState = ControllerState.RUNNING,
                currentStageIndex = nextIndex,
                safetyStatus = "Entering ${profile.stages[nextIndex].name}"
            )
        }
        recordState(ControllerState.RUNNING, "Entering ${profile.stages[nextIndex].name}")

        if (profile.stages[nextIndex].type == StageType.STOP) {
            sendStop("Stop stage reached", nowMs, weight)
        }
    }

    fun commandPressure(
        requestedPressureBar: Double,
        nowMs: Long,
        force: Boolean = false,
        source: String
    ): Boolean {
        val current = state.value
        val service = EbarAccessibilityService.current()
        if (service == null) {
            fail("Accessibility service unavailable before pressure command", attemptStop = false)
            return false
        }

        val result = lutManager.requestPressure(
            lut = current.loadedLut,
            screen = screenSpec(current.snapshot),
            requestedPressureBar = requestedPressureBar,
            nowMs = nowMs,
            force = force
        )

        if (!result.accepted) {
            state.update { it.copy(lastPressureCommand = result.message) }
            return false
        }

        val point = result.point ?: return false
        val tapped = service.dispatchTap(point.x, point.y)
        if (!tapped) {
            fail("Failed to dispatch pressure tap", attemptStop = false)
            return false
        }

        val pressure = result.pressureBar ?: requestedPressureBar
        state.update {
            it.copy(
                commandedPressureBar = pressure,
                lastPressureCommand = "$source: ${pressure.fmt(2)} bar -> ${point.x.toInt()},${point.y.toInt()}"
            )
        }
        recordEvent(
            ShotEventType.PRESSURE_COMMAND,
            "$source commanded ${pressure.fmt(2)} bar",
            pressureBar = pressure
        )
        return true
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
        state.update {
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
        state.update {
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
        val weight = state.value.currentWeightG
        if (attemptStop && !stopSent) {
            EbarAccessibilityService.current()?.clickStopOrFallback(safetyConfig)
            stopSent = true
            recordEvent(ShotEventType.STOP_COMMAND, "Safety stop attempted: $message", weightG = weight)
        }

        EbarAccessibilityService.setShouldRun(false)
        state.update {
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

        val currentStage = state.value.selectedProfile.stages.getOrNull(state.value.currentStageIndex)
        val requiresTwo = currentStage?.safety?.requireTwoConsecutiveFirstDropReadings == true
        val detected = if (requiresTwo) firstDropConsecutiveReadings >= 2 else firstDropConsecutiveReadings >= 1

        if (detected) {
            firstDropDetected = true
            recordEvent(ShotEventType.FIRST_DROP, "First drop detected at ${weight.fmt(1)} g", weightG = weight)
        }
    }

    private fun appendSample(nowMs: Long, weight: Double, flow: Double, altFlowGps: Double? = null) {
        val current = state.value
        val shotStart = shotStartMs ?: nowMs
        val sample = ShotSample(
            timeMs = nowMs - shotStart,
            weightG = weight,
            flowGps = flow,
            commandedPressureBar = current.commandedPressureBar,
            stageName = current.currentStageName,
            altFlowGps = altFlowGps
        )
        state.update { it.copy(samples = (it.samples + sample).takeLast(MAX_LOG_ITEMS)) }
    }

    private fun recordState(controllerState: ControllerState, message: String) {
        recordEvent(ShotEventType.STATE_TRANSITION, "${controllerState.name}: $message")
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
        state.update { it.copy(events = (it.events + event).takeLast(MAX_LOG_ITEMS)) }
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
        flowCapWarningLoggedForCurrentStage = false
        lastFlowCorrectionMs = null
        lastCorrectionFlowGps = null
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

    fun validateLut(
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

    private fun lerp(start: Double, end: Double, progress: Double): Double {
        return start + (end - start) * progress
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun Double.fmt(decimals: Int): String = formatDecimals(decimals)

    companion object {
        private const val MAX_LOG_ITEMS = 2_000
    }
}
