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
    val maxShotTimeMs: Long,
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
    val exit: ExitCondition = ExitCondition(),
    val safety: StageSafety = StageSafety()
)

@Serializable
enum class StageType {
    FIXED_PRESSURE,
    FLOW_LIMITED_PRESSURE,
    WEIGHT_BASED_PRESSURE_RAMP,
    TIME_BASED_PRESSURE_RAMP,
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

@Serializable
data class ShotSample(
    val timeMs: Long,
    val weightG: Double,
    val flowGps: Double,
    val commandedPressureBar: Double?,
    val stageName: String,
    val altFlowGps: Double? = null  // software-estimated flow when scale is connected, for comparison
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
    // Auto-captured context for reproducibility (docs/puck-resistance-feedforward.md Tier 1).
    val appVersion: String? = null,
    val flowSource: String? = null,        // "scale" (BLE) or "accessibility" (estimated)
    val scaleBatteryPercent: Int? = null,
    // Full profile snapshot — captures the exact stage params and any FeedForwardConfig used,
    // so a shot is fully reproducible (profileName above is kept for convenience).
    val profile: ShotProfile? = null
)

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

object DefaultProfiles {
    val flow33Dark = ShotProfile(
        name = "Flow 33 dark",
        targetWeightG = 33.0,
        stopOffsetG = 1.2,
        maxShotTimeMs = 45_000L,
        stages = listOf(
            ProfileStage(
                name = "Preinfusion",
                type = StageType.FIXED_PRESSURE,
                fixedPressureBar = 6.9,
                exit = ExitCondition(stageTimeGteMs = 15_000L, firstDropDetected = true),
                safety = StageSafety(requireTwoConsecutiveFirstDropReadings = true)
            ),
            ProfileStage(
                name = "Wait",
                type = StageType.FIXED_PRESSURE,
                fixedPressureBar = 0.0,
                exit = ExitCondition(weightGte = 6.0, stageTimeGteMs = 5_000L),
                safety = StageSafety()
            ),
            ProfileStage(
                name = "Main",
                type = StageType.FLOW_LIMITED_PRESSURE,
                pressureCapBar = 9.0,
                targetFlowGps = 1.9,
                flowDeadbandGps = 0.1,
                pressureStepBar = 0.2,
                correctionIntervalMs = 200L,
                exit = ExitCondition(weightGte = 27.0),
                safety = StageSafety(maxStageTimeMs = 35_000L)
            ),
            ProfileStage(
                name = "Fade",
                type = StageType.FLOW_LIMITED_PRESSURE,
                pressureCapBar = 8.0,
                targetFlowGps = 1.6,
                flowDeadbandGps = 0.1,
                pressureStepBar = 0.2,
                correctionIntervalMs = 100L,
                rampEndPressureBar = 5.0,
                rampStartWeightG = 28.0,
                rampEndWeightG = 35.0,
                exit = ExitCondition(weightGte = 32.0),
                safety = StageSafety()
            ),
            ProfileStage(
                name = "Stop",
                type = StageType.STOP,
                exit = ExitCondition(stageTimeGteMs = 1L)
            )
        )
    )
}
