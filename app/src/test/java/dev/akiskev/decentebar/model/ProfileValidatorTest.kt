package dev.akiskev.decentebar.model

import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    @Test
    fun defaultProfileIsValid() {
        assertTrue(ProfileValidator.validate(DefaultProfiles.flow34).isEmpty())
    }

    @Test
    fun flowLimitedStageRequiresControlFields() {
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
        assertTrue(errors.any { it.contains("correction interval") })
    }
}
