package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.CurvePoint
import dev.akiskev.decentebar.model.FlowCurveType
import dev.akiskev.decentebar.model.YieldTimeTrajectoryConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CurveMathTest {

    @Test
    fun areaOfFlatCurveIsFlowTimesDuration() {
        val flat = listOf(CurvePoint(0.0, 1.0), CurvePoint(1.0, 1.0))
        assertEquals(30.0, CurveMath.areaG(flat, 30.0), 1e-9)
    }

    @Test
    fun areaOfTriangle() {
        // 0 → 2 → 0 over the duration: mean flow 1.0 g/s × 10 s = 10 g.
        val tri = listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 2.0), CurvePoint(1.0, 0.0))
        assertEquals(10.0, CurveMath.areaG(tri, 10.0), 1e-9)
    }

    @Test
    fun cleanKnotsExtendsEndpoints() {
        // Interior-only points get flat endpoints at p=0 and p=1.
        val pts = listOf(CurvePoint(0.3, 1.0), CurvePoint(0.7, 1.0))
        val knots = CurveMath.cleanKnots(pts)
        assertEquals(0.0, knots.first().timePct, 1e-9)
        assertEquals(1.0, knots.last().timePct, 1e-9)
        // Flat 1.0 g/s after extension → area = duration.
        assertEquals(10.0, CurveMath.areaG(pts, 10.0), 1e-9)
    }

    @Test
    fun emptyCurveHasZeroArea() {
        assertEquals(0.0, CurveMath.areaG(emptyList(), 30.0), 1e-9)
    }

    @Test
    fun flowAtPctInterpolatesAndClampsToEndpoints() {
        val pts = listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 2.0))
        assertEquals(1.0, CurveMath.flowAtPct(pts, 0.5), 1e-9)
        assertEquals(0.0, CurveMath.flowAtPct(pts, -0.2), 1e-9)
        assertEquals(2.0, CurveMath.flowAtPct(pts, 1.5), 1e-9)
    }

    // --- The headline contract: drawing a curve + setting targetYieldG = areaG means the planner
    // reproduces the drawn flow exactly and lands on the drawn area. ---

    @Test
    fun plannerReproducesDrawnCurveWhenYieldEqualsArea() {
        val points = listOf(CurvePoint(0.0, 0.7), CurvePoint(0.35, 1.4), CurvePoint(1.0, 0.8))
        val dur = 28.0
        val area = CurveMath.areaG(points, dur)

        val p = YieldTimeTrajectoryPlanner().apply {
            configure(
                YieldTimeTrajectoryConfig(
                    targetYieldG = area,
                    targetDurationS = dur,
                    curveType = FlowCurveType.CUSTOM_POINTS,
                    customPoints = points
                )
            )
        }

        // Yield lands on the drawn area, and the planned flow equals the drawn flow (factor == 1).
        assertEquals(area, p.plannedWeightAt(dur), 1e-6)
        for (frac in listOf(0.1, 0.35, 0.6, 0.9)) {
            assertEquals(
                CurveMath.flowAtPct(points, frac),
                p.plannedFlowAt(frac * dur),
                1e-6
            )
        }
    }
}
