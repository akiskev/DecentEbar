package dev.akiskev.decentebar.ui

import dev.akiskev.decentebar.model.ExitCondition
import dev.akiskev.decentebar.model.ProfileStage
import dev.akiskev.decentebar.model.StageSafety
import dev.akiskev.decentebar.model.StageType

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
            correctionIntervalMs = correctionIntervalMs ?: 600L
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

internal fun StageType.shortName(): String {
    return when (this) {
        StageType.FIXED_PRESSURE -> "Fixed"
        StageType.FLOW_LIMITED_PRESSURE -> "Flow"
        StageType.WEIGHT_BASED_PRESSURE_RAMP -> "Weight Ramp"
        StageType.TIME_BASED_PRESSURE_RAMP -> "Time Ramp"
        StageType.STOP -> "Stop"
    }
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
