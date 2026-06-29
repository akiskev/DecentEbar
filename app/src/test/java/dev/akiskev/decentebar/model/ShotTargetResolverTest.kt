package dev.akiskev.decentebar.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShotTargetResolverTest {
    @Test
    fun yieldTimeStageWinsOverProfileAndUserTargets() {
        val profile = ShotProfile(
            name = "Yield time",
            targetWeightG = 42.0,
            stopOffsetG = 1.0,
            stages = listOf(
                ProfileStage(
                    name = "Main",
                    type = StageType.YIELD_TIME_TRAJECTORY,
                    yieldTime = YieldTimeTrajectoryConfig(targetYieldG = 36.0, targetDurationS = 28.0)
                )
            )
        )

        val targets = ShotTargetResolver.resolve(profile, userTargetYieldG = 30.0, userTargetTimeS = 35.0)

        assertEquals(36.0, targets.targetYieldG!!, 0.0001)
        assertEquals(28.0, targets.targetTimeS!!, 0.0001)
    }

    @Test
    fun profileYieldWinsAndUserTimeFillsMissingTime() {
        val profile = ShotProfile(
            name = "Pressure curve",
            targetWeightG = 40.0,
            stopOffsetG = 1.0,
            stages = listOf(
                ProfileStage(
                    name = "Main",
                    type = StageType.PRESSURE_CURVE,
                    pressureCurve = PressureCurveConfig()
                )
            )
        )

        val targets = ShotTargetResolver.resolve(profile, userTargetYieldG = 32.0, userTargetTimeS = 29.0)

        assertEquals(40.0, targets.targetYieldG!!, 0.0001)
        assertEquals(29.0, targets.targetTimeS!!, 0.0001)
    }

    @Test
    fun unavailableTimeStaysUnavailable() {
        val profile = ShotProfile(
            name = "No time",
            targetWeightG = 38.0,
            stopOffsetG = 1.0,
            stages = listOf(ProfileStage(name = "Main", type = StageType.FIXED_PRESSURE, fixedPressureBar = 8.0))
        )

        val targets = ShotTargetResolver.resolve(profile)

        assertEquals(38.0, targets.targetYieldG!!, 0.0001)
        assertNull(targets.targetTimeS)
    }
}
