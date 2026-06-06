package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.FeedForwardConfig
import kotlin.math.abs
import kotlin.math.min

/**
 * Resistance feed-forward controller for flow-limited stages
 * (docs/puck-resistance-feedforward.md §5). Pure JVM — no Android dependencies — so it can
 * be unit-tested and replayed offline.
 *
 * It models the puck as a resistance `R = P / Q` (bar per g/s), commands
 * `P = targetFlow * R_est` plus a small bounded feedback trim, and:
 *  - learns `R` only from settled samples (§5.2),
 *  - adapts `R` fast on a drop / slow on a rise (§5.3),
 *  - holds pressure low during an overspeed gush, flagging it after a timeout (§5.7),
 *  - keeps a floor in normal control but lifts it toward 0 when flow is over target (§5.8),
 *  - caps how fast pressure may rise while leaving drops unrestricted.
 *
 * Not thread-safe: call from a single thread (the ShotController's main-thread confinement).
 */
class FlowFeedForwardController {
    /** Current online resistance estimate (bar per g/s), or null before the first tick. */
    var resistanceEstimate: Double? = null
        private set

    /** What the controller did on the last tick — for diagnostics/logging. */
    var lastMode: Mode = Mode.SEEKING
        private set

    private var lastTickMs: Long? = null
    private var lastFlowGps: Double? = null
    private var overspeedHoldStartMs: Long? = null
    // The pressure that was actively pushing the puck during normal control. Held across an
    // overspeed hold (when the live command has been cut to ~0) so the gush can still teach us R.
    private var lastTrackingPressureBar: Double? = null

    enum class Mode { SEEKING, TRACKING, OVERSPEED_HOLD, OVERSPEED_TIMEOUT }

    fun reset() {
        resistanceEstimate = null
        lastTickMs = null
        lastFlowGps = null
        overspeedHoldStartMs = null
        lastTrackingPressureBar = null
        lastMode = Mode.SEEKING
    }

    /**
     * Compute the next pressure to command (bar), or null if the control interval has not
     * elapsed since the last tick (the caller should then hold the current command).
     */
    fun tick(
        nowMs: Long,
        flowGps: Double,
        targetFlowGps: Double,
        currentPressureBar: Double,
        capBar: Double,
        config: FeedForwardConfig
    ): Double? {
        val last = lastTickMs
        if (last != null && nowMs - last < config.controlIntervalMs) return null
        val dtS = if (last == null) 0.0 else (nowMs - last) / 1000.0
        val slopeGpsPerS = if (last != null && dtS > 0.0 && lastFlowGps != null) {
            (flowGps - lastFlowGps!!) / dtS
        } else {
            0.0
        }

        // Seed the model on the first tick. Phase 1 is online-only, so use the configured seed
        // (or cold-start default) rather than the noisy entry P/Q — pressure is ~0 coming out of
        // Wait. Online adaptation corrects it within a few ticks.
        if (resistanceEstimate == null) {
            resistanceEstimate = (config.seedResistanceBarPerGps ?: config.coldStartResistanceBarPerGps)
                .coerceIn(config.minResistanceBarPerGps, config.maxResistanceBarPerGps)
        }
        var r = resistanceEstimate!!

        val overspeed = flowGps > targetFlowGps + config.overspeedBandGps
        if (!overspeed) lastTrackingPressureBar = currentPressureBar

        // Learn R from `observedR = pushingPressure / flow` (§5.2/§5.3). Crucially we learn even
        // during a gush: the live command has been cut to recovery, but the puck is still flowing
        // in response to the pressure that *was* pushing it, so `lastTrackingPressureBar / flow`
        // is a valid (low) resistance reading — this is what lets the model adapt down and break
        // the limit cycle instead of being locked out by the flow-slope gate. A resistance DROP
        // (channel risk) is trusted fast and regardless of slope; a RISE is taken slowly and only
        // from settled samples, so the dead-time lag right after a pressure rise can't inflate R.
        val pushingPressure = if (overspeed) (lastTrackingPressureBar ?: currentPressureBar) else currentPressureBar
        if (flowGps > FLOW_FLOOR_GPS && pushingPressure > PRESSURE_FLOOR_BAR) {
            val observedR = pushingPressure / flowGps
            val loosening = observedR < r
            if (loosening || abs(slopeGpsPerS) <= config.stableMaxFlowSlopeGpsPerS) {
                val rate = if (loosening) config.adaptDownRate else config.adaptUpRate
                r = ((1.0 - rate) * r + rate * observedR)
                    .coerceIn(config.minResistanceBarPerGps, config.maxResistanceBarPerGps)
                resistanceEstimate = r
            }
        }

        val proposed: Double
        if (overspeed) {
            // §5.7: arrest the gush by holding low. The timeout only changes the reported mode
            // (so the caller can flag the puck compromised) — holding low stays the safe action,
            // because if the puck over-flows even at recovery pressure, adding pressure is worse.
            val holdStart = overspeedHoldStartMs ?: nowMs.also { overspeedHoldStartMs = it }
            lastMode = if (nowMs - holdStart >= config.overspeedHoldTimeoutMs) {
                Mode.OVERSPEED_TIMEOUT
            } else {
                Mode.OVERSPEED_HOLD
            }
            proposed = config.recoveryPressureBar
        } else {
            overspeedHoldStartMs = null
            lastMode = Mode.TRACKING
            val predictedFlow = flowGps + slopeGpsPerS * config.predictHorizonS
            val trim = config.trimGainBarPerGps * (targetFlowGps - predictedFlow)
            proposed = targetFlowGps * r + trim
        }

        // Cap rises (drops are unrestricted) — the asymmetry that stops re-triggering breakthroughs.
        var result = proposed
        if (result > currentPressureBar + config.maxRisePerTickBar) {
            result = currentPressureBar + config.maxRisePerTickBar
        }
        // §5.8 conditional floor: keep a floor in normal control so the loop never sits at ~0 and
        // jitters; lift it to 0 when flow is over target so a gush can actually be arrested. The
        // floor is applied last, so it wins over the rise cap on the way up (a small jump to the
        // floor from ~0 is safe; the rise cap then governs everything above the floor). It is also
        // capped at the feed-forward target pressure (targetFlow * R) so a very loose puck — where
        // target needs less than the nominal floor — is never pushed past target by the floor.
        val floor = if (flowGps > targetFlowGps) {
            0.0
        } else {
            min(min(config.pressureFloorBar, targetFlowGps * r), capBar)
        }
        result = result.coerceIn(floor, capBar)

        lastTickMs = nowMs
        lastFlowGps = flowGps
        return result
    }

    companion object {
        private const val FLOW_FLOOR_GPS = 0.3
        private const val PRESSURE_FLOOR_BAR = 0.3
    }
}
