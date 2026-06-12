package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.FlowCurveType
import dev.akiskev.decentebar.model.TastePriorityMode
import dev.akiskev.decentebar.model.YieldTimeTrajectoryConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Output-based yield/time trajectory planner (docs/yield-time-trajectory.md). Pure JVM — no
 * Android dependencies — so it can be unit-tested and replayed offline, like
 * [FlowFeedForwardController].
 *
 * It turns a high-level intent ("30 g out in 30 s on a sweet declining curve") into a live
 * **target flow**: it builds a normalized flow-shape curve whose integral equals the target
 * yield, then each tick blends the planned curve flow with a catch-up flow so the shot still
 * lands on the yield/time target — while ramping the correction down near the end so it never
 * makes a violent late-shot rescue. It deliberately knows nothing about pressure: the caller
 * feeds [PlannerTick.correctedTargetFlowGps] to the existing flow→pressure controller, which
 * keeps all the usual pressure-safety limits.
 *
 * Not thread-safe: call from a single thread (the ShotController's main-thread confinement).
 * [configure] once per stage entry; [evaluate] is pure and may be called repeatedly per tick.
 */
class YieldTimeTrajectoryPlanner {

    /** Result of one [evaluate]; all flows in g/s, weights in g (stage-relative). */
    data class PlannerTick(
        val plannedFlowGps: Double,
        val plannedStageWeightG: Double,
        val correctedTargetFlowGps: Double,
        val weightErrorG: Double,
        val progressPct: Double,
        val mode: String
    )

    private var config: YieldTimeTrajectoryConfig? = null
    private var durationS: Double = 1.0
    private var targetYieldG: Double = 0.0
    // Normalized piecewise-linear curve over p = t/duration. ps strictly increasing 0..1;
    // flows already scaled so duration * ∫flow dp == targetYieldG.
    private var ps: DoubleArray = doubleArrayOf(0.0, 1.0)
    private var flows: DoubleArray = doubleArrayOf(0.0, 0.0)

    fun reset() {
        config = null
        durationS = 1.0
        targetYieldG = 0.0
        ps = doubleArrayOf(0.0, 1.0)
        flows = doubleArrayOf(0.0, 0.0)
    }

    /** Build (and normalize) the flow curve for [cfg]. Cheap; call on stage entry. */
    fun configure(cfg: YieldTimeTrajectoryConfig) {
        config = cfg
        durationS = max(MIN_DURATION_S, cfg.targetDurationS)
        targetYieldG = max(0.0, cfg.targetYieldG)

        val knots = rawKnots(cfg)
        val rawPs = knots.map { it.first }.toDoubleArray()
        val rawFlows = knots.map { it.second }.toDoubleArray()

        // Normalize so the curve integrates to the target yield. rawArea is the weight the raw
        // shape would deposit over the duration; scale every knot flow by targetYield / rawArea.
        val rawAreaPerUnitP = trapezoid(rawPs, rawFlows, 1.0)
        val rawArea = durationS * rawAreaPerUnitP
        val factor = if (rawArea > AREA_EPS && targetYieldG > 0.0) targetYieldG / rawArea else 0.0

        ps = rawPs
        flows = if (factor > 0.0) {
            DoubleArray(rawFlows.size) { rawFlows[it] * factor }
        } else {
            // Degenerate shape (all-zero or zero yield): fall back to a flat curve at the mean.
            val flat = targetYieldG / durationS
            DoubleArray(rawFlows.size) { flat }
        }
    }

    /** Planned flow (g/s) at stage time [tS]. */
    fun plannedFlowAt(tS: Double): Double {
        val p = (tS / durationS).coerceIn(0.0, 1.0)
        return flowAtP(p)
    }

    /** Planned cumulative stage weight (g) deposited by stage time [tS]. */
    fun plannedWeightAt(tS: Double): Double {
        val p = (tS / durationS).coerceIn(0.0, 1.0)
        return durationS * trapezoid(ps, flows, p)
    }

    /**
     * Pure: given the stage-elapsed time and the yield gained *since the stage began*, compute
     * the corrected target flow plus telemetry. Returns sensible defaults if [configure] was
     * never called.
     */
    fun evaluate(stageElapsedMs: Long, yieldGainedG: Double): PlannerTick {
        val cfg = config ?: return PlannerTick(0.0, 0.0, 0.0, 0.0, 0.0, "PLANNED")
        val elapsedS = stageElapsedMs / 1000.0

        val plannedFlow = plannedFlowAt(elapsedS)
        val plannedWeight = plannedWeightAt(elapsedS)
        val weightError = plannedWeight - yieldGainedG
        val progress = (elapsedS / durationS).coerceIn(0.0, 1.0)

        val remainingWeight = max(0.0, targetYieldG - yieldGainedG)
        val remainingTime = max(REMAINING_TIME_FLOOR_S, durationS - elapsedS)
        val catchupFlow = remainingWeight / remainingTime

        // Ramp correction down inside the late window so the end of the shot can't be rescued
        // with a violent flow spike. STRICT_TARGET keeps a floor so it still chases; TASTE_SAFE
        // additionally hard-caps any late increase.
        val lateLimit = cfg.lateShotCorrectionLimitS
        val inLateWindow = lateLimit > 0.0 && remainingTime <= lateLimit
        val baseLate = if (inLateWindow) (remainingTime / lateLimit) else 1.0
        val effLate = when (cfg.tastePriorityMode) {
            TastePriorityMode.STRICT_TARGET -> max(baseLate, STRICT_LATE_FLOOR)
            else -> baseLate
        }
        val effStrength = (cfg.correctionStrength * effLate).coerceIn(0.0, 1.0)

        var corrected = plannedFlow + effStrength * (catchupFlow - plannedFlow)

        // Classify before clamping so the mode reflects the controller's intent.
        var mode = when {
            corrected > plannedFlow + MODE_EPS -> "CATCHUP"
            corrected < plannedFlow - MODE_EPS -> "EASING"
            else -> "PLANNED"
        }
        if (inLateWindow && effLate < 1.0 && abs(catchupFlow - plannedFlow) > MODE_EPS) {
            mode = "LATE_LIMIT"
        }
        if (cfg.tastePriorityMode == TastePriorityMode.TASTE_SAFE && inLateWindow && corrected > plannedFlow) {
            val cap = plannedFlow * (1.0 + TASTE_LATE_MAX_INCREASE)
            if (corrected > cap) {
                corrected = cap
                mode = "TASTE_CAP"
            }
        }

        corrected = corrected.coerceIn(min(cfg.minFlowGps, cfg.maxFlowGps), cfg.maxFlowGps)

        return PlannerTick(
            plannedFlowGps = plannedFlow,
            plannedStageWeightG = plannedWeight,
            correctedTargetFlowGps = corrected,
            weightErrorG = weightError,
            progressPct = progress,
            mode = mode
        )
    }

    /**
     * Raw (un-normalized) knots (p, flow) for the chosen shape. p strictly increasing with
     * endpoints clamped to 0 and 1; absolute flow values only matter as a shape — [configure]
     * scales them to hit the target yield.
     */
    private fun rawKnots(cfg: YieldTimeTrajectoryConfig): List<Pair<Double, Double>> {
        val start = max(0.0, cfg.startFlowGps)
        val peak = max(0.0, cfg.peakFlowGps)
        val end = max(0.0, cfg.endFlowGps)
        val peakAt = cfg.peakAtPct.coerceIn(0.05, 0.95)
        return when (cfg.curveType) {
            FlowCurveType.FLAT -> listOf(0.0 to 1.0, 1.0 to 1.0)
            // DECLINING must actually decline no matter how the shared shape hints are set (the
            // defaults have start < end, and other presets tune the same hints): run from the
            // higher of the two hints down to the lower.
            FlowCurveType.DECLINING -> listOf(0.0 to max(start, end), 1.0 to min(start, end))
            FlowCurveType.RAMP_THEN_DECLINE -> listOf(0.0 to start, peakAt to peak, 1.0 to end)
            FlowCurveType.BLOOMING_DECLINE -> {
                // A low flat bloom, then ramp to peak, then a gentle decline.
                val bloomEnd = min(BLOOM_FRACTION, peakAt - 0.01).coerceIn(0.0, 0.9)
                listOf(0.0 to start, bloomEnd to start, peakAt to peak, 1.0 to end)
            }
            // Shared with the graphical editor so the drawn weight == the planner's yield (§CurveMath).
            FlowCurveType.CUSTOM_POINTS -> CurveMath.cleanKnots(cfg.customPoints).map { it.timePct to it.flowGps }
        }
    }

    private fun flowAtP(p: Double): Double {
        if (p <= ps.first()) return flows.first()
        if (p >= ps.last()) return flows.last()
        for (i in 0 until ps.size - 1) {
            val p0 = ps[i]
            val p1 = ps[i + 1]
            if (p in p0..p1) {
                val span = p1 - p0
                if (span <= 0.0) return flows[i]
                return flows[i] + (flows[i + 1] - flows[i]) * ((p - p0) / span)
            }
        }
        return flows.last()
    }

    /** ∫₀^[upToP] of the piecewise-linear curve (ps, flows), with a partial final segment. */
    private fun trapezoid(ps: DoubleArray, flows: DoubleArray, upToP: Double): Double {
        if (ps.size < 2) return 0.0
        var area = 0.0
        for (i in 0 until ps.size - 1) {
            val p0 = ps[i]
            val p1 = ps[i + 1]
            if (upToP <= p0) break
            val segEnd = min(upToP, p1)
            val span = p1 - p0
            if (segEnd > p0 && span > 0.0) {
                val fEnd = flows[i] + (flows[i + 1] - flows[i]) * ((segEnd - p0) / span)
                area += 0.5 * (flows[i] + fEnd) * (segEnd - p0)
            }
            if (upToP <= p1) break
        }
        return area
    }

    companion object {
        private const val MIN_DURATION_S = 0.5
        private const val AREA_EPS = 1e-6
        private const val MODE_EPS = 0.02
        // Never divide catch-up by a vanishing remaining time on overrun.
        private const val REMAINING_TIME_FLOOR_S = 0.5
        // STRICT_TARGET keeps at least this much correction even at the very end.
        private const val STRICT_LATE_FLOOR = 0.5
        // TASTE_SAFE: inside the late window, an increase may not exceed this fraction over the
        // planned flow ("do not increase target flow by more than 20–30% during the final 5 s").
        private const val TASTE_LATE_MAX_INCREASE = 0.25
        // BLOOMING_DECLINE: fraction of the shot spent in the low flat bloom before the ramp.
        private const val BLOOM_FRACTION = 0.20
    }
}
