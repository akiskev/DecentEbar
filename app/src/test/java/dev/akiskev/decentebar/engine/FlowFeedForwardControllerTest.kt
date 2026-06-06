package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.FeedForwardConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Contract tests for [FlowFeedForwardController] plus a small closed-loop A/B replay against a
 * reference of the legacy incremental-P law, using a lagged puck model that reproduces the
 * dead-time-driven limit cycle (docs/puck-resistance-feedforward.md §5, §10, §11).
 */
class FlowFeedForwardControllerTest {

    private val target = 1.85
    private val cap = 9.0

    // --- Contract / unit behaviour ------------------------------------------------------------

    @Test
    fun returnsNullUntilControlIntervalElapses() {
        val c = FlowFeedForwardController()
        val cfg = FeedForwardConfig()
        assertNotNull(c.tick(0, 1.0, target, 2.0, cap, cfg))
        assertNull("should hold within the control interval", c.tick(500, 1.0, target, 2.0, cap, cfg))
        assertNotNull(c.tick(800, 1.0, target, 2.0, cap, cfg))
    }

    @Test
    fun adaptsResistanceDownFast() {
        val c = FlowFeedForwardController()
        // seed 3.0 (cold start); observed R = 3/2 = 1.5 < seed -> fast (0.4) drop
        c.tick(0, 2.0, target, 3.0, cap, FeedForwardConfig())
        assertEquals(0.6 * 3.0 + 0.4 * 1.5, c.resistanceEstimate!!, 0.01)
    }

    @Test
    fun adaptsResistanceUpSlow() {
        val c = FlowFeedForwardController()
        // seed 1.0; observed R = 3/1 = 3 > seed -> slow (0.1) rise
        c.tick(0, 1.0, target, 3.0, cap, FeedForwardConfig(seedResistanceBarPerGps = 1.0))
        assertEquals(0.9 * 1.0 + 0.1 * 3.0, c.resistanceEstimate!!, 0.01)
    }

    @Test
    fun holdsLowDuringOverspeedThenFlagsTimeout() {
        val c = FlowFeedForwardController()
        val cfg = FeedForwardConfig() // recovery 0, hold timeout 4000
        val held = c.tick(0, 5.0, target, 6.0, cap, cfg) // flow >> target+band
        assertEquals(0.0, held!!, 0.0)
        assertEquals(FlowFeedForwardController.Mode.OVERSPEED_HOLD, c.lastMode)
        // still gushing well past the timeout -> still holds low, but now flagged
        val timedOut = c.tick(4200, 5.0, target, 0.0, cap, cfg)
        assertEquals(0.0, timedOut!!, 0.0)
        assertEquals(FlowFeedForwardController.Mode.OVERSPEED_TIMEOUT, c.lastMode)
    }

    @Test
    fun floorHoldsWhenUnderTargetButliftsWhenOverTarget() {
        // Under target on a normal puck (FF target pressure >> floor): the 2 bar floor is the
        // active lower bound even though the rise cap alone would land lower.
        val under = FlowFeedForwardController()
        val pUnder = under.tick(0, 1.0, target, 0.5, cap, FeedForwardConfig(seedResistanceBarPerGps = 3.0))
        assertEquals(2.0, pUnder!!, 0.001)

        // Over target (but below the overspeed band): floor lifts to 0 so the loop can back off.
        val over = FlowFeedForwardController()
        val pOver = over.tick(0, 2.0, target, 1.0, cap, FeedForwardConfig(seedResistanceBarPerGps = 0.5))
        assertTrue("floor should lift below 2 bar when over target, was $pOver", pOver!! < 2.0)
    }

    @Test
    fun capsRisesButNotDrops() {
        // Rise capped to maxRisePerTick (0.8) above the current pressure.
        val rising = FlowFeedForwardController()
        val pRise = rising.tick(0, 1.0, target, 4.0, cap, FeedForwardConfig(seedResistanceBarPerGps = 12.0))
        assertEquals(4.8, pRise!!, 0.001)

        // A drop (here via overspeed) is not slew-limited: 8 -> 0 in one tick.
        val dropping = FlowFeedForwardController()
        val pDrop = dropping.tick(0, 5.0, target, 8.0, cap, FeedForwardConfig())
        assertEquals(0.0, pDrop!!, 0.0)
    }

    // --- Closed-loop A/B replay ---------------------------------------------------------------
    //
    // A first-order-lag puck (the dead time) with a *steep* flow curve Q = (P/R0)^n. The steep
    // dQ/dP is what makes the legacy fixed-step chaser overshoot and limit-cycle near target;
    // a stable operating point still exists, so a model-based law can settle. This is a crude
    // model (the §11 caveat), good enough to guard the controller against the failure modes.

    @Test
    fun newLawIsFarSteadierThanLegacyOnASteepPuck() {
        // An (exaggerated) steep puck Q=(P/R0)^3: one legacy step moves flow more than its
        // deadband, so it limit-cycles. A single-secant model can't perfectly hold such an
        // extreme curve at target, but it must avoid the violent cycle — i.e. be far steadier.
        val ff = simulateFeedForward(startR0 = 3.0, exponent = 3.0)
        val legacy = simulateIncrementalP(startR0 = 3.0, exponent = 3.0)

        assertTrue(
            "new flow stdev ${ff.flowStdevLastQuarter} should be well under legacy ${legacy.flowStdevLastQuarter}",
            ff.flowStdevLastQuarter < 0.5 * legacy.flowStdevLastQuarter
        )
    }

    @Test
    fun newLawRecoversFromAMidShotChannel() {
        // Resistance drops sharply mid-shot (a channel forming) — the 09:38 failure mode.
        val ff = simulateFeedForward(startR0 = 3.0, exponent = 1.0, channelAtMs = 10_000, channelR0 = 1.0)
        assertTrue(
            "new should re-settle near target after the channel, mean=${ff.flowMeanLastQuarter}",
            abs(ff.flowMeanLastQuarter - target) < 0.4
        )
        assertTrue(
            "new should not sustain a limit cycle, flow stdev=${ff.flowStdevLastQuarter}",
            ff.flowStdevLastQuarter < 0.3
        )
    }

    @Test
    fun newLawTamesAGusherFromAnAlreadyLoosePuck() {
        // Loose puck from the start — flow wants to run away immediately.
        val ff = simulateFeedForward(startR0 = 1.2, exponent = 1.0)
        assertTrue(
            "new should not sustain a limit cycle, flow stdev=${ff.flowStdevLastQuarter}",
            ff.flowStdevLastQuarter < 0.4
        )
        assertTrue(
            "new should settle near target, mean=${ff.flowMeanLastQuarter}",
            abs(ff.flowMeanLastQuarter - target) < 0.5
        )
    }

    // --- Simulation harness ------------------------------------------------------------------

    /** First-order-lag puck with a (possibly steep) flow curve: steady flow = (P / R0)^n. */
    private class LaggedPuck(var r0: Double, private val exponent: Double, private val tauS: Double) {
        var flow = 0.0
            private set

        fun advance(pressureBar: Double, dtS: Double): Double {
            val steady = if (pressureBar <= 0.0) 0.0 else Math.pow(pressureBar / r0, exponent)
            flow += (1.0 - exp(-dtS / tauS)) * (steady - flow)
            return flow
        }
    }

    private class Trace(val flows: List<Double>, val pressures: List<Double>) {
        private fun lastQuarter(xs: List<Double>) = xs.takeLast(xs.size / 4).ifEmpty { xs }
        val flowMeanLastQuarter get() = lastQuarter(flows).average()
        val flowStdevLastQuarter get() = lastQuarter(flows).let { q ->
            val m = q.average(); sqrt(q.sumOf { (it - m) * (it - m) } / q.size)
        }
    }

    private fun simulateFeedForward(
        startR0: Double,
        exponent: Double,
        channelAtMs: Long? = null,
        channelR0: Double? = null
    ): Trace {
        val puck = LaggedPuck(startR0, exponent, tauS = 1.0)
        val c = FlowFeedForwardController()
        val cfg = FeedForwardConfig()
        var pressure = 0.0
        val flows = ArrayList<Double>()
        val pressures = ArrayList<Double>()
        var t = 0L
        while (t <= 25_000) {
            if (channelAtMs != null && t >= channelAtMs) puck.r0 = channelR0!!
            val flow = puck.advance(pressure, 0.1)
            c.tick(t, flow, target, pressure, cap, cfg)?.let { pressure = it }
            flows.add(flow); pressures.add(pressure)
            t += 100
        }
        return Trace(flows, pressures)
    }

    /** Reference re-implementation of the legacy incremental-P law for A/B comparison only. */
    private fun simulateIncrementalP(
        startR0: Double,
        exponent: Double,
        channelAtMs: Long? = null,
        channelR0: Double? = null
    ): Trace {
        val puck = LaggedPuck(startR0, exponent, tauS = 1.0)
        val step = 0.2; val deadband = 0.1; val maxMult = 8.0; val interval = 250L
        var pressure = 0.0
        var lastCorrection: Long? = null
        var lastFlow: Double? = null
        val flows = ArrayList<Double>()
        val pressures = ArrayList<Double>()
        var t = 0L
        while (t <= 25_000) {
            if (channelAtMs != null && t >= channelAtMs) puck.r0 = channelR0!!
            val flow = puck.advance(pressure, 0.1)
            if (lastCorrection == null || t - lastCorrection >= interval) {
                val error = flow - target
                val moving = when {
                    error > deadband -> lastFlow != null && flow < lastFlow!!
                    error < -deadband -> lastFlow != null && flow > lastFlow!!
                    else -> false
                }
                val rawMult = if (abs(error) > deadband) (abs(error) / deadband).coerceAtMost(maxMult) else 0.0
                val scaled = step * (if (moving) rawMult * 0.3 else rawMult)
                pressure = when {
                    flow > target + deadband -> pressure - scaled
                    flow < target - deadband -> pressure + scaled
                    else -> pressure
                }.coerceIn(0.0, cap)
                lastCorrection = t; lastFlow = flow
            }
            flows.add(flow); pressures.add(pressure)
            t += 100
        }
        return Trace(flows, pressures)
    }
}
