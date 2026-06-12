package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.CurvePoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure helpers for a piecewise-linear CUSTOM_POINTS flow curve, shared by
 * [YieldTimeTrajectoryPlanner] and the graphical curve editor so the weight shown while drawing is
 * exactly the yield the planner will use.
 *
 * A curve is a list of [CurvePoint] (timePct in [0,1], flowGps in g/s). The beverage weight it
 * deposits over `durationS` is the area under it: `weight = durationS * ∫₀¹ flow(p) dp`. Setting a
 * yield/time stage's `targetYieldG` to [areaG] makes the planner's normalization a no-op, so the
 * planned flow equals exactly the drawn curve.
 */
object CurveMath {

    /**
     * Canonical "what the planner integrates" form: sort/clamp the points (timePct→[0,1],
     * flow≥0), drop duplicate times, and guarantee endpoints at p=0 and p=1 so the curve is a
     * well-formed, strictly-increasing polyline. An empty input yields a flat-zero curve.
     */
    fun cleanKnots(points: List<CurvePoint>): List<CurvePoint> {
        val cleaned = points
            .map { CurvePoint(it.timePct.coerceIn(0.0, 1.0), max(0.0, it.flowGps)) }
            .sortedBy { it.timePct }
            .fold(mutableListOf<CurvePoint>()) { acc, p ->
                if (acc.isEmpty() || abs(acc.last().timePct - p.timePct) > EPS) acc.add(p)
                acc
            }
        if (cleaned.isEmpty()) return listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 0.0))
        if (cleaned.first().timePct > 0.0) cleaned.add(0, CurvePoint(0.0, cleaned.first().flowGps))
        if (cleaned.last().timePct < 1.0) cleaned.add(CurvePoint(1.0, cleaned.last().flowGps))
        return cleaned
    }

    /** Beverage weight (g) the curve deposits over [durationS] — the area under the flow curve. */
    fun areaG(points: List<CurvePoint>, durationS: Double): Double {
        val knots = cleanKnots(points)
        var areaPerUnitP = 0.0
        for (i in 0 until knots.size - 1) {
            val a = knots[i]
            val b = knots[i + 1]
            areaPerUnitP += 0.5 * (a.flowGps + b.flowGps) * (b.timePct - a.timePct)
        }
        return max(0.0, durationS) * areaPerUnitP
    }

    /** Interpolated flow (g/s) at fraction [p] of the duration — for drawing the polyline. */
    fun flowAtPct(points: List<CurvePoint>, p: Double): Double {
        val knots = cleanKnots(points)
        val pp = p.coerceIn(0.0, 1.0)
        if (pp <= knots.first().timePct) return knots.first().flowGps
        if (pp >= knots.last().timePct) return knots.last().flowGps
        for (i in 0 until knots.size - 1) {
            val a = knots[i]
            val b = knots[i + 1]
            if (pp in a.timePct..b.timePct) {
                val span = b.timePct - a.timePct
                if (span <= 0.0) return a.flowGps
                return a.flowGps + (b.flowGps - a.flowGps) * ((pp - a.timePct) / span)
            }
        }
        return knots.last().flowGps
    }

    private const val EPS = 1e-6
}
