package dev.akiskev.decentebar.model

import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    @Test
    fun defaultProfileIsValid() {
        assertTrue(ProfileValidator.validate(DefaultProfiles.flow33Dark).isEmpty())
    }

    @Test
    fun flowLimitedStageRequiresCapAndTargetFlow() {
        val profile = ShotProfile(
            name = "bad flow",
            targetWeightG = 36.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Main",
                    type = StageType.FLOW_LIMITED_PRESSURE,
                    exit = ExitCondition(weightGte = 30.0)
                )
            )
        )

        val errors = ProfileValidator.validate(profile)

        assertTrue(errors.any { it.contains("pressure cap") })
        assertTrue(errors.any { it.contains("target flow") })
    }

    @Test
    fun flowLimitedStageAllowsAutoTunedFields() {
        // Deadband, pressure step and correction interval are optional (auto-tuned when null).
        val profile = ShotProfile(
            name = "auto flow",
            targetWeightG = 36.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Main",
                    type = StageType.FLOW_LIMITED_PRESSURE,
                    pressureCapBar = 9.0,
                    targetFlowGps = 1.9,
                    exit = ExitCondition(weightGte = 30.0)
                )
            )
        )

        assertTrue(ProfileValidator.validate(profile).isEmpty())
    }
}
