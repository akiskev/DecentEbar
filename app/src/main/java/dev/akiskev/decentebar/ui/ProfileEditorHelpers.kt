package dev.akiskev.decentebar.ui

import dev.akiskev.decentebar.engine.YieldTimeTrajectoryPlanner
import dev.akiskev.decentebar.model.CurvePoint
import dev.akiskev.decentebar.model.ExitCondition
import dev.akiskev.decentebar.model.FlowCurveType
import dev.akiskev.decentebar.model.PressureCurveAxis
import dev.akiskev.decentebar.model.PressureCurveConfig
import dev.akiskev.decentebar.model.PressureCurvePoint
import dev.akiskev.decentebar.model.ProfileStage
import dev.akiskev.decentebar.model.StageSafety
import dev.akiskev.decentebar.model.StageType
import dev.akiskev.decentebar.model.TastePriorityMode
import dev.akiskev.decentebar.model.YieldTimeTrajectoryConfig

internal fun newStage(): ProfileStage {
    return ProfileStage(
        name = "New Stage",
        type = StageType.FIXED_PRESSURE,
        fixedPressureBar = 2.0,
        exit = ExitCondition(weightGte = 1.0),
        safety = StageSafety()
    )
}

internal fun ProfileStage.withTypeDefaults(newType: StageType): ProfileStage {
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
            correctionIntervalMs = correctionIntervalMs ?: 600L,
            pressureStepMultiplierMax = pressureStepMultiplierMax ?: 8.0
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
        StageType.YIELD_TIME_TRAJECTORY -> {
            // A fresh yield/time stage gets a modest extraction floor on by default — a 0-bar tail
            // under-extracts (sour), so this is a better out-of-the-box recipe.
            val cfg = yieldTime ?: YieldTimeTrajectoryConfig(minExtractionPressureBar = 2.5)
            copy(
                type = newType,
                yieldTime = cfg,
                // Weight target ends the shot (the global stop-offset bounds it); the trajectory's
                // own time completion is post-first-drop, handled by the controller — NOT stage
                // time, which would include pre-infusion and end the shot early.
                exit = ExitCondition(weightGte = cfg.targetYieldG),
                safety = StageSafety(
                    maxStageTimeMs = ((cfg.targetDurationS + cfg.preInfusionMaxS + 10.0) * 1000).toLong(),
                    requireTwoConsecutiveFirstDropReadings = true
                )
            )
        }
        StageType.PRESSURE_CURVE -> {
            val cfg = pressureCurve ?: PressureCurveConfig(points = defaultPressurePoints())
            copy(
                type = newType,
                pressureCurve = cfg,
                // Default exit matches the curve's X axis: TIME ends at the drawn duration, WEIGHT at
                // the drawn max weight (absolute cup grams). The global target-stop still bounds it.
                exit = when (cfg.axis) {
                    PressureCurveAxis.TIME -> ExitCondition(stageTimeGteMs = (cfg.durationS * 1000).toLong())
                    PressureCurveAxis.WEIGHT -> ExitCondition(weightGte = cfg.maxWeightG)
                }
            )
        }
        StageType.STOP -> copy(type = newType)
    }
}

internal fun StageType.shortName(): String {
    return when (this) {
        StageType.FIXED_PRESSURE -> "Fixed"
        StageType.FLOW_LIMITED_PRESSURE -> "Flow"
        StageType.WEIGHT_BASED_PRESSURE_RAMP -> "Weight Ramp"
        StageType.TIME_BASED_PRESSURE_RAMP -> "Time Ramp"
        StageType.YIELD_TIME_TRAJECTORY -> "Yield/Time"
        StageType.PRESSURE_CURVE -> "Pressure Curve"
        StageType.STOP -> "Stop"
    }
}

/** A sensible starting pressure profile: hold 9 bar, then decline to 6 bar — the classic shape. */
internal fun defaultPressurePoints(): List<PressureCurvePoint> = listOf(
    PressureCurvePoint(0.0, 9.0),
    PressureCurvePoint(0.3, 9.0),
    PressureCurvePoint(1.0, 6.0),
)

/** A sensible starting curve when the user first switches to a hand-drawn CUSTOM_POINTS curve. */
internal fun defaultCustomPoints(): List<CurvePoint> = listOf(
    CurvePoint(0.0, 0.7),
    CurvePoint(0.35, 1.3),
    CurvePoint(1.0, 0.8),
)

/**
 * Points for the inline curve preview, for ANY curve type: for CUSTOM_POINTS the drawn points,
 * otherwise the planner's normalized planned flow sampled across the shot — so the analytic shapes
 * (flat / declining / ramp / blooming) get a preview too.
 */
internal fun YieldTimeTrajectoryConfig.previewPoints(samples: Int = 24): List<CurvePoint> {
    if (curveType == FlowCurveType.CUSTOM_POINTS) return customPoints
    val planner = YieldTimeTrajectoryPlanner().apply { configure(this@previewPoints) }
    val dur = targetDurationS.coerceAtLeast(0.5)
    return (0..samples).map { i ->
        val frac = i.toDouble() / samples
        CurvePoint(frac, planner.plannedFlowAt(frac * dur))
    }
}

internal fun FlowCurveType.uiLabel(): String = when (this) {
    FlowCurveType.FLAT -> "Flat"
    FlowCurveType.DECLINING -> "Declining"
    FlowCurveType.RAMP_THEN_DECLINE -> "Ramp then decline"
    FlowCurveType.BLOOMING_DECLINE -> "Blooming decline"
    FlowCurveType.CUSTOM_POINTS -> "Custom"
}

internal fun TastePriorityMode.uiLabel(): String = when (this) {
    TastePriorityMode.STRICT_TARGET -> "Strict"
    TastePriorityMode.BALANCED -> "Balanced"
    TastePriorityMode.TASTE_SAFE -> "Taste-safe"
}

internal fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, item -> if (i == index) value else item }

internal fun <T> List<T>.removeAt(index: Int): List<T> =
    filterIndexed { i, _ -> i != index }

internal fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    val mutable = toMutableList()
    val value = mutable.removeAt(from)
    mutable.add(to, value)
    return mutable
}
