package dev.akiskev.decentebar.engine

import kotlin.math.max
import kotlin.math.min

class FlowEstimator(
    private val maxFlowGps: Double = 8.0,
    private val smoothingPreviousWeight: Double = 0.75,
    private val smoothingRawWeight: Double = 0.25,
) {
    private var lastKnownWeightG: Double? = null
    private var lastWeightChangeMs: Long? = null
    private var smoothedFlowGps: Double = 0.0

    fun reset() {
        lastKnownWeightG = null
        lastWeightChangeMs = null
        smoothedFlowGps = 0.0
    }

    fun addSample(timeMs: Long, weightG: Double): Double {
        val prevWeight = lastKnownWeightG
        val lastChangeMs = lastWeightChangeMs

        if (prevWeight == null || lastChangeMs == null) {
            lastKnownWeightG = weightG
            lastWeightChangeMs = timeMs
            smoothedFlowGps = 0.0
            return smoothedFlowGps
        }

        if (weightG == prevWeight) {
            // Scale hasn't reported a new reading yet. Hold the last estimate — don't decay
            // toward zero, because during slow stages (Wait at 0 bar, early Preinfusion)
            // the weight can be stable for several seconds without extraction having stopped.
            return smoothedFlowGps
        }

        // Weight changed: use the full interval since the previous scale reading as the
        // denominator, not just the last snapshot gap. Without this, a 0.2g step detected
        // in a single 60ms accessibility frame would produce a phantom 3+ g/s spike even
        // though the actual flow was ~0.4 g/s over the ~500ms between scale updates.
        val deltaTimeS = (timeMs - lastChangeMs) / 1000.0
        lastKnownWeightG = weightG
        lastWeightChangeMs = timeMs

        if (deltaTimeS <= 0.0) return smoothedFlowGps

        val rawFlow = max(0.0, (weightG - prevWeight) / deltaTimeS)
        val bounded = min(rawFlow, maxFlowGps)
        smoothedFlowGps = smoothingPreviousWeight * smoothedFlowGps + smoothingRawWeight * bounded
        return smoothedFlowGps
    }
}
