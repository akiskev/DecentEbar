package dev.akiskev.decentebar.storage

import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotSample
import org.junit.Assert.assertEquals
import org.junit.Test

class ShotCompareScalesTest {
    @Test
    fun scaleCalculationUsesBothLogsAndTargetSeries() {
        val shotA = sampleLog(
            stoppedAtMs = 32_000L,
            samples = listOf(
                sample(0L, weightG = 0.0, flowGps = 0.0, pressureBar = 3.0),
                sample(31_000L, weightG = 36.0, flowGps = 1.1, pressureBar = 8.0, targetFlowGps = 2.3)
            )
        )
        val shotB = sampleLog(
            stoppedAtMs = 37_000L,
            targetYieldG = 43.1,
            samples = listOf(
                sample(0L, weightG = 0.0, flowGps = 0.0, pressureBar = 4.0),
                sample(36_000L, weightG = 41.2, flowGps = 1.8, pressureBar = 11.2, targetWeightG = 43.1)
            )
        )

        val scales = ShotCompareScaleCalculator.calculate(shotA, shotB)

        assertEquals(36_000L, scales.durationMs)
        assertEquals(12.0, scales.pressureMax, 0.0001)
        assertEquals(2.5, scales.flowMax, 0.0001)
        assertEquals(45.0, scales.weightMax, 0.0001)
    }

    private fun sampleLog(
        stoppedAtMs: Long,
        targetYieldG: Double? = null,
        samples: List<ShotSample>
    ): ShotLog = ShotLog(
        profileName = "Profile",
        startedAtMs = 1_000L,
        stoppedAtMs = stoppedAtMs,
        samples = samples,
        events = emptyList(),
        stageTargetFlows = mapOf("Main" to 2.0),
        targetYieldG = targetYieldG
    )

    private fun sample(
        timeMs: Long,
        weightG: Double,
        flowGps: Double,
        pressureBar: Double,
        targetFlowGps: Double? = null,
        targetWeightG: Double? = null
    ): ShotSample = ShotSample(
        timeMs = timeMs,
        weightG = weightG,
        flowGps = flowGps,
        commandedPressureBar = pressureBar,
        stageName = "Main",
        targetFlowGps = targetFlowGps,
        targetWeightG = targetWeightG
    )
}
