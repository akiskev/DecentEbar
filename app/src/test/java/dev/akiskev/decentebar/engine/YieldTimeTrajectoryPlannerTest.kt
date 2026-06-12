package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.CurvePoint
import dev.akiskev.decentebar.model.FlowCurveType
import dev.akiskev.decentebar.model.TastePriorityMode
import dev.akiskev.decentebar.model.YieldTimeTrajectoryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [YieldTimeTrajectoryPlanner] (docs/yield-time-trajectory.md). The
 * FLAT-curve cases exploit the fact that a normalized flat 30 g / 30 s curve has
 * plannedFlow == 1.0 g/s and plannedWeight(t) == t, so the catch-up/late-window arithmetic is
 * exactly verifiable.
 */
class YieldTimeTrajectoryPlannerTest {

    private fun config(
        curveType: FlowCurveType = FlowCurveType.FLAT,
        yieldG: Double = 30.0,
        durationS: Double = 30.0,
        correctionStrength: Double = 0.5,
        lateShotCorrectionLimitS: Double = 5.0,
        tastePriorityMode: TastePriorityMode = TastePriorityMode.BALANCED,
        maxFlowGps: Double = 8.0,
        customPoints: List<CurvePoint> = emptyList()
    ) = YieldTimeTrajectoryConfig(
        targetYieldG = yieldG,
        targetDurationS = durationS,
        curveType = curveType,
        maxFlowGps = maxFlowGps,
        correctionStrength = correctionStrength,
        lateShotCorrectionLimitS = lateShotCorrectionLimitS,
        tastePriorityMode = tastePriorityMode,
        customPoints = customPoints
    )

    // --- Curve normalization ------------------------------------------------------------------

    @Test
    fun everyCurveNormalizesToTargetYield() {
        val curves = listOf(
            config(FlowCurveType.FLAT),
            config(FlowCurveType.DECLINING),
            config(FlowCurveType.RAMP_THEN_DECLINE),
            config(FlowCurveType.BLOOMING_DECLINE),
            config(
                FlowCurveType.CUSTOM_POINTS,
                customPoints = listOf(
                    CurvePoint(0.0, 0.7),
                    CurvePoint(0.3, 1.3),
                    CurvePoint(1.0, 0.8)
                )
            )
        )
        for (cfg in curves) {
            val p = YieldTimeTrajectoryPlanner().apply { configure(cfg) }
            assertEquals("start weight", 0.0, p.plannedWeightAt(0.0), 1e-6)
            assertEquals(
                "${cfg.curveType} integrates to target yield",
                30.0, p.plannedWeightAt(30.0), 0.02
            )
        }
    }

    @Test
    fun decliningCurveDeclinesEvenWhenStartHintIsBelowEnd() {
        // The shape hints are shared across presets (and the defaults have start < end), so the
        // DECLINING preset must enforce its own direction rather than trusting start → end.
        val cfg = config(FlowCurveType.DECLINING).copy(startFlowGps = 0.5, endFlowGps = 1.0)
        val p = YieldTimeTrajectoryPlanner().apply { configure(cfg) }
        assertTrue(
            "DECLINING must start above where it ends",
            p.plannedFlowAt(0.0) > p.plannedFlowAt(30.0)
        )
        var prev = Double.MAX_VALUE
        var t = 0.0
        while (t <= 30.0) {
            val f = p.plannedFlowAt(t)
            assertTrue("flow must not increase at t=$t (prev=$prev, f=$f)", f <= prev + 1e-9)
            prev = f
            t += 1.0
        }
    }

    @Test
    fun plannedWeightIsMonotonic() {
        val p = YieldTimeTrajectoryPlanner().apply { configure(config(FlowCurveType.RAMP_THEN_DECLINE)) }
        var prev = -1.0
        var t = 0.0
        while (t <= 30.0) {
            val w = p.plannedWeightAt(t)
            assertTrue("weight must not decrease at t=$t (prev=$prev, w=$w)", w >= prev - 1e-9)
            prev = w
            t += 1.0
        }
    }

    @Test
    fun flatCurveHasUnitFlow() {
        val p = YieldTimeTrajectoryPlanner().apply { configure(config(FlowCurveType.FLAT)) }
        assertEquals(1.0, p.plannedFlowAt(0.0), 1e-6)
        assertEquals(1.0, p.plannedFlowAt(15.0), 1e-6)
        assertEquals(15.0, p.plannedWeightAt(15.0), 1e-6)
    }

    // --- Catch-up direction -------------------------------------------------------------------

    @Test
    fun behindScheduleRaisesCorrectedFlowAbovePlanned() {
        val p = YieldTimeTrajectoryPlanner().apply { configure(config()) }
        // FLAT planned weight at 15 s is 15 g; 10 g actual = 5 g behind.
        val tick = p.evaluate(15_000, 10.0)
        assertTrue("corrected ${tick.correctedTargetFlowGps} should exceed planned ${tick.plannedFlowGps}",
            tick.correctedTargetFlowGps > tick.plannedFlowGps)
        assertEquals("CATCHUP", tick.mode)
    }

    @Test
    fun aheadOfScheduleLowersCorrectedFlowBelowPlanned() {
        val p = YieldTimeTrajectoryPlanner().apply { configure(config()) }
        // 20 g actual at 15 s = 5 g ahead of the 15 g plan.
        val tick = p.evaluate(15_000, 20.0)
        assertTrue("corrected ${tick.correctedTargetFlowGps} should be below planned ${tick.plannedFlowGps}",
            tick.correctedTargetFlowGps < tick.plannedFlowGps)
        assertEquals("EASING", tick.mode)
    }

    @Test
    fun correctedFlowDecreasesMonotonicallyWithYieldGained() {
        val p = YieldTimeTrajectoryPlanner().apply { configure(config()) }
        val behind = p.evaluate(15_000, 10.0).correctedTargetFlowGps
        val onTrack = p.evaluate(15_000, 15.0).correctedTargetFlowGps
        val ahead = p.evaluate(15_000, 20.0).correctedTargetFlowGps
        assertTrue(behind > onTrack)
        assertTrue(onTrack > ahead)
    }

    // --- Clamping -----------------------------------------------------------------------------

    @Test
    fun correctedFlowIsClampedToMaxFlow() {
        val p = YieldTimeTrajectoryPlanner().apply { configure(config(maxFlowGps = 1.1)) }
        // Very behind (0 g at 15 s) wants a large catch-up flow; must clamp to 1.1.
        val tick = p.evaluate(15_000, 0.0)
        assertEquals(1.1, tick.correctedTargetFlowGps, 1e-6)
    }

    // --- Late-shot taste protection -----------------------------------------------------------

    @Test
    fun lateWindowShrinksTheCorrection() {
        val p = YieldTimeTrajectoryPlanner().apply { configure(config()) }
        // FLAT: plannedFlow = 1.0. Both ticks are 2 g behind plan.
        // t1 = 15 s (remaining 15 > 5 s window): full strength 0.5.
        val t1 = p.evaluate(15_000, 13.0)
        val catchup1 = (30.0 - 13.0) / (30.0 - 15.0)
        assertEquals(1.0 + 0.5 * (catchup1 - 1.0), t1.correctedTargetFlowGps, 1e-3)
        // t2 = 27 s (remaining 3 <= 5 s window): late factor 3/5 -> strength 0.3.
        val t2 = p.evaluate(27_000, 25.0)
        val catchup2 = (30.0 - 25.0) / (30.0 - 27.0)
        assertEquals(1.0 + 0.3 * (catchup2 - 1.0), t2.correctedTargetFlowGps, 1e-3)
        assertEquals("LATE_LIMIT", t2.mode)
    }

    @Test
    fun tasteSafeCapsLateIncrease() {
        val p = YieldTimeTrajectoryPlanner().apply {
            configure(config(tastePriorityMode = TastePriorityMode.TASTE_SAFE))
        }
        // Strongly behind inside the late window: corrected would blow past the planned flow,
        // but TASTE_SAFE caps the increase to 25% over planned (1.0 -> 1.25).
        val tick = p.evaluate(27_000, 0.0)
        assertEquals(1.25, tick.correctedTargetFlowGps, 1e-3)
        assertEquals("TASTE_CAP", tick.mode)
    }

    @Test
    fun lateBehindShotIsCappedNotRescuedViolently() {
        // The worst case to guard: target 30 g / 30 s, only 23 g at 27 s. The naive catch-up flow
        // is 7 g / 3 s = 2.33 g/s, which would wreck the extraction. TASTE_SAFE must keep the
        // corrected target near the planned flow instead of chasing the arithmetic.
        val cfg = config(
            curveType = FlowCurveType.RAMP_THEN_DECLINE,
            correctionStrength = 0.35,
            tastePriorityMode = TastePriorityMode.TASTE_SAFE,
            maxFlowGps = 1.6
        )
        val p = YieldTimeTrajectoryPlanner().apply { configure(cfg) }
        val tick = p.evaluate(27_000, 23.0)
        val planned = p.plannedFlowAt(27.0)
        val naiveCatchup = (30.0 - 23.0) / (30.0 - 27.0) // 2.33 g/s

        assertEquals("TASTE_CAP", tick.mode)
        assertTrue(
            "corrected ${tick.correctedTargetFlowGps} must stay far below naive catch-up $naiveCatchup",
            tick.correctedTargetFlowGps < 1.5
        )
        // Hard guarantee: late increase capped to <=25% over the planned flow.
        assertEquals(planned * 1.25, tick.correctedTargetFlowGps, 1e-6)
    }

    @Test
    fun strictTargetKeepsChasingLate() {
        val balanced = YieldTimeTrajectoryPlanner().apply {
            configure(config(tastePriorityMode = TastePriorityMode.BALANCED, maxFlowGps = 8.0))
        }.evaluate(29_000, 20.0).correctedTargetFlowGps
        val strict = YieldTimeTrajectoryPlanner().apply {
            configure(config(tastePriorityMode = TastePriorityMode.STRICT_TARGET, maxFlowGps = 8.0))
        }.evaluate(29_000, 20.0).correctedTargetFlowGps
        // Both are behind at 29 s; STRICT keeps a higher correction floor, so it chases harder.
        assertTrue("strict ($strict) should chase at least as hard as balanced ($balanced)",
            strict >= balanced)
    }
}
