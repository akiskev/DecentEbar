package dev.akiskev.decentebar.model

import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    @Test
    fun fallbackProfileIsValid() {
        assertTrue(ProfileValidator.validate(DefaultProfiles.fallbackProfile).isEmpty())
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
    fun builtInProfilesAreValid() {
        DefaultProfiles.builtIns.forEach { profile ->
            assertTrue(
                "built-in '${profile.name}' should be valid: ${ProfileValidator.validate(profile)}",
                ProfileValidator.validate(profile).isEmpty()
            )
        }
    }

    @Test
    fun leverSimProfileIsTheOnlyBuiltInDefault() {
        assertTrue(DefaultProfiles.builtIns.single().name == "Lever sim 17g in 39g out")
    }

    @Test
    fun yieldTimeStageRequiresConfig() {
        val profile = ShotProfile(
            name = "missing yt",
            targetWeightG = 30.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Extraction",
                    type = StageType.YIELD_TIME_TRAJECTORY,
                    exit = ExitCondition(weightGte = 30.0)
                )
            )
        )

        assertTrue(ProfileValidator.validate(profile).any { it.contains("yield/time config") })
    }

    @Test
    fun yieldTimeStageRejectsBadEnvelope() {
        val profile = ShotProfile(
            name = "bad yt",
            targetWeightG = 30.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Extraction",
                    type = StageType.YIELD_TIME_TRAJECTORY,
                    yieldTime = YieldTimeTrajectoryConfig(
                        targetYieldG = 0.0,
                        minFlowGps = 2.0,
                        maxFlowGps = 1.0,
                        correctionStrength = 1.5,
                        maxPressureBar = 8.0,
                        minExtractionPressureBar = 9.0
                    ),
                    exit = ExitCondition(stageTimeGteMs = 30_000L)
                )
            )
        )

        val errors = ProfileValidator.validate(profile)
        assertTrue(errors.any { it.contains("target yield") })
        assertTrue(errors.any { it.contains("min flow cannot exceed max flow") })
        assertTrue(errors.any { it.contains("correction strength") })
        assertTrue(errors.any { it.contains("extraction floor cannot exceed max pressure") })
    }

    @Test
    fun pressureCurveStageIsValidWithTwoPoints() {
        val profile = ShotProfile(
            name = "pc ok",
            targetWeightG = 36.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Pressure",
                    type = StageType.PRESSURE_CURVE,
                    pressureCurve = PressureCurveConfig(
                        axis = PressureCurveAxis.WEIGHT,
                        points = listOf(
                            PressureCurvePoint(0.0, 9.0),
                            PressureCurvePoint(1.0, 6.0)
                        ),
                        maxWeightG = 36.0,
                        maxPressureBar = 9.0
                    ),
                    exit = ExitCondition(weightGte = 36.0)
                )
            )
        )

        assertTrue(ProfileValidator.validate(profile).isEmpty())
    }

    @Test
    fun pressureCurveStageRejectsBadConfig() {
        val profile = ShotProfile(
            name = "pc bad",
            targetWeightG = 36.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Pressure",
                    type = StageType.PRESSURE_CURVE,
                    pressureCurve = PressureCurveConfig(
                        axis = PressureCurveAxis.TIME,
                        points = listOf(PressureCurvePoint(0.0, 5.0)), // only 1 point
                        durationS = 0.0,                                // invalid for TIME axis
                        minPressureBar = 10.0,                          // exceeds max
                        maxPressureBar = 9.0
                    ),
                    exit = ExitCondition(stageTimeGteMs = 30_000L)
                )
            )
        )

        val errors = ProfileValidator.validate(profile)
        assertTrue(errors.any { it.contains("at least 2 points") })
        assertTrue(errors.any { it.contains("duration must be greater than 0") })
        assertTrue(errors.any { it.contains("min pressure cannot exceed max pressure") })
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

    @Test
    fun rejectsStopOffsetAtOrAboveTargetWeight() {
        val profile = DefaultProfiles.fallbackProfile.copy(targetWeightG = 5.0, stopOffsetG = 5.0)

        val errors = ProfileValidator.validate(profile)

        assertTrue(errors.any { it.contains("Stop offset") })
    }

    @Test
    fun rejectsProfileMaxShotTimeLowerThanStageMaxTimes() {
        val profile = ShotProfile(
            name = "bad time cap",
            targetWeightG = 36.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 20_000L,
            stages = listOf(
                ProfileStage(
                    name = "A",
                    type = StageType.FIXED_PRESSURE,
                    fixedPressureBar = 2.0,
                    exit = ExitCondition(stageTimeGteMs = 10_000L),
                    safety = StageSafety(maxStageTimeMs = 15_000L)
                ),
                ProfileStage(
                    name = "B",
                    type = StageType.FIXED_PRESSURE,
                    fixedPressureBar = 4.0,
                    exit = ExitCondition(stageTimeGteMs = 10_000L),
                    safety = StageSafety(maxStageTimeMs = 15_000L)
                )
            )
        )

        val errors = ProfileValidator.validate(profile)

        assertTrue(errors.any { it.contains("sum of stage max times") })
    }

    @Test
    fun rejectsStageWeightsAboveProfileTarget() {
        val profile = ShotProfile(
            name = "bad weights",
            targetWeightG = 33.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Curve",
                    type = StageType.PRESSURE_CURVE,
                    pressureCurve = PressureCurveConfig(
                        axis = PressureCurveAxis.WEIGHT,
                        points = listOf(PressureCurvePoint(0.0, 6.0), PressureCurvePoint(1.0, 4.0)),
                        maxWeightG = 40.0,
                        maxPressureBar = 8.0
                    ),
                    exit = ExitCondition(weightGte = 40.0)
                )
            )
        )

        val errors = ProfileValidator.validate(profile)

        assertTrue(errors.any { it.contains("max weight cannot exceed") })
        assertTrue(errors.any { it.contains("exit weight cannot exceed") })
    }

    @Test
    fun rejectsYieldTimeSumAboveProfileTarget() {
        val profile = ShotProfile(
            name = "too much yield",
            targetWeightG = 33.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 60_000L,
            stages = listOf(
                ProfileStage(
                    name = "A",
                    type = StageType.YIELD_TIME_TRAJECTORY,
                    yieldTime = YieldTimeTrajectoryConfig(targetYieldG = 20.0),
                    exit = ExitCondition(weightGte = 20.0)
                ),
                ProfileStage(
                    name = "B",
                    type = StageType.YIELD_TIME_TRAJECTORY,
                    yieldTime = YieldTimeTrajectoryConfig(targetYieldG = 20.0),
                    exit = ExitCondition(weightGte = 33.0)
                )
            )
        )

        val errors = ProfileValidator.validate(profile)

        assertTrue(errors.any { it.contains("Sum of yield/time") })
    }

    @Test
    fun rejectsWeightRampEndingBeforeStart() {
        val profile = ShotProfile(
            name = "bad ramp",
            targetWeightG = 33.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Ramp",
                    type = StageType.WEIGHT_BASED_PRESSURE_RAMP,
                    rampStartPressureBar = 2.0,
                    rampEndPressureBar = 6.0,
                    rampStartWeightG = 20.0,
                    rampEndWeightG = 10.0,
                    exit = ExitCondition(weightGte = 33.0)
                )
            )
        )

        val errors = ProfileValidator.validate(profile)

        assertTrue(errors.any { it.contains("end weight must be at least start weight") })
    }

    @Test
    fun normalizerClampsProfileWeightAndTimeConstraints() {
        val profile = ShotProfile(
            name = "normalize",
            targetWeightG = 33.0,
            stopOffsetG = 40.0,
            maxShotTimeMs = 10_000L,
            stages = listOf(
                ProfileStage(
                    name = "Curve",
                    type = StageType.PRESSURE_CURVE,
                    pressureCurve = PressureCurveConfig(
                        axis = PressureCurveAxis.WEIGHT,
                        points = listOf(PressureCurvePoint(0.0, 10.0), PressureCurvePoint(1.0, 4.0)),
                        maxWeightG = 60.0,
                        maxPressureBar = 8.0
                    ),
                    exit = ExitCondition(weightGte = 60.0),
                    safety = StageSafety(maxStageTimeMs = 45_000L)
                )
            )
        )

        val normalized = ProfileConstraints.normalize(profile)

        assertTrue(normalized.stopOffsetG < normalized.targetWeightG)
        assertTrue(normalized.maxShotTimeMs >= 45_000L)
        assertTrue(normalized.stages.single().pressureCurve!!.maxWeightG <= normalized.targetWeightG)
        assertTrue(normalized.stages.single().exit.weightGte!! <= normalized.targetWeightG)
    }
}
