package dev.akiskev.decentebar.ui

import android.util.Log
import dev.akiskev.decentebar.accessibility.EbarAccessibilityService
import dev.akiskev.decentebar.ble.ScaleConnectionState
import dev.akiskev.decentebar.ble.ScaleReading
import dev.akiskev.decentebar.engine.CurveMath
import dev.akiskev.decentebar.engine.FlowEstimator
import dev.akiskev.decentebar.engine.FlowFeedForwardController
import dev.akiskev.decentebar.engine.PressureLutManager
import dev.akiskev.decentebar.engine.YieldTimeTrajectoryPlanner
import dev.akiskev.decentebar.model.BuiltInPressureLut
import dev.akiskev.decentebar.model.ControllerState
import dev.akiskev.decentebar.model.CurvePoint
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.ExitMode
import dev.akiskev.decentebar.model.PressureCurveAxis
import dev.akiskev.decentebar.model.FeedForwardConfig
import dev.akiskev.decentebar.model.LutValidationResult
import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.ProfileStage
import dev.akiskev.decentebar.model.ProfileValidator
import dev.akiskev.decentebar.model.SafetyConfig
import dev.akiskev.decentebar.model.ScreenSpec
import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotEventType
import dev.akiskev.decentebar.model.ShotSample
import dev.akiskev.decentebar.model.StageType
import dev.akiskev.decentebar.model.YieldTimeTrajectoryConfig
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
    // Weight at the moment the current stage began, so a YIELD_TIME_TRAJECTORY stage measures
    // yield relative to its own start (stays correct after a preinfusion stage).
    private var stageEntryWeightG: Double? = null
    // Last time the yield/time stage issued an accepted pressure command — basis for the
    // per-second pressure rise/fall rate envelope.
    private var lastYieldCommandMs: Long? = null
    // Gush detection state for the yield/time fall-clamp bypass: the flow at the previous control
    // tick (for the "rising" test) and how many consecutive ticks have met the gush criteria.
    private var prevYieldFlowGps: Double? = null
    private var gushConfirmTicks = 0
    private var gushBypassLoggedForCurrentStage = false
    // Yield/time first-drop anchor: the recipe clock and yield count start at first drop (flow is
    // unobservable while the scale reads ~0 g), so these latch the time/weight at the moment the
    // stage leaves pre-infusion. Null while still pre-infusing.
    private var yieldExtractionStartMs: Long? = null
    private var yieldExtractionStartWeightG: Double? = null
    private var yieldPreInfusionLogged = false
    private var lastValidWeightMs: Long? = null
    // When ARMED, the first time we saw Stop visible while the pressure bar was not yet in
    // the tree (post-Start "Loading…" overlay). Used to time out the wait for controls.
    private var armedStopSeenMs: Long? = null
    private var stopSent = false
    private var manualSkipRequested = false
    private var firstDropConsecutiveReadings = 0
    private var firstDropDetected = false
    private var flowCapWarningLoggedForCurrentStage = false
    private var controlLawLoggedForCurrentStage = false
    private var lastFlowCorrectionMs: Long? = null
    private var lastCorrectionFlowGps: Double? = null

    // Resistance feed-forward controller for FLOW_LIMITED_PRESSURE stages that opt in via
    // ProfileStage.feedForward (docs/puck-resistance-feedforward.md §5). The legacy
    // incremental-P law stays the default; this runs only when a stage supplies the config.
    private val flowFeedForward = FlowFeedForwardController()

    // Output-based trajectory planner for YIELD_TIME_TRAJECTORY stages
    // (docs/yield-time-trajectory.md). Configured on stage entry; it only emits a target flow,
    // which is then handed to the flow→pressure controller below.
    private val yieldPlanner = YieldTimeTrajectoryPlanner()

    // Stall detection for the pressure slider: the target Y of the last issued slide and
    // the live reading just before it, used to notice when re-sliding to an unreachable
    // target (e.g. 0 bar floors near ~0.4) stops moving the bar so we hold instead of
    // jittering.
    private var lastSlideTargetBar: Double? = null
    private var lastSlideActualBar: Double? = null

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
        // A measured calibration takes precedence over the auto-anchored formula.
        val nextLut = if (current.lutCalibrated) current.loadedLut else lutForScreen(snapshot, current.loadedLut)
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
            armedStopSeenMs = null
            state.update { it.copy(safetyStatus = "Armed; waiting for E-Bar foreground") }
            return
        }

        if (snapshot.hasStop) {
            // Stop is visible, so the shot has started. But after an e-bar/OS update the
            // press of Start can pop a transient "Loading…" overlay for a second or two,
            // during which the pressure bar isn't in the accessibility tree yet. Running
            // the first stage then commands pressure into a slider that doesn't exist —
            // the swipe is a no-op and preinfusion stays at 0 bar (what a manual dummy
            // wait stage used to work around). Hold in ARMED until the bar is actually
            // present, with a timeout so an unrecognised layout still starts rather than
            // hanging here forever.
            val firstSeen = armedStopSeenMs ?: nowMs.also { armedStopSeenMs = it }
            val waitedTooLong = nowMs - firstSeen >= PRESSURE_CONTROLS_WAIT_TIMEOUT_MS
            if (pressureControlsReady(snapshot) || waitedTooLong) {
                beginRunning(nowMs, snapshot)
            } else {
                state.update { it.copy(safetyStatus = "Shot starting; waiting for pressure controls…") }
            }
            return
        }

        armedStopSeenMs = null
        if (!snapshot.hasStart) {
            fail("Neither Start nor Stop is visible while armed", attemptStop = false)
        } else {
            state.update { it.copy(safetyStatus = "Armed; waiting for shot start") }
        }
    }

    /**
     * True once the live pressure bar is present in the accessibility tree, i.e. a pressure
     * command can actually take effect. Gates the RUNNING transition so the first stage
     * isn't run blind while a post-Start "Loading…" overlay is still covering the slider.
     */
    private fun pressureControlsReady(snapshot: EbarSnapshot): Boolean {
        return BuiltInPressureLut.findPressureBar(
            snapshot.nodes,
            snapshot.screenWidth,
            snapshot.screenHeight
        ) != null
    }

    private fun beginRunning(nowMs: Long, snapshot: EbarSnapshot) {
        armedStopSeenMs = null
        shotStartMs = nowMs
        stageStartMs = nowMs
        stageEntryWeightG = snapshot.weightG ?: 0.0
        lastYieldCommandMs = null
        prevYieldFlowGps = null
        gushConfirmTicks = 0
        gushBypassLoggedForCurrentStage = false
        yieldExtractionStartMs = null
        yieldExtractionStartWeightG = null
        yieldPreInfusionLogged = false
        lastValidWeightMs = snapshot.weightG?.let { nowMs }
        flowEstimator.reset()
        flowFeedForward.reset()
        yieldPlanner.reset()
        configurePlannerForStage(state.value.selectedProfile.stages.firstOrNull())
        lutManager.resetThrottle()
        lastSlideTargetBar = null
        lastSlideActualBar = null
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
                currentWeightG = snapshot.weightG,
                // The machine resets the slider to 0 bar at shot start, so the first
                // pressure swipe of each shot must drag from there (not a stale value
                // left over from a previous shot).
                commandedPressureBar = null
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
            StageType.YIELD_TIME_TRAJECTORY -> runYieldTimeTrajectoryStage(nowMs, weight, flow, stage)
            StageType.PRESSURE_CURVE -> runPressureCurveStage(nowMs, weight, stage)
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

        // Record which control law drove this stage, once per stage entry, so logs are
        // self-identifying (the analysis tool reads this instead of fingerprinting cadence).
        if (!controlLawLoggedForCurrentStage) {
            controlLawLoggedForCurrentStage = true
            val law = if (stage.feedForward != null) "feed-forward control" else "incremental-P control"
            recordEvent(ShotEventType.INFO, "$stageName: $law")
        }

        // Opt-in resistance feed-forward law (docs/puck-resistance-feedforward.md §5). When a
        // stage supplies a FeedForwardConfig it replaces the legacy incremental-P law below.
        stage.feedForward?.let { ffConfig ->
            runFeedForwardStage(nowMs, flow, target, cap, currentPressure, ffConfig, stageName)
            return
        }

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
            val nextPressure = incrementalPNextPressure(
                flow, target, currentPressure, step, deadband, maxMult,
                minP = safetyConfig.minPressureBar, cap = cap
            )

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

    /**
     * Resistance feed-forward law for stages that opt in via [ProfileStage.feedForward]
     * (docs/puck-resistance-feedforward.md §5). The control maths lives in the pure
     * [FlowFeedForwardController]; this only feeds it live readings, commands the result, and
     * records the one-shot cap / channel diagnostics.
     */
    private fun runFeedForwardStage(
        nowMs: Long,
        flow: Double,
        target: Double,
        cap: Double,
        currentPressure: Double,
        config: FeedForwardConfig,
        stageName: String
    ) {
        val current = state.value
        val nextPressure = flowFeedForward.tick(nowMs, flow, target, currentPressure, cap, config)
            ?: return  // control interval not elapsed — hold the current command

        if (nextPressure != currentPressure || current.commandedPressureBar == null) {
            commandPressure(nextPressure, nowMs, source = stageName)
        }

        if (!flowCapWarningLoggedForCurrentStage) {
            val capLimited = nextPressure >= cap && flow < target - config.overspeedBandGps
            val channeling = flowFeedForward.lastMode == FlowFeedForwardController.Mode.OVERSPEED_TIMEOUT
            when {
                capLimited -> {
                    flowCapWarningLoggedForCurrentStage = true
                    recordEvent(
                        ShotEventType.INFO,
                        "$stageName: flow ${flow.fmt(2)} g/s below target ${target.fmt(2)} g/s — pressure capped at ${cap.fmt(2)} bar"
                    )
                }
                channeling -> {
                    flowCapWarningLoggedForCurrentStage = true
                    recordEvent(
                        ShotEventType.INFO,
                        "$stageName: flow ${flow.fmt(2)} g/s won't settle at low pressure — puck likely channeling"
                    )
                }
            }
        }
    }

    /**
     * Incremental-P flow-tracking step (shared by [runFlowLimitedStage] and the yield/time
     * stage): nudge pressure up/down by a deadband-scaled step toward [target] flow, with a
     * derivative guard that damps the step when flow is already moving the right way. Pure given
     * the live readings and [lastCorrectionFlowGps]; the caller owns the interval gate, the
     * command, and the timer bookkeeping.
     */
    private fun incrementalPNextPressure(
        flow: Double,
        target: Double,
        currentPressure: Double,
        step: Double,
        deadband: Double,
        maxMult: Double,
        minP: Double,
        cap: Double
    ): Double {
        val error = flow - target
        val absError = abs(error)
        // Derivative guard: if the previous correction is already moving flow toward the target,
        // reduce the multiplier to 30% to avoid stacking corrections faster than the system can
        // respond (dead-time over-correction).
        val lastFlow = lastCorrectionFlowGps
        val movingTowardTarget = when {
            error > deadband -> lastFlow != null && flow < lastFlow
            error < -deadband -> lastFlow != null && flow > lastFlow
            else -> false
        }
        val rawMultiplier = if (absError > deadband) (absError / deadband).coerceAtMost(maxMult) else 0.0
        val multiplier = if (movingTowardTarget) rawMultiplier * 0.3 else rawMultiplier
        val scaledStep = step * multiplier
        return when {
            flow > target + deadband -> currentPressure - scaledStep
            flow < target - deadband -> currentPressure + scaledStep
            else -> currentPressure
        }.coerceIn(minP, cap)
    }

    /** Configure the trajectory planner when entering a YIELD_TIME_TRAJECTORY stage. */
    private fun configurePlannerForStage(stage: ProfileStage?) {
        if (stage?.type == StageType.YIELD_TIME_TRAJECTORY) {
            stage.yieldTime?.let { yieldPlanner.configure(it) }
        }
    }

    /**
     * Hand-drawn pressure curve commanded directly (no feedback): interpolate the drawn pressure at
     * the current X fraction — elapsed stage time, or absolute cup weight — and command it within
     * the stage's pressure limits. Mirrors [StageType.TIME_BASED_PRESSURE_RAMP]; the LUT throttle and
     * the slider's physical response smooth steep steps.
     */
    private fun runPressureCurveStage(nowMs: Long, weight: Double, stage: ProfileStage) {
        val cfg = stage.pressureCurve ?: return
        val cap = min(cfg.maxPressureBar, safetyConfig.maxPressureBar)
        val minP = max(cfg.minPressureBar, safetyConfig.minPressureBar)

        if (!controlLawLoggedForCurrentStage) {
            controlLawLoggedForCurrentStage = true
            recordEvent(
                ShotEventType.INFO,
                "${stage.name}: pressure curve vs ${cfg.axis.name.lowercase()} (${cfg.points.size} points)"
            )
        }

        val x = when (cfg.axis) {
            PressureCurveAxis.TIME ->
                ((nowMs - (stageStartMs ?: nowMs)) / 1000.0) / cfg.durationS.coerceAtLeast(0.5)
            PressureCurveAxis.WEIGHT ->
                weight / cfg.maxWeightG.coerceAtLeast(0.5)   // absolute cup weight
        }.coerceIn(0.0, 1.0)

        val knots = cfg.points.map { CurvePoint(it.xPct, it.pressureBar) }
        val target = CurveMath.flowAtPct(knots, x).coerceIn(minP, cap)
        commandPressure(target, nowMs, source = stage.name)
    }

    /**
     * Output-driven extraction stage (docs/yield-time-trajectory.md). The pure [yieldPlanner]
     * turns the desired yield/time + flow shape into a corrected target flow; that target is
     * handed to the selected flow→pressure controller (resistance feed-forward when the config
     * supplies one, else the shared incremental-P law), and the resulting pressure is clamped to
     * the stage's per-second rise/fall envelope before being commanded. The planner never
     * bypasses the pressure-safety layer — it only produces a target flow.
     */
    private fun runYieldTimeTrajectoryStage(nowMs: Long, weight: Double, flow: Double, stage: ProfileStage) {
        val cfg = stage.yieldTime ?: return
        val current = state.value
        val stageName = stage.name
        val cap = min(cfg.maxPressureBar, safetyConfig.maxPressureBar)
        val minP = max(cfg.minPressureBar, safetyConfig.minPressureBar)

        // Phase 1 — pre-infusion (before first drop). Flow is unobservable while the scale reads
        // ~0 g, so hold a gentle pressure to saturate the puck; the recipe clock and flow control
        // do not run yet. If first drop already happened (e.g. an upstream pre-infusion stage),
        // this is skipped and extraction begins immediately.
        if (yieldExtractionStartMs == null) {
            val stageElapsedMs = nowMs - (stageStartMs ?: nowMs)
            val timedOut = cfg.preInfusionMaxS > 0.0 && stageElapsedMs >= (cfg.preInfusionMaxS * 1000).toLong()
            if (!firstDropDetected && !timedOut) {
                if (!yieldPreInfusionLogged) {
                    yieldPreInfusionLogged = true
                    recordEvent(
                        ShotEventType.INFO,
                        "$stageName: pre-infusion at ${cfg.preInfusionPressureBar.fmt(1)} bar until first drop"
                    )
                }
                commandPressure(cfg.preInfusionPressureBar.coerceIn(minP, cap), nowMs, source = stageName)
                return
            }
            // Latch the trajectory anchor at first drop (or pre-infusion timeout). The recipe's
            // 30 g / 30 s is measured from here, and the controller hands off from the pre-infusion
            // pressure. Reset the rate/gush clocks so extraction starts with a clean envelope.
            yieldExtractionStartMs = nowMs
            yieldExtractionStartWeightG = weight
            lastYieldCommandMs = null
            prevYieldFlowGps = null
            gushConfirmTicks = 0
            val law = if (cfg.feedForward != null) "feed-forward control" else "incremental-P control"
            val trigger = if (firstDropDetected) "first drop at ${weight.fmt(1)}g" else "pre-infusion timeout"
            recordEvent(
                ShotEventType.INFO,
                "$stageName: extraction begins ($trigger) — ${cfg.targetYieldG.fmt(1)}g / ${cfg.targetDurationS.fmt(1)}s, " +
                    "${cfg.curveType.name.lowercase().replace('_', ' ')} curve ($law)"
            )
        }

        // Phase 2 — extraction. The trajectory clocks from first drop and yield is counted from
        // the first-drop weight, so the pre-infusion dead time isn't charged against the recipe.
        val currentPressure = current.commandedPressureBar ?: min(cap, stageEntryPressureBar ?: cap)
        val extractionElapsedMs = nowMs - (yieldExtractionStartMs ?: nowMs)
        val yieldGained = (weight - (yieldExtractionStartWeightG ?: weight)).coerceAtLeast(0.0)
        val tick = yieldPlanner.evaluate(extractionElapsedMs, yieldGained)
        val targetFlow = tick.correctedTargetFlowGps

        // Ask the selected lower-level controller for a proposed pressure to hit targetFlow.
        // Both paths self-gate on their own interval and return null to mean "hold".
        val proposed: Double = if (cfg.feedForward != null) {
            flowFeedForward.tick(nowMs, flow, targetFlow, currentPressure, cap, cfg.feedForward) ?: return
        } else {
            val scaleOn = current.scaleConnectionState == ScaleConnectionState.CONNECTED
            val intervalMs = if (scaleOn) 250L else 600L
            val lastCorrection = lastFlowCorrectionMs
            if (lastCorrection != null && nowMs - lastCorrection < intervalMs) return  // not due — hold
            val stepBar = 0.6 * (intervalMs.toDouble() / 600.0)
            incrementalPNextPressure(flow, targetFlow, currentPressure, stepBar, INCREMENTAL_DEADBAND_GPS, 8.0, minP, cap)
        }

        // Authoritative per-second pressure rate envelope (applied uniformly regardless of the
        // controller chosen). The fall clamp is bypassed only during a *confirmed* gush so pressure
        // can be dumped fast — preserving gusher safety without letting a single high-flow tick
        // trigger an aggressive (and self-oscillating) pressure drop.
        val gushing = detectGush(flow, targetFlow, tick.weightErrorG, stageName)
        // Extraction floor: hold a minimum pressure through extraction so the tail keeps real
        // extraction force (anti-sour), but release it during a confirmed gush so a channeling puck
        // can still be arrested down to the hard minPressureBar.
        val floor = if (gushing) minP else max(minP, cfg.minExtractionPressureBar).coerceAtMost(cap)
        val finalPressure = applyPressureRateEnvelope(proposed, currentPressure, nowMs, bypassFallClamp = gushing, cfg)
            .coerceIn(floor, cap)

        if (finalPressure != currentPressure || current.commandedPressureBar == null) {
            val accepted = commandPressure(finalPressure, nowMs, source = stageName)
            if (accepted) {
                lastYieldCommandMs = nowMs
                lastFlowCorrectionMs = nowMs
                lastCorrectionFlowGps = flow
            }
        } else {
            // Holding at target — advance the incremental timer so we don't re-check every reading.
            lastFlowCorrectionMs = nowMs
            lastCorrectionFlowGps = flow
        }

        // One-shot trajectory diagnostics (mirrors the flow-limited cap warning).
        if (!flowCapWarningLoggedForCurrentStage) {
            when {
                finalPressure >= cap && flow < targetFlow - INCREMENTAL_DEADBAND_GPS -> {
                    flowCapWarningLoggedForCurrentStage = true
                    recordEvent(
                        ShotEventType.INFO,
                        "$stageName: flow ${flow.fmt(2)} g/s below target ${targetFlow.fmt(2)} g/s — pressure capped at ${cap.fmt(2)} bar"
                    )
                }
                tick.mode == "LATE_LIMIT" -> {
                    flowCapWarningLoggedForCurrentStage = true
                    recordEvent(ShotEventType.INFO, "$stageName: late-shot correction limiting engaged")
                }
                tick.mode == "TASTE_CAP" -> {
                    flowCapWarningLoggedForCurrentStage = true
                    recordEvent(ShotEventType.INFO, "$stageName: taste-safe cap limiting late flow increase")
                }
            }
        }
    }

    /**
     * Clamp [proposed] pressure to the per-second rise/fall rate envelope relative to
     * [prevPressure], using the wall-clock gap since the last yield/time command as dt. When
     * [bypassFallClamp] (a confirmed gush) the fall clamp is skipped so pressure can be dumped
     * quickly. The first command of a stage establishes the baseline (no clamp).
     */
    private fun applyPressureRateEnvelope(
        proposed: Double,
        prevPressure: Double,
        nowMs: Long,
        bypassFallClamp: Boolean,
        cfg: YieldTimeTrajectoryConfig
    ): Double {
        val lastMs = lastYieldCommandMs ?: return proposed
        val dtS = (nowMs - lastMs) / 1000.0
        if (dtS <= 0.0) return proposed
        val maxRise = cfg.maxPressureRiseBarPerS * dtS
        val maxFall = cfg.maxPressureFallBarPerS * dtS
        var p = proposed
        if (p > prevPressure + maxRise) p = prevPressure + maxRise
        if (!bypassFallClamp && p < prevPressure - maxFall) p = prevPressure - maxFall
        return p
    }

    /**
     * Conservative gush detector for the fall-clamp bypass (per-tick, called on control ticks).
     * A single high-flow reading must NOT trigger an aggressive pressure drop, so a gush requires
     * all of: flow well over the corrected target ([GUSH_MARGIN_GPS]), flow *rising* vs the last
     * tick, weight already at/ahead of the planned trajectory (so dumping pressure won't sacrifice
     * the yield target), and the combination holding for [GUSH_CONFIRM_TICKS] consecutive ticks
     * (one confirmation). Without the bypass pressure still falls — just at the normal rate limit.
     */
    private fun detectGush(flow: Double, targetFlow: Double, weightErrorG: Double, stageName: String): Boolean {
        val rising = prevYieldFlowGps?.let { flow > it } ?: false
        prevYieldFlowGps = flow
        val overTarget = flow > targetFlow + GUSH_MARGIN_GPS
        val aheadOfPlan = weightErrorG <= 0.0   // planned − actual <= 0 ⇒ actual at/ahead of plan
        val candidate = overTarget && rising && aheadOfPlan
        gushConfirmTicks = if (candidate) gushConfirmTicks + 1 else 0
        val gushing = gushConfirmTicks >= GUSH_CONFIRM_TICKS
        if (gushing && !gushBypassLoggedForCurrentStage) {
            gushBypassLoggedForCurrentStage = true
            recordEvent(
                ShotEventType.INFO,
                "$stageName: confirmed gush (flow ${flow.fmt(2)} g/s over target ${targetFlow.fmt(2)}) — easing pressure fast"
            )
        }
        return gushing
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

        // Yield/time trajectory completes after targetDurationS of *extraction* (measured from
        // first drop), not stage time — pre-infusion before first drop doesn't count against the
        // recipe clock, so stageTimeGteMs would end the shot early.
        if (stage.type == StageType.YIELD_TIME_TRAJECTORY) {
            val extStart = yieldExtractionStartMs
            val durationMs = stage.yieldTime?.let { (it.targetDurationS * 1000).toLong() }
            if (extStart != null && durationMs != null && nowMs - extStart >= durationMs) {
                return "trajectory ${stage.yieldTime!!.targetDurationS.fmt(0)}s elapsed"
            }
        }

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
        controlLawLoggedForCurrentStage = false
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
        // Stage-relative yield baseline + a fresh rate-envelope clock and gush state for a
        // yield/time stage.
        stageEntryWeightG = weight
        lastYieldCommandMs = null
        prevYieldFlowGps = null
        gushConfirmTicks = 0
        gushBypassLoggedForCurrentStage = false
        yieldExtractionStartMs = null
        yieldExtractionStartWeightG = null
        yieldPreInfusionLogged = false

        if (nextIndex >= profile.stages.size) {
            sendStop("Profile complete", nowMs, weight)
            return
        }

        configurePlannerForStage(profile.stages[nextIndex])

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

        // The e-bar slider is absolute (the release Y sets the value) and the LUT is
        // measured, so we slide to the target's Y. Run closed-loop against the live
        // reading: the e-bar drops the first slide or two right after Start, so re-slide
        // until the bar confirms it landed, then hold (the closed-loop deadband is wider
        // than the landing jitter, so it settles instead of oscillating).
        val actual = readBarPressure(current.snapshot)

        val result = lutManager.requestPressure(
            lut = current.loadedLut,
            screen = screenSpec(current.snapshot),
            requestedPressureBar = requestedPressureBar,
            nowMs = nowMs,
            force = force,
            currentActualBar = actual
        )

        if (!result.accepted) {
            // Routine throttle/deadband drops happen many times per second on a fixed
            // stage; only log the rejections that signal an actual problem.
            if (!result.message.startsWith("Suppressed")) {
                Log.i(
                    LOG_TAG,
                    "pressure[$source] req=${requestedPressureBar.fmt(2)} REJECTED: ${result.message} " +
                        "(lut=${current.loadedLut?.name ?: "null"}, pkg=${current.snapshot.activePackage}, " +
                        "screen=${current.snapshot.screenWidth}x${current.snapshot.screenHeight})"
                )
            }
            state.update { it.copy(lastPressureCommand = result.message) }
            return false
        }

        val point = result.point ?: return false
        val target = result.pressureBar ?: requestedPressureBar
        val lut = current.loadedLut ?: return false

        // Stall detection: if the previous slide to this same target left the reading
        // unchanged AND we're already near the target, the bar is as close as it can get
        // (e.g. 0 bar floors ~0.4) — hold rather than re-slide and jitter. Only applies
        // when already near target, so the far-from-target start-up retries still run.
        val sameTarget = lastSlideTargetBar?.let { abs(target - it) < 0.05 } == true
        val unchanged = actual != null && lastSlideActualBar?.let { abs(actual - it) < STALL_EPSILON_BAR } == true
        val nearTarget = actual != null && abs(target - actual) < STALL_HOLD_BAND_BAR
        if (sameTarget && unchanged && nearTarget) {
            state.update {
                it.copy(lastPressureCommand = "$source: holding ${actual?.fmt(2)} bar (target ${target.fmt(2)}, bar floored)")
            }
            return true
        }

        // Absolute slide: the release Y sets the value. Start a short hop toward the
        // bar's active centre so touch-down is never on the dead zone at the extreme ends.
        val startY = pressureSwipeStartY(lut, point.y)
        val swiped = service.dispatchSwipe(point.x, startY, point.x, point.y)
        Log.i(
            LOG_TAG,
            "pressure[$source] target=${target.fmt(2)} barReads=${actual?.fmt(2) ?: "??"} " +
                "slide x=${point.x.toInt()} y ${startY.toInt()}->${point.y.toInt()} dispatched=$swiped"
        )
        if (!swiped) {
            fail("Failed to dispatch pressure swipe", attemptStop = false)
            return false
        }
        lastSlideTargetBar = target
        lastSlideActualBar = actual

        state.update {
            it.copy(
                commandedPressureBar = target,
                lastPressureCommand = "$source: ${actual?.fmt(2) ?: "?"}->${target.fmt(2)} bar"
            )
        }
        recordEvent(
            ShotEventType.PRESSURE_COMMAND,
            "$source -> ${target.fmt(2)} bar",
            pressureBar = target
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

        // Yield/time telemetry: re-evaluate the planner (pure) for this reading so the sample
        // carries target weight/flow, the corrected target, and the trajectory error. Only on a
        // YIELD_TIME_TRAJECTORY stage; null on every other stage, so other shots are unchanged.
        val stage = current.selectedProfile.stages.getOrNull(current.currentStageIndex)
        var targetWeight: Double? = null
        var targetFlow: Double? = null
        var correctedFlow: Double? = null
        var weightError: Double? = null
        var flowError: Double? = null
        var progressPct: Double? = null
        var plannerMode: String? = null
        if (stage?.type == StageType.YIELD_TIME_TRAJECTORY && stage.yieldTime != null) {
            val extStart = yieldExtractionStartMs
            if (extStart == null) {
                // Pre-infusion: no trajectory yet (flow is unobservable), so leave the target
                // fields null — the report's target curves begin at first drop.
                plannerMode = "PREINFUSION"
            } else {
                val anchorWeight = yieldExtractionStartWeightG ?: weight
                val extractionElapsedMs = nowMs - extStart
                val yieldGained = (weight - anchorWeight).coerceAtLeast(0.0)
                val tick = yieldPlanner.evaluate(extractionElapsedMs, yieldGained)
                targetWeight = anchorWeight + tick.plannedStageWeightG
                targetFlow = tick.plannedFlowGps
                correctedFlow = tick.correctedTargetFlowGps
                weightError = tick.weightErrorG
                flowError = flow - tick.correctedTargetFlowGps
                progressPct = tick.progressPct
                plannerMode = tick.mode
            }
        }

        val sample = ShotSample(
            timeMs = nowMs - shotStart,
            weightG = weight,
            flowGps = flow,
            commandedPressureBar = current.commandedPressureBar,
            stageName = current.currentStageName,
            altFlowGps = altFlowGps,
            targetWeightG = targetWeight,
            targetFlowGps = targetFlow,
            correctedTargetFlowGps = correctedFlow,
            weightErrorG = weightError,
            flowErrorGps = flowError,
            trajectoryProgressPct = progressPct,
            plannerMode = plannerMode
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
        stageEntryWeightG = null
        lastYieldCommandMs = null
        prevYieldFlowGps = null
        gushConfirmTicks = 0
        gushBypassLoggedForCurrentStage = false
        yieldExtractionStartMs = null
        yieldExtractionStartWeightG = null
        yieldPreInfusionLogged = false
        lastValidWeightMs = null
        armedStopSeenMs = null
        stopSent = false
        manualSkipRequested = false
        firstDropConsecutiveReadings = 0
        firstDropDetected = false
        flowCapWarningLoggedForCurrentStage = false
        controlLawLoggedForCurrentStage = false
        lastFlowCorrectionMs = null
        lastCorrectionFlowGps = null
        flowEstimator.reset()
        flowFeedForward.reset()
        yieldPlanner.reset()
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

    /**
     * Reads the pressure value the e-bar is currently showing, parsed from the pressure
     * bar node's content-desc (e.g. "7.0\nbar\nPr."). Lets the log compare what we
     * commanded against what the machine actually displays.
     */
    private fun readBarPressure(snapshot: EbarSnapshot): Double? {
        val bar = BuiltInPressureLut.findPressureBar(snapshot.nodes, snapshot.screenWidth, snapshot.screenHeight)
        val desc = bar?.contentDescription ?: return null
        return desc.split('\n', ' ', '\t').firstNotNullOfOrNull { it.trim().toDoubleOrNull() }
    }

    /**
     * Picks the Y to begin an absolute pressure slide from, given the release [targetY].
     * The slide only needs enough travel to register (not a tap) and its start must not
     * land on the dead zone at the extreme ends — so we start [SWIPE_TRAVEL_PX] toward
     * the bar's active centre and release at the target. Only the release position sets
     * the value, so the start being merely near the target is fine.
     */
    private fun pressureSwipeStartY(lut: PressureLut?, targetY: Float): Float {
        val ys = lut?.points?.map { it.y }
        val minY = ys?.min() ?: (targetY - MIN_SWIPE_TRAVEL_PX)
        val maxY = ys?.max() ?: (targetY + MIN_SWIPE_TRAVEL_PX)
        val midY = (minY + maxY) / 2f
        // Travel scales with the bar's on-screen span so it works across resolutions.
        val travel = ((maxY - minY) * SWIPE_TRAVEL_FRACTION).coerceAtLeast(MIN_SWIPE_TRAVEL_PX)
        val toward = if (targetY <= midY) travel else -travel
        return (targetY + toward).coerceIn(minY, maxY)
    }

    private fun lutForScreen(snapshot: EbarSnapshot, current: PressureLut?): PressureLut? {
        val width = snapshot.screenWidth
        val height = snapshot.screenHeight
        if (width <= 0 || height <= 0) return current
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)

        // Prefer a LUT anchored to the live pressure bar so the tap coordinates track
        // wherever the current e-bar version places it. Reuse the existing object when
        // the derived points are unchanged so we don't churn state every snapshot.
        val anchored = BuiltInPressureLut.buildAnchored(snapshot.nodes, width, height)
        if (anchored != null) {
            return if (current == anchored) current else anchored
        }

        // No bar visible (app backgrounded or layout not recognised): keep a matching
        // LUT if we have one, otherwise fall back to the static built-in calibration.
        if (current != null && current.screenWidth == longSide && current.screenHeight == shortSide) return current
        return BuiltInPressureLut.buildFor(width, height) ?: current
    }

    private fun lerp(start: Double, end: Double, progress: Double): Double {
        return start + (end - start) * progress
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun Double.fmt(decimals: Int): String = formatDecimals(decimals)

    companion object {
        const val LOG_TAG = "DecentEbar"
        private const val MAX_LOG_ITEMS = 2_000

        // How far toward the bar's centre an absolute pressure slide starts before
        // releasing at the target: enough travel to register as a drag (not a tap, which
        // opens a popup) while keeping touch-down off the dead zone at the bar's ends.
        // A fraction of the bar's span (resolution-independent) with an absolute floor.
        private const val SWIPE_TRAVEL_FRACTION = 0.11f
        private const val MIN_SWIPE_TRAVEL_PX = 60f

        // After Start, hold the RUNNING transition until the pressure bar appears in the
        // tree (a post-Start "Loading…" overlay can hide it for a second or two). Begin
        // anyway after this long so an unrecognised layout — where the bar is never
        // matched — still starts instead of stranding the user armed.
        private const val PRESSURE_CONTROLS_WAIT_TIMEOUT_MS = 5_000L

        // Stall detection: a re-slide that changes the reading by less than this is "no
        // movement"; only give up (hold) when the bar is already within this band of the
        // target, so far-from-target start-up retries are unaffected.
        private const val STALL_EPSILON_BAR = 0.25
        private const val STALL_HOLD_BAND_BAR = 1.0

        // Yield/time stage: deadband for the incremental-P flow tracker.
        private const val INCREMENTAL_DEADBAND_GPS = 0.1
        // Gush detection (fall-clamp bypass): flow must exceed the corrected target by this much,
        // be rising, with weight at/ahead of plan, for this many consecutive control ticks.
        private const val GUSH_MARGIN_GPS = 0.6
        private const val GUSH_CONFIRM_TICKS = 2
    }
}
