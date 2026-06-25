package dev.akiskev.decentebar.model

object DefaultProfiles {
    val leverSim17gIn39gOut = ShotProfile(
        name = "Lever sim 17g in 39g out",
        targetWeightG = 39.0,
        stopOffsetG = 0.9,
        maxShotTimeMs = ProfileConstraints.MAX_PROFILE_TIME_MS,
        stages = listOf(
            ProfileStage(
                name = "Main",
                type = StageType.PRESSURE_CURVE,
                pressureCurve = PressureCurveConfig(
                    axis = PressureCurveAxis.WEIGHT,
                    points = listOf(
                        PressureCurvePoint(0.0, 7.49598240852356),
                        PressureCurvePoint(0.13586480915546417, 9.0),
                        PressureCurvePoint(0.5122016072273254, 9.0),
                        PressureCurvePoint(0.6412059664726257, 7.488448619842529),
                        PressureCurvePoint(1.0, 6.516629576683044)
                    ),
                    durationS = 30.0,
                    maxWeightG = 39.0,
                    maxPressureBar = 9.0,
                    minPressureBar = 0.0
                ),
                exit = ExitCondition(
                    weightGte = 39.0,
                    stageTimeGteMs = 59_661L
                ),
                safety = StageSafety(maxStageTimeMs = 59_780L)
            )
        )
    )

    /** Internal fallback used when no saved profile exists yet. */
    val fallbackProfile = leverSim17gIn39gOut

    /** Profiles intentionally seeded into installs. */
    val builtIns: List<ShotProfile> = listOf(leverSim17gIn39gOut)

    /**
     * Bump whenever [builtIns] changes: installs whose stored seed version is older re-seed on
     * next load, replacing same-name profiles with the bundled versions and appending the rest.
     */
    const val BUILT_INS_VERSION = 5
}
