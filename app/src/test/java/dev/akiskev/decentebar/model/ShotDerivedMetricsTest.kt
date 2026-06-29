package dev.akiskev.decentebar.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShotDerivedMetricsTest {
    @Test
    fun finalYieldAndDurationUseShotLogData() {
        val log = sampleLog(
            startedAtMs = 1_000L,
            stoppedAtMs = 32_500L,
            samples = listOf(
                sample(timeMs = 0L, weightG = 0.0),
                sample(timeMs = 29_000L, weightG = 38.4)
            )
        )

        assertEquals(38.4, ShotDerivedMetrics.finalYieldG(log)!!, 0.0001)
        assertEquals(31_500L, ShotDerivedMetrics.durationMs(log))
    }

    @Test
    fun durationFallsBackToLastSampleTime() {
        val log = sampleLog(
            startedAtMs = null,
            stoppedAtMs = null,
            samples = listOf(
                sample(timeMs = 500L, weightG = 0.0),
                sample(timeMs = 12_000L, weightG = 24.0)
            )
        )

        assertEquals(12_000L, ShotDerivedMetrics.durationMs(log))
    }

    @Test
    fun firstDropUsesFirstDropEventAndNormalizesAbsoluteSampleTimes() {
        val log = sampleLog(
            samples = listOf(
                sample(timeMs = 10_000L, weightG = 0.0),
                sample(timeMs = 14_000L, weightG = 2.0)
            ),
            events = listOf(
                ShotEvent(
                    timeMs = 13_000L,
                    type = ShotEventType.FIRST_DROP,
                    message = "First drop"
                )
            )
        )

        assertEquals(3_000L, ShotDerivedMetrics.firstDropMs(log))
    }

    @Test
    fun firstDropIsNullWhenEventIsMissing() {
        assertNull(ShotDerivedMetrics.firstDropMs(sampleLog(events = emptyList())))
    }

    @Test
    fun normalizedSeriesStartsAtZeroAndSkipsMissingPressure() {
        val log = sampleLog(
            samples = listOf(
                sample(timeMs = 1_000L, weightG = 0.0, flowGps = 0.0, pressureBar = null),
                sample(timeMs = 1_500L, weightG = 4.0, flowGps = 0.8, pressureBar = 5.0),
                sample(timeMs = 2_500L, weightG = 9.0, flowGps = 1.1, pressureBar = 7.0)
            )
        )

        assertEquals(
            listOf(
                ShotMetricPoint(0L, 0.0),
                ShotMetricPoint(500L, 0.8),
                ShotMetricPoint(1_500L, 1.1)
            ),
            ShotDerivedMetrics.normalizedSeries(log, ShotMetric.FLOW)
        )
        assertEquals(
            listOf(
                ShotMetricPoint(500L, 5.0),
                ShotMetricPoint(1_500L, 7.0)
            ),
            ShotDerivedMetrics.normalizedSeries(log, ShotMetric.PRESSURE)
        )
    }

    private fun sampleLog(
        startedAtMs: Long? = 1_000L,
        stoppedAtMs: Long? = 31_000L,
        samples: List<ShotSample> = listOf(sample(timeMs = 0L), sample(timeMs = 30_000L, weightG = 40.0)),
        events: List<ShotEvent> = emptyList()
    ): ShotLog = ShotLog(
        profileName = "Profile",
        startedAtMs = startedAtMs,
        stoppedAtMs = stoppedAtMs,
        samples = samples,
        events = events
    )

    private fun sample(
        timeMs: Long,
        weightG: Double = 0.0,
        flowGps: Double = 0.0,
        pressureBar: Double? = 3.0
    ): ShotSample = ShotSample(
        timeMs = timeMs,
        weightG = weightG,
        flowGps = flowGps,
        commandedPressureBar = pressureBar,
        stageName = "Main"
    )
}
