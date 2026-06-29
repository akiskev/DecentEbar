package dev.akiskev.decentebar.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

// Canonical package: the 3.1.0+ public release. The 3.0.x betas shipped under a
// different package id, so both are accepted everywhere we match the e-bar app.
const val EBAR_PACKAGE_NAME = "com.g472631889.stf"
const val EBAR_PACKAGE_NAME_BETA = "com.g472631889.stfbeta"
val EBAR_PACKAGE_NAMES: Set<String> = setOf(EBAR_PACKAGE_NAME, EBAR_PACKAGE_NAME_BETA)

fun isEbarPackage(packageName: String?): Boolean = packageName in EBAR_PACKAGE_NAMES

@Serializable
data class PressureLut(
    val schemaVersion: Int = 1,
    val name: String,
    val packageName: String = EBAR_PACKAGE_NAME,
    val screenWidth: Int,
    val screenHeight: Int,
    val orientation: String = "landscape",
    val points: List<PressurePoint>,
)

@Serializable
data class PressurePoint(
    val pressureBar: Double,
    val x: Float,
    val y: Float
)

@Serializable
data class ShotProfile(
    val schemaVersion: Int = 1,
    val name: String,
    val targetWeightG: Double,
    val stopOffsetG: Double,
    val maxShotTimeMs: Long = ProfileConstraints.DEFAULT_MAX_SHOT_TIME_MS,
    val stages: List<ProfileStage>
)

@Serializable
data class ProfileStage(
    val name: String,
    val type: StageType,
    val fixedPressureBar: Double? = null,
    val pressureCapBar: Double? = null,
    val targetFlowGps: Double? = null,
    val flowDeadbandGps: Double? = null,
    val pressureStepBar: Double? = null,
    val correctionIntervalMs: Long? = null,
    val pressureStepMultiplierMax: Double? = null,
    // When present on a FLOW_LIMITED_PRESSURE stage, selects the resistance feed-forward
    // controller instead of the legacy incremental-P law (docs/puck-resistance-feedforward.md §5).
    val feedForward: FeedForwardConfig? = null,
    val rampStartPressureBar: Double? = null,
    val rampEndPressureBar: Double? = null,
    val rampStartWeightG: Double? = null,
    val rampEndWeightG: Double? = null,
    val rampDurationMs: Long? = null,
    // When present on a YIELD_TIME_TRAJECTORY stage, drives the output-based planner that
    // computes a live target flow from a desired final yield/time + flow shape, then hands
    // that target to the existing flow→pressure controller (docs/yield-time-trajectory.md).
    val yieldTime: YieldTimeTrajectoryConfig? = null,
    // When present on a PRESSURE_CURVE stage, a hand-drawn multi-point pressure curve commanded
    // directly against elapsed time or absolute cup weight.
    val pressureCurve: PressureCurveConfig? = null,
    val exit: ExitCondition = ExitCondition(),
    val safety: StageSafety = StageSafety()
)

@Serializable
enum class StageType {
    FIXED_PRESSURE,
    FLOW_LIMITED_PRESSURE,
    WEIGHT_BASED_PRESSURE_RAMP,
    TIME_BASED_PRESSURE_RAMP,
    YIELD_TIME_TRAJECTORY,
    PRESSURE_CURVE,
    STOP
}

@Serializable
data class ExitCondition(
    val mode: ExitMode = ExitMode.ANY,
    val weightGte: Double? = null,
    val stageTimeGteMs: Long? = null,
    val flowGte: Double? = null,
    val flowLte: Double? = null,
    val firstDropDetected: Boolean = false,
    val manualSkip: Boolean = false,
    val safetyTimeout: Boolean = false
)

@Serializable
enum class ExitMode {
    ANY,
    ALL
}

@Serializable
data class StageSafety(
    val maxStageTimeMs: Long? = null,
    val requireTwoConsecutiveFirstDropReadings: Boolean = false
)

/**
 * Config for the resistance feed-forward Main controller
 * (docs/puck-resistance-feedforward.md §5). Its presence on a FLOW_LIMITED_PRESSURE stage
 * selects the feed-forward law over the legacy incremental-P law, so it can be A/B'd per
 * profile. Every field defaults to a sane value, so opting in is just
 * `feedForward = FeedForwardConfig()`.
 */
@Serializable
data class FeedForwardConfig(
    // Control tick spaced to the puck's response lag so corrections don't stack (§5.4).
    val controlIntervalMs: Long = 700L,
    // Cold-start puck resistance (bar per g/s) when no probe seed is supplied. Phase 1 is
    // online-only; the probe seed is Phase 2. Online adaptation corrects this within a few ticks.
    val coldStartResistanceBarPerGps: Double = 3.0,
    val seedResistanceBarPerGps: Double? = null,
    // Online resistance estimate is clamped to this sane range.
    val minResistanceBarPerGps: Double = 0.5,
    val maxResistanceBarPerGps: Double = 12.0,
    // Asymmetric adaptation (§5.3): trust a resistance DROP fast (channel risk), a RISE slowly.
    val adaptDownRate: Double = 0.4,
    val adaptUpRate: Double = 0.1,
    // Learn only from settled samples (§5.2): accept a P/Q sample when |dflow/dt| is at or
    // below this (g/s per second).
    val stableMaxFlowSlopeGpsPerS: Double = 0.6,
    // Small bounded feedback trim on the (optionally dead-time-predicted) flow error.
    val trimGainBarPerGps: Double = 0.8,
    val predictHorizonS: Double = 0.0,
    // Overspeed (§5.7): flow above target + band triggers a low-pressure hold. Sized for a real
    // gush, not a mild overshoot — small overshoots are handled by normal tracking with the floor
    // lifted, so we don't slam to 0 (and re-trigger a limit cycle) over a few tenths g/s.
    val overspeedBandGps: Double = 1.0,
    val recoveryPressureBar: Double = 0.0,
    val overspeedHoldTimeoutMs: Long = 4_000L,
    // Conditional floor (§5.8): a floor in normal control, lifted toward 0 when flow is over target.
    val pressureFloorBar: Double = 2.0,
    // Rises are capped per tick (drops are not) — the asymmetry that stops re-triggering breakthroughs.
    val maxRisePerTickBar: Double = 0.8
)

/** Flow-shape strategy for a [YieldTimeTrajectoryConfig]. */
@Serializable
enum class FlowCurveType {
    FLAT,
    DECLINING,
    RAMP_THEN_DECLINE,
    BLOOMING_DECLINE,
    CUSTOM_POINTS
}

/**
 * How hard the planner is allowed to chase the yield/time target near the end of the shot.
 * STRICT_TARGET still corrects aggressively late; TASTE_SAFE prefers a slightly short/late
 * shot over a violent final-seconds pressure rise (docs/yield-time-trajectory.md).
 */
@Serializable
enum class TastePriorityMode {
    STRICT_TARGET,
    BALANCED,
    TASTE_SAFE
}

/** One control point of a CUSTOM_POINTS flow curve: flow at a fraction of the stage duration. */
@Serializable
data class CurvePoint(
    val timePct: Double,
    val flowGps: Double
)

/**
 * Output-driven extraction config for a [StageType.YIELD_TIME_TRAJECTORY] stage
 * (docs/yield-time-trajectory.md). The user declares the desired final beverage weight,
 * shot time, and a flow shape; the planner computes a live target flow (planned curve blended
 * with a catch-up term, with late-shot taste protection) and the existing flow→pressure
 * controller follows it under all the usual pressure-safety limits.
 *
 * The shape params (start/peak/end flow) describe the *shape*; the planner normalizes the
 * curve so its integral equals [targetYieldG] regardless of those absolute values.
 *
 * [targetYieldG] and [targetDurationS] are measured from **first drop**, not stage entry:
 * flow is unobservable while the scale reads ~0 g, so the recipe clock and flow control only
 * start once the puck yields. Before that, a pressure-driven pre-infusion phase saturates the
 * puck ([preInfusionPressureBar]). During extraction, [minExtractionPressureBar] keeps a pressure
 * floor so the tail doesn't sag into under-extraction.
 *
 * [feedForward] mirrors the [ProfileStage.feedForward] convention: its presence selects the
 * resistance feed-forward controller over the legacy incremental-P law for the flow→pressure
 * step. Every field is defaulted so opting in from the editor or a minimal JSON is cheap.
 */
@Serializable
data class YieldTimeTrajectoryConfig(
    val targetYieldG: Double = 30.0,
    val targetDurationS: Double = 30.0,
    val curveType: FlowCurveType = FlowCurveType.RAMP_THEN_DECLINE,
    // Shape hints (normalized to hit targetYieldG). For RAMP_THEN_DECLINE the curve runs
    // start → peak (at peakAtPct) → end; DECLINING uses start → end; FLAT ignores them.
    val startFlowGps: Double = 0.65,
    val peakFlowGps: Double = 1.25,
    val endFlowGps: Double = 0.75,
    val peakAtPct: Double = 0.35,
    // Only for CUSTOM_POINTS: interpolated then normalized to targetYieldG.
    val customPoints: List<CurvePoint> = emptyList(),
    // Pressure / flow envelope handed to the lower-level controller and the rate guard.
    val maxPressureBar: Double = 8.0,
    val minPressureBar: Double = 0.0,
    // Extraction floor: a minimum pressure held through the *extraction* phase (after first drop)
    // so the back of the shot keeps real extraction force instead of coasting at ~0 bar and
    // under-extracting (the classic "sour, thin tail"). Unlike minPressureBar it does NOT raise
    // the pre-infusion pressure, and it is released during a confirmed gush so a channeling puck
    // can still be arrested. 0 = off (default; backward compatible).
    val minExtractionPressureBar: Double = 0.0,
    val maxFlowGps: Double = 1.6,
    val minFlowGps: Double = 0.0,
    val maxPressureRiseBarPerS: Double = 0.6,
    val maxPressureFallBarPerS: Double = 1.0,
    // 0 = ignore the trajectory error (follow the planned curve), 1 = fully chase the
    // catch-up flow. Blended each tick: corrected = planned + strength*(catchup − planned).
    val correctionStrength: Double = 0.35,
    // Inside this many seconds of the end, correction strength is ramped down toward 0.
    val lateShotCorrectionLimitS: Double = 5.0,
    val tastePriorityMode: TastePriorityMode = TastePriorityMode.TASTE_SAFE,
    // Pre-infusion (before first drop): flow is 0 until the puck yields, so the trajectory clock
    // and flow control only begin at first drop. Until then, hold this gentle pressure to
    // saturate the puck. preInfusionMaxS force-starts extraction if first drop never arrives, so
    // a non-yielding puck can't pre-infuse forever.
    val preInfusionPressureBar: Double = 3.0,
    val preInfusionMaxS: Double = 20.0,
    val feedForward: FeedForwardConfig? = null
)

/** What a [PressureCurveConfig] plots pressure against: elapsed stage time or absolute cup weight. */
@Serializable
enum class PressureCurveAxis {
    TIME,
    WEIGHT
}

/** One point of a hand-drawn pressure curve: pressure at a fraction of the X axis (time or weight). */
@Serializable
data class PressureCurvePoint(
    val xPct: Double,
    val pressureBar: Double
)

/**
 * A hand-drawn multi-point pressure curve commanded directly by a [StageType.PRESSURE_CURVE] stage
 * (no controller/feedback — pressure is the actuator). At each tick the X fraction is computed from
 * elapsed stage time (`durationS`) or absolute cup weight (`maxWeightG`) per [axis], the pressure is
 * interpolated from [points] (clamped to the endpoints past the range), and commanded within
 * `[minPressureBar, maxPressureBar]`. `maxPressureBar` is both the editor's Y scale and the cap.
 */
@Serializable
data class PressureCurveConfig(
    val axis: PressureCurveAxis = PressureCurveAxis.TIME,
    val points: List<PressureCurvePoint> = emptyList(),
    val durationS: Double = 30.0,
    val maxWeightG: Double = 40.0,
    val maxPressureBar: Double = 9.0,
    val minPressureBar: Double = 0.0
)

@Serializable
data class ShotSample(
    val timeMs: Long,
    val weightG: Double,
    val flowGps: Double,
    val commandedPressureBar: Double?,
    val stageName: String,
    val altFlowGps: Double? = null,  // software-estimated flow when scale is connected, for comparison
    // Yield/time trajectory telemetry — populated only on YIELD_TIME_TRAJECTORY stages, null
    // otherwise (docs/yield-time-trajectory.md). targetWeightG is absolute (stage-entry weight
    // + planned stage weight) so reports overlay it directly on actual cup weight.
    val targetWeightG: Double? = null,
    val targetFlowGps: Double? = null,
    val correctedTargetFlowGps: Double? = null,
    val weightErrorG: Double? = null,
    val flowErrorGps: Double? = null,
    val trajectoryProgressPct: Double? = null,
    val plannerMode: String? = null
)

@Serializable
data class ShotEvent(
    val timeMs: Long,
    val type: ShotEventType,
    val message: String,
    val weightG: Double? = null,
    val pressureBar: Double? = null
)

@Serializable
data class ShotLog(
    val profileName: String,
    val startedAtMs: Long?,
    val stoppedAtMs: Long?,
    val samples: List<ShotSample>,
    val events: List<ShotEvent>,
    val stageTargetFlows: Map<String, Double> = emptyMap(),
    // User-entered shot metadata (collected on save) so logs are richer for analysis.
    val beansName: String? = null,
    val grindSetting: String? = null,
    val doseG: Double? = null,
    val notes: String? = null,
    val shotId: String? = null,
    val savedAtMs: Long? = null,
    val roastLevel: String? = null,
    val basket: String? = null,
    val targetYieldG: Double? = null,
    val targetTimeS: Double? = null,
    val tasteNotes: String? = null,
    val rating: Int? = null,
    val bestForBean: Boolean = false,
    // Auto-captured context for reproducibility (docs/puck-resistance-feedforward.md Tier 1).
    val appVersion: String? = null,
    val flowSource: String? = null,        // "scale" (BLE) or "accessibility" (estimated)
    val scaleBatteryPercent: Int? = null,
    // Full profile snapshot — captures the exact stage params and any FeedForwardConfig used,
    // so a shot is fully reproducible (profileName above is kept for convenience).
    val profile: ShotProfile? = null
)

@Serializable
data class ShotLibraryEntry(
    val shotId: String,
    val savedAtMs: Long,
    val startedAtMs: Long?,
    val profileName: String,
    val beansName: String?,
    val normalizedBean: String,
    val doseG: Double?,
    val grindSetting: String?,
    val roastLevel: String?,
    val basket: String?,
    val rating: Int?,
    val bestForBean: Boolean = false,
    val finalYieldG: Double?,
    val durationMs: Long?,
    val sampleCount: Int,
    val eventCount: Int,
    val flowSource: String?,
    val targetYieldG: Double?,
    val targetTimeS: Double?,
    val exportedAtMs: Long? = null
)

data class ResolvedShotTargets(
    val targetYieldG: Double?,
    val targetTimeS: Double?
)

object ShotTargetResolver {
    fun resolve(
        profile: ShotProfile,
        userTargetYieldG: Double? = null,
        userTargetTimeS: Double? = null
    ): ResolvedShotTargets {
        val yieldTime = profile.stages.firstNotNullOfOrNull { it.yieldTime }
        return ResolvedShotTargets(
            targetYieldG = yieldTime?.targetYieldG
                ?: profile.targetWeightG.takeIf { it > 0.0 }
                ?: userTargetYieldG?.takeIf { it > 0.0 },
            targetTimeS = yieldTime?.targetDurationS
                ?: userTargetTimeS?.takeIf { it > 0.0 }
        )
    }
}

@Serializable
enum class ShotEventType {
    STATE_TRANSITION,
    ARM,
    DISARM,
    PRESSURE_COMMAND,
    PRESSURE_SUPPRESSED,
    FIRST_DROP,
    STAGE_EXIT,
    STOP_COMMAND,
    SAFETY_ERROR,
    INFO
}

enum class ControllerState {
    IDLE,
    ARMED,
    RUNNING,
    STAGE_TRANSITION,
    STOPPING,
    STOPPED,
    ERROR
}

data class EbarSnapshot(
    val timestampMs: Long = 0L,
    val isForeground: Boolean = false,
    val activePackage: String? = null,
    val hasStart: Boolean = false,
    val hasStop: Boolean = false,
    val hasWeigh: Boolean = false,
    val hasPressurePriority: Boolean = false,
    val hasFlowRatePriority: Boolean = false,
    val hasPressureLabel: Boolean = false,
    val weightG: Double? = null,
    val rawDescriptions: List<String> = emptyList(),
    val rawTexts: List<String> = emptyList(),
    val nodes: List<AccessibilityNodeBounds> = emptyList(),
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val orientation: String = "unknown"
) {
    val hasPressureControls: Boolean
        get() = hasPressurePriority || hasFlowRatePriority || hasPressureLabel
}

/**
 * A flattened, value-typed copy of one accessibility node's on-screen geometry.
 * Captured so the pressure bar can be located by layout (auto-anchoring) rather
 * than by hardcoded pixel coordinates that break when the e-bar app moves things.
 */
data class AccessibilityNodeBounds(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean = false,
    val scrollable: Boolean = false
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val label: String? get() = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }
}

data class ScreenSpec(
    val width: Int,
    val height: Int,
    val orientation: String,
    val packageName: String?
)

data class LutValidationResult(
    val isValid: Boolean,
    val messages: List<String>
) {
    val displayText: String
        get() = if (messages.isEmpty()) "OK" else messages.joinToString("; ")

    companion object {
        val Missing = LutValidationResult(false, listOf("Pressure LUT missing"))
        val Ok = LutValidationResult(true, emptyList())
    }
}

data class PressureCommandResult(
    val accepted: Boolean,
    val message: String,
    val pressureBar: Double? = null,
    val point: PressurePoint? = null
)

data class SafetyConfig(
    val minPressureBar: Double = 0.0,
    val maxPressureBar: Double = 12.0,
    val maxReadableWeightG: Double = 150.0,
    val maxFlowGps: Double = 8.0,
    val pressureCommandIntervalMs: Long = 250L,
    val minPressureDeltaBar: Double = 0.15,
    // Closed-loop "we're there, stop re-sliding" tolerance, compared against the bar's
    // live reading. Must exceed the slider's landing jitter (~±0.2 bar) so a fixed stage
    // doesn't oscillate, while still being tight enough to keep pressure on target. The
    // retry loop this enables also recovers the first slide(s) the e-bar drops right
    // after Start.
    val closedLoopDeadbandBar: Double = 0.3,
    val missingWeightTimeoutMs: Long = 2_000L,
    val fallbackStopRx: Double = 2860.0 / BuiltInPressureLut.REFERENCE_WIDTH,
    val fallbackStopRy: Double = 119.0 / BuiltInPressureLut.REFERENCE_HEIGHT,
    val firstDropThresholdG: Double = 0.1
)

data class PressureLutPointTemplate(
    val pressureBar: Double,
    val rx: Double,
    val ry: Double
)

object BuiltInPressureLut {
    const val NAME = "E-Bar pressure LUT (built-in 0-12 bar)"
    const val ANCHORED_NAME = "E-Bar pressure LUT (auto-anchored)"
    const val REFERENCE_WIDTH = 3120
    const val REFERENCE_HEIGHT = 1440

    // Bounds of the pressure SeekBar in the reference (3.0.x) layout that the tap
    // points below were calibrated against. The hardcoded y values sit inside this
    // span (thumb insets), so expressing each point as a fraction of these bounds
    // lets us re-anchor the whole LUT onto a bar that has moved or been resized in a
    // newer e-bar version — see [buildAnchoredFrom].
    private const val REFERENCE_BAR_TOP = 434.0
    private const val REFERENCE_BAR_BOTTOM = 1256.0

    // The 3.1.0 release merges the value readout and the slider track into a single
    // scrollable View (no child SeekBar). These are the Y positions of each integer bar
    // as a fraction of that node's height, measured by a calibration sweep on the real
    // app (reading back the live value at each position): index = bar, 0 near the bottom,
    // 12 near the top. The shot-relevant 2-11 bar range is measured directly; 0/1/12 are
    // extrapolated along the (very linear) trend and clamped onto the track. Used only
    // for the merged-View bar; the SeekBar path keeps the reference-derived fractions so
    // 3.0.x reproduces the hand-calibrated LUT exactly.
    private val FLAT_BAR_FRACTIONS = doubleArrayOf(
        1.035, // 0 bar — released just past the node bottom to force the slider to true 0
        0.936, // 1 bar
        0.851, // 2 bar
        0.768, // 3 bar
        0.684, // 4 bar
        0.597, // 5 bar
        0.508, // 6 bar
        0.425, // 7 bar
        0.341, // 8 bar
        0.258, // 9 bar
        0.175, // 10 bar
        0.086, // 11 bar
        0.010  // 12 bar
    )

    // How far below the bar node the 0-bar release may extend (fraction of node height).
    private const val BOTTOM_OVERSHOOT_FRACTION = 0.06

    private fun rx(x: Double) = x / REFERENCE_WIDTH
    private fun ry(y: Double) = y / REFERENCE_HEIGHT

    val points: List<PressureLutPointTemplate> = listOf(
        PressureLutPointTemplate(0.0,  rx(2925.0), ry(1164.0)),
        PressureLutPointTemplate(1.0,  rx(2925.0), ry(1112.0)),
        PressureLutPointTemplate(2.0,  rx(2925.0), ry(1061.0)),
        PressureLutPointTemplate(3.0,  rx(2925.0), ry(1005.0)),
        PressureLutPointTemplate(4.0,  rx(2925.0), ry( 952.0)),
        PressureLutPointTemplate(5.0,  rx(2925.0), ry( 898.0)),
        PressureLutPointTemplate(6.0,  rx(2925.0), ry( 844.0)),
        PressureLutPointTemplate(7.0,  rx(2925.0), ry( 791.0)),
        PressureLutPointTemplate(8.0,  rx(2925.0), ry( 738.0)),
        PressureLutPointTemplate(9.0,  rx(2925.0), ry( 684.0)),
        PressureLutPointTemplate(10.0, rx(2925.0), ry( 631.0)),
        PressureLutPointTemplate(11.0, rx(2925.0), ry( 590.0)),
        PressureLutPointTemplate(12.0, rx(2925.0), ry( 550.0))
    )

    fun buildFor(screenWidth: Int, screenHeight: Int): PressureLut? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        // Ratios are calibrated against the landscape layout (x along the long side,
        // y along the short side). Normalize so we always emit the landscape LUT,
        // regardless of how Android currently reports orientation.
        val longSide = maxOf(screenWidth, screenHeight)
        val shortSide = minOf(screenWidth, screenHeight)
        return PressureLut(
            name = NAME,
            screenWidth = longSide,
            screenHeight = shortSide,
            orientation = "landscape",
            points = points.map {
                PressurePoint(
                    pressureBar = it.pressureBar,
                    x = (it.rx * longSide).toFloat(),
                    y = (it.ry * shortSide).toFloat()
                )
            }
        )
    }

    /**
     * Locates the pressure bar in the live accessibility tree and, if found, builds a
     * LUT anchored to its actual on-screen bounds. The bar is identified purely by
     * layout (a tall, thin, vertically-oriented control on the right half of the
     * screen — the flow bar is the mirror image on the left), so it survives the e-bar
     * app moving or restyling the control, and works whether the bar is exposed as a
     * [android.widget.SeekBar] (3.0.x) or a scrollable View (3.1.0+).
     *
     * Returns null when no confident match exists; callers fall back to [buildFor].
     */
    fun buildAnchored(nodes: List<AccessibilityNodeBounds>, screenWidth: Int, screenHeight: Int): PressureLut? {
        val bar = findPressureBar(nodes, screenWidth, screenHeight) ?: return null
        return buildAnchoredFrom(bar, screenWidth, screenHeight)
    }

    fun findPressureBar(
        nodes: List<AccessibilityNodeBounds>,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeBounds? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val longSide = maxOf(screenWidth, screenHeight)
        val shortSide = minOf(screenWidth, screenHeight)
        return nodes.asSequence()
            .filter { it.width > 0 && it.height > 0 }
            .filter { it.height >= shortSide * 0.4 }      // the bar spans most of the height
            .filter { it.height >= it.width * 2 }         // tall and thin
            .filter { it.centerX >= longSide * 0.5 }      // right half: pressure, not the flow bar
            .filter { it.scrollable || it.className?.contains("SeekBar", ignoreCase = true) == true }
            .maxByOrNull { it.height }
    }

    fun buildAnchoredFrom(bar: AccessibilityNodeBounds, screenWidth: Int, screenHeight: Int): PressureLut? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val barHeight = bar.height.toDouble()
        if (barHeight <= 0.0) return null
        val longSide = maxOf(screenWidth, screenHeight)
        val shortSide = minOf(screenWidth, screenHeight)
        val centerX = bar.centerX.toFloat()

        // A SeekBar (3.0.x) exposes the bare track, so each calibrated point maps as a
        // fraction of the reference bar. The 3.1.0 merged View needs its own endpoints.
        val isSeekBar = bar.className?.contains("SeekBar", ignoreCase = true) == true
        val referenceSpan = REFERENCE_BAR_BOTTOM - REFERENCE_BAR_TOP

        fun yFor(template: PressureLutPointTemplate): Float = if (isSeekBar) {
            val referenceY = template.ry * REFERENCE_HEIGHT
            val fraction = (referenceY - REFERENCE_BAR_TOP) / referenceSpan
            (bar.top + fraction * barHeight).toFloat()
        } else {
            val fraction = FLAT_BAR_FRACTIONS[template.pressureBar.roundToInt().coerceIn(0, 12)]
            // Allow a little overshoot below the node bottom so the 0-bar point can be
            // released past the track to force a true 0 (the bar's center-x sits in the
            // gap between the +/- buttons, so this never lands on a button).
            val maxY = bar.bottom + barHeight * BOTTOM_OVERSHOOT_FRACTION
            (bar.top + fraction * barHeight).toFloat().coerceIn(bar.top.toFloat(), maxY.toFloat())
        }

        return PressureLut(
            name = ANCHORED_NAME,
            screenWidth = longSide,
            screenHeight = shortSide,
            orientation = "landscape",
            points = points.map { template ->
                PressurePoint(pressureBar = template.pressureBar, x = centerX, y = yFor(template))
            }
        )
    }
}
