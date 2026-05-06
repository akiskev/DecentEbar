package dev.akiskev.decentebar.engine

import kotlin.math.max
import kotlin.math.min

class FlowEstimator(
    private val maxFlowGps: Double = 8.0,
    private val smoothingPreviousWeight: Double = 0.75,
    private val smoothingRawWeight: Double = 0.25
) {
    private var previousTimeMs: Long? = null
    private var previousWeightG: Double? = null
    private var smoothedFlowGps: Double = 0.0

    fun reset() {
        previousTimeMs = null
        previousWeightG = null
        smoothedFlowGps = 0.0
    }

    fun addSample(timeMs: Long, weightG: Double): Double {
        val lastTime = previousTimeMs
        val lastWeight = previousWeightG

        previousTimeMs = timeMs
        previousWeightG = weightG

        if (lastTime == null || lastWeight == null) {
            smoothedFlowGps = 0.0
            return smoothedFlowGps
        }

        val deltaTimeS = (timeMs - lastTime) / 1000.0
        if (deltaTimeS <= 0.0) return smoothedFlowGps

        val rawFlow = max(0.0, (weightG - lastWeight) / deltaTimeS)
        val boundedRawFlow = min(rawFlow, maxFlowGps)
        smoothedFlowGps = smoothingPreviousWeight * smoothedFlowGps + smoothingRawWeight * boundedRawFlow
        return smoothedFlowGps
    }
}
