package dev.akiskev.decentebar.model

import dev.akiskev.decentebar.storage.JsonCodec
import dev.akiskev.decentebar.storage.ProfileJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun rejectsStopStageType() {
        val profile = ShotProfile(
            name = "legacy stop",
            targetWeightG = 36.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Stop",
                    type = StageType.STOP
                )
            )
        )

        val errors = ProfileValidator.validate(profile)

        assertTrue(errors.any { it.contains("stop stages are no longer supported") })
    }

    @Test
    fun normalizerDropsStopStages() {
        val profile = ShotProfile(
            name = "legacy mixed",
            targetWeightG = 36.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Main",
                    type = StageType.FIXED_PRESSURE,
                    fixedPressureBar = 4.0,
                    exit = ExitCondition(weightGte = 36.0)
                ),
                ProfileStage(
                    name = "Stop",
                    type = StageType.STOP
                )
            )
        )

        val normalized = ProfileConstraints.normalize(profile)

        assertTrue(normalized.stages.none { it.type == StageType.STOP })
        assertTrue(normalized.stages.single().name == "Main")
    }

    @Test
    fun acceptsStageMaxTimeSumAboveHiddenProfileMax() {
        val profile = ShotProfile(
            name = "stage time caps",
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

        assertTrue(errors.isEmpty())
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
        assertEquals(ProfileConstraints.MAX_PROFILE_TIME_MS, normalized.maxShotTimeMs)
        assertTrue(normalized.stages.single().pressureCurve!!.maxWeightG <= normalized.targetWeightG)
        assertTrue(normalized.stages.single().exit.weightGte!! <= normalized.targetWeightG)
    }

    @Test
    fun maxShotTimeIsFixedAtMachineLimit() {
        val profile = ShotProfile(
            name = "too long",
            targetWeightG = 33.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 120_000L,
            stages = listOf(
                ProfileStage(
                    name = "Main",
                    type = StageType.FIXED_PRESSURE,
                    fixedPressureBar = 4.0,
                    exit = ExitCondition(stageTimeGteMs = 120_000L),
                    safety = StageSafety(maxStageTimeMs = 120_000L)
                )
            )
        )

        val normalized = ProfileConstraints.normalize(profile)

        assertEquals(ProfileConstraints.MAX_PROFILE_TIME_MS, normalized.maxShotTimeMs)
        assertEquals(ProfileConstraints.MAX_PROFILE_TIME_MS, normalized.stages.single().exit.stageTimeGteMs)
        assertEquals(ProfileConstraints.MAX_PROFILE_TIME_MS, normalized.stages.single().safety.maxStageTimeMs)

        val shortProfile = profile.copy(
            name = "too short",
            maxShotTimeMs = 10_000L,
            stages = profile.stages.map {
                it.copy(
                    exit = ExitCondition(stageTimeGteMs = 10_000L),
                    safety = StageSafety(maxStageTimeMs = 10_000L)
                )
            }
        )
        assertEquals(ProfileConstraints.MAX_PROFILE_TIME_MS, ProfileConstraints.normalize(shortProfile).maxShotTimeMs)
    }

    @Test
    fun profileJsonWithoutMaxShotTimeStillLoads() {
        val json = """
            {
              "schemaVersion": 1,
              "name": "legacy",
              "targetWeightG": 33.0,
              "stopOffsetG": 1.0,
              "stages": [
                {
                  "name": "Main",
                  "type": "FIXED_PRESSURE",
                  "fixedPressureBar": 4.0,
                  "exit": { "stageTimeGteMs": 10000 },
                  "safety": {}
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonCodec.json.decodeFromString<ShotProfile>(json)
        val normalized = ProfileConstraints.normalize(decoded)

        assertEquals(ProfileConstraints.MAX_PROFILE_TIME_MS, normalized.maxShotTimeMs)
        assertTrue(ProfileValidator.validate(normalized).isEmpty())
    }

    @Test
    fun compactProfileExportOmitsDefaultsAndInactiveFields() {
        val profile = ShotProfile(
            name = "compact",
            targetWeightG = 30.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 45_000L,
            stages = listOf(
                ProfileStage(
                    name = "Pre",
                    type = StageType.FIXED_PRESSURE,
                    fixedPressureBar = 4.0,
                    pressureCapBar = 9.0, // stale field from another stage type
                    exit = ExitCondition(
                        stageTimeGteMs = 10_000L,
                        manualSkip = true,
                        safetyTimeout = true
                    )
                ),
                ProfileStage(
                    name = "Main",
                    type = StageType.FLOW_LIMITED_PRESSURE,
                    pressureCapBar = 9.0,
                    targetFlowGps = 1.9,
                    flowDeadbandGps = 0.10000000149011612,
                    pressureStepBar = 0.2,
                    correctionIntervalMs = 200L,
                    pressureStepMultiplierMax = 8.0,
                    rampEndPressureBar = 5.0, // ignored by FLOW_LIMITED_PRESSURE
                    feedForward = FeedForwardConfig(),
                    exit = ExitCondition(weightGte = 30.0)
                )
            )
        )

        val exported = ProfileJsonCodec.encode(profile)

        assertTrue(exported.contains("\"schemaVersion\""))
        assertFalse(exported.contains("\"maxShotTimeMs\""))
        assertFalse(exported.contains("\"manualSkip\""))
        assertFalse(exported.contains("\"safetyTimeout\""))
        assertFalse(exported.contains("\"flowDeadbandGps\""))
        assertFalse(exported.contains("\"pressureStepMultiplierMax\""))
        assertFalse(exported.contains("\"rampEndPressureBar\""))
        assertTrue(exported.contains("\"pressureStepBar\""))
        assertTrue(exported.contains("\"correctionIntervalMs\""))
        assertTrue(exported.contains("\"feedForward\""))

        val normalized = ProfileConstraints.normalize(ProfileJsonCodec.decode(exported))

        assertTrue(ProfileValidator.validate(normalized).isEmpty())
        val flowStage = normalized.stages.single { it.type == StageType.FLOW_LIMITED_PRESSURE }
        assertTrue(flowStage.flowDeadbandGps == null)
        assertEquals(0.2, flowStage.pressureStepBar!!, 0.0)
        assertEquals(200L, flowStage.correctionIntervalMs)
        assertTrue(flowStage.feedForward != null)
    }

    @Test
    fun compactYieldTimeExportPrunesDefaultsAndKeepsDefaultFeedForwardOptIn() {
        val profile = ShotProfile(
            name = "yt compact",
            targetWeightG = 30.0,
            stopOffsetG = 1.0,
            stages = listOf(
                ProfileStage(
                    name = "Extraction",
                    type = StageType.YIELD_TIME_TRAJECTORY,
                    yieldTime = YieldTimeTrajectoryConfig(
                        curveType = FlowCurveType.FLAT,
                        startFlowGps = 9.0,
                        peakFlowGps = 8.0,
                        endFlowGps = 7.0,
                        customPoints = listOf(CurvePoint(0.0, 1.0), CurvePoint(1.0, 1.0)),
                        minExtractionPressureBar = 2.5,
                        feedForward = FeedForwardConfig()
                    ),
                    exit = ExitCondition(weightGte = 30.0),
                    safety = StageSafety(requireTwoConsecutiveFirstDropReadings = true)
                )
            )
        )

        val exported = ProfileJsonCodec.encode(profile)

        assertTrue(exported.contains("\"curveType\": \"FLAT\""))
        assertTrue(exported.contains("\"minExtractionPressureBar\""))
        assertTrue(exported.contains("\"feedForward\""))
        assertFalse(exported.contains("\"targetYieldG\""))
        assertFalse(exported.contains("\"targetDurationS\""))
        assertFalse(exported.contains("\"startFlowGps\""))
        assertFalse(exported.contains("\"peakFlowGps\""))
        assertFalse(exported.contains("\"endFlowGps\""))
        assertFalse(exported.contains("\"customPoints\""))
        assertFalse(exported.contains("\"correctionStrength\""))

        val decoded = ProfileJsonCodec.decode(exported)
        val yieldTime = decoded.stages.single().yieldTime!!

        assertEquals(FlowCurveType.FLAT, yieldTime.curveType)
        assertEquals(2.5, yieldTime.minExtractionPressureBar, 0.0)
        assertTrue(yieldTime.feedForward != null)
        assertTrue(ProfileValidator.validate(ProfileConstraints.normalize(decoded)).isEmpty())
    }

    @Test
    fun verboseLegacyProfileWithUnknownFieldsAndStopStillLoads() {
        val json = """
            {
              "schemaVersion": 1,
              "name": "legacy verbose",
              "targetWeightG": 33.0,
              "stopOffsetG": 1.0,
              "unknownRoot": true,
              "stages": [
                {
                  "name": "Main",
                  "type": "FIXED_PRESSURE",
                  "fixedPressureBar": 4.0,
                  "pressureCapBar": null,
                  "targetFlowGps": null,
                  "unknownStage": "ignored",
                  "exit": {
                    "mode": "ANY",
                    "stageTimeGteMs": 10000,
                    "manualSkip": false,
                    "safetyTimeout": false
                  },
                  "safety": {
                    "maxStageTimeMs": null,
                    "requireTwoConsecutiveFirstDropReadings": false
                  }
                },
                {
                  "name": "Stop",
                  "type": "STOP",
                  "exit": { "stageTimeGteMs": 1 }
                }
              ]
            }
        """.trimIndent()

        val normalized = ProfileConstraints.normalize(ProfileJsonCodec.decode(json))

        assertEquals(1, normalized.stages.size)
        assertEquals(StageType.FIXED_PRESSURE, normalized.stages.single().type)
        assertTrue(ProfileValidator.validate(normalized).isEmpty())
    }

    @Test
    fun validatorRejectsShotTimesAboveMachineLimit() {
        val profile = ShotProfile(
            name = "too long",
            targetWeightG = 33.0,
            stopOffsetG = 1.0,
            maxShotTimeMs = 61_000L,
            stages = listOf(
                ProfileStage(
                    name = "Main",
                    type = StageType.FIXED_PRESSURE,
                    fixedPressureBar = 4.0,
                    exit = ExitCondition(stageTimeGteMs = 61_000L),
                    safety = StageSafety(maxStageTimeMs = 61_000L)
                )
            )
        )

        val errors = ProfileValidator.validate(profile)

        assertTrue(errors.any { it.contains("Max shot time cannot exceed 60s") })
        assertTrue(errors.any { it.contains("exit time cannot exceed 60s") })
        assertTrue(errors.any { it.contains("stage max time cannot exceed 60s") })
    }
}
