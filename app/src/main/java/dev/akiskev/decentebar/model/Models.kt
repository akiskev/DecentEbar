package dev.akiskev.decentebar.model

import kotlinx.serialization.Serializable

const val EBAR_PACKAGE_NAME = "com.g472631889.stfbeta"

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

@Serializable
data class ShotSample(
    val timeMs: Long,
    val weightG: Double,
    val flowGps: Double,
    val commandedPressureBar: Double?,
    val stageName: String
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
    val events: List<ShotEvent>
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
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val orientation: String = "unknown"
) {
    val hasPressureControls: Boolean
        get() = hasPressurePriority || hasFlowRatePriority || hasPressureLabel
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
    val pressureCommandIntervalMs: Long = 400L,
    val minPressureDeltaBar: Double = 0.15,
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
    const val REFERENCE_WIDTH = 3120
    const val REFERENCE_HEIGHT = 1440

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
}

object DefaultProfiles {
    val flow34 = ShotProfile(
        name = "Flow 34",
        targetWeightG = 33.0,
        stopOffsetG = 1.2,
        maxShotTimeMs = 45_000L,
        stages = listOf(
            ProfileStage(
                name = "Preinfusion",
                type = StageType.FIXED_PRESSURE,
                fixedPressureBar = 7.0,
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
                correctionIntervalMs = 600L,
                pressureStepMultiplierMax = 8.0,
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
                correctionIntervalMs = 600L,
                pressureStepMultiplierMax = 8.0,
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
