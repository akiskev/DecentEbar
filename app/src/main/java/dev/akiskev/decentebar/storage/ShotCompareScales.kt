package dev.akiskev.decentebar.storage

import dev.akiskev.decentebar.model.ShotDerivedMetrics
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotMetric
import kotlin.math.ceil
import kotlin.math.max

data class ShotCompareScales(
    val durationMs: Long,
    val pressureMax: Double,
    val flowMax: Double,
    val weightMax: Double
)

object ShotCompareScaleCalculator {
    fun calculate(shotA: ShotLog, shotB: ShotLog): ShotCompareScales =
        ShotCompareScales(
            durationMs = maxDurationMs(shotA, shotB).coerceAtLeast(1L),
            pressureMax = roundedMax(
                values = listOf(shotA, shotB).flatMap { log ->
                    ShotDerivedMetrics.normalizedSeries(log, ShotMetric.PRESSURE).map { it.value }
                },
                minimum = 10.0,
                step = 1.0
            ),
            flowMax = roundedMax(
                values = listOf(shotA, shotB).flatMap { log ->
                    ShotDerivedMetrics.normalizedSeries(log, ShotMetric.FLOW).map { it.value } +
                        log.samples.mapNotNull { it.correctedTargetFlowGps ?: it.targetFlowGps } +
                        log.stageTargetFlows.values
                },
                minimum = 2.0,
                step = 0.5
            ),
            weightMax = roundedMax(
                values = listOf(shotA, shotB).flatMap { log ->
                    ShotDerivedMetrics.normalizedSeries(log, ShotMetric.WEIGHT).map { it.value } +
                        log.samples.mapNotNull { it.targetWeightG } +
                        listOfNotNull(log.targetYieldG)
                },
                minimum = 10.0,
                step = 5.0
            )
        )

    private fun maxDurationMs(shotA: ShotLog, shotB: ShotLog): Long {
        val direct = listOfNotNull(
            ShotDerivedMetrics.durationMs(shotA),
            ShotDerivedMetrics.durationMs(shotB)
        ).maxOrNull() ?: 0L
        val series = listOf(shotA, shotB)
            .flatMap { log ->
                listOf(ShotMetric.PRESSURE, ShotMetric.FLOW, ShotMetric.WEIGHT)
                    .flatMap { metric -> ShotDerivedMetrics.normalizedSeries(log, metric) }
            }
            .maxOfOrNull { it.timeMs } ?: 0L
        return max(direct, series)
    }

    private fun roundedMax(values: List<Double>, minimum: Double, step: Double): Double {
        val maxValue = values.filter { it.isFinite() }.maxOrNull() ?: minimum
        return ceil(max(maxValue, minimum) / step) * step
    }
}
