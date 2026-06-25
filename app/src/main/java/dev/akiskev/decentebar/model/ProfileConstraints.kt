package dev.akiskev.decentebar.model

object ProfileConstraints {
    const val DEFAULT_TARGET_WEIGHT_G = 33.0
    const val DEFAULT_STOP_OFFSET_G = 1.0
    const val MIN_POSITIVE = 0.1
    const val MIN_TARGET_WEIGHT_G = 0.1
    const val MAX_TARGET_WEIGHT_G = 120.0
    const val MAX_PROFILE_TIME_MS = 60_000L
    const val DEFAULT_MAX_SHOT_TIME_MS = MAX_PROFILE_TIME_MS
    const val MAX_PRESSURE_BAR = 12.0
    const val MAX_FLOW_GPS = 8.0

    fun yieldTargetSumG(profile: ShotProfile): Double =
        profile.stages.sumOf { stage ->
            if (stage.type == StageType.YIELD_TIME_TRAJECTORY) stage.yieldTime?.targetYieldG ?: 0.0 else 0.0
        }

    fun yieldTargetSumExcluding(profile: ShotProfile, stageIndex: Int): Double =
        profile.stages.withIndex().sumOf { (index, stage) ->
            if (index != stageIndex && stage.type == StageType.YIELD_TIME_TRAJECTORY) {
                stage.yieldTime?.targetYieldG ?: 0.0
            } else {
                0.0
            }
        }

    fun maxYieldForStage(profile: ShotProfile, stageIndex: Int): Double =
        (profile.targetWeightG - yieldTargetSumExcluding(profile, stageIndex)).coerceAtLeast(MIN_TARGET_WEIGHT_G)

    fun normalize(profile: ShotProfile): ShotProfile {
        val target = profile.targetWeightG.coerceIn(MIN_TARGET_WEIGHT_G, MAX_TARGET_WEIGHT_G)
        val stopOffset = profile.stopOffsetG.coerceIn(0.0, (target - MIN_TARGET_WEIGHT_G).coerceAtLeast(0.0))
        var remainingYield = target
        val stages = profile.stages.mapNotNull { stage ->
            if (stage.type == StageType.STOP) return@mapNotNull null
            val normalized = normalizeStage(stage, target, remainingYield)
            if (normalized.type == StageType.YIELD_TIME_TRAJECTORY) {
                remainingYield -= normalized.yieldTime?.targetYieldG ?: 0.0
            }
            normalized
        }
        return profile.copy(
            targetWeightG = target,
            stopOffsetG = stopOffset,
            maxShotTimeMs = MAX_PROFILE_TIME_MS,
            stages = stages
        )
    }

    private fun normalizeStage(stage: ProfileStage, profileTargetWeightG: Double, remainingYieldG: Double): ProfileStage {
        val exit = stage.exit.copy(
            weightGte = stage.exit.weightGte?.coerceIn(0.0, profileTargetWeightG),
            stageTimeGteMs = stage.exit.stageTimeGteMs?.coerceIn(1L, MAX_PROFILE_TIME_MS),
            flowGte = stage.exit.flowGte?.coerceIn(0.0, MAX_FLOW_GPS),
            flowLte = stage.exit.flowLte?.coerceIn(0.0, MAX_FLOW_GPS)
        )
        val safety = stage.safety.copy(maxStageTimeMs = stage.safety.maxStageTimeMs?.coerceIn(1_000L, MAX_PROFILE_TIME_MS))
        val base = stage.copy(exit = exit, safety = safety)
        return when (base.type) {
            StageType.WEIGHT_BASED_PRESSURE_RAMP -> {
                val start = (base.rampStartWeightG ?: 0.0).coerceIn(0.0, profileTargetWeightG)
                val end = (base.rampEndWeightG ?: profileTargetWeightG).coerceIn(start, profileTargetWeightG)
                base.copy(
                    rampStartPressureBar = base.rampStartPressureBar?.coerceIn(0.0, MAX_PRESSURE_BAR),
                    rampEndPressureBar = base.rampEndPressureBar?.coerceIn(0.0, MAX_PRESSURE_BAR),
                    rampStartWeightG = start,
                    rampEndWeightG = end
                )
            }
            StageType.TIME_BASED_PRESSURE_RAMP -> base.copy(
                rampStartPressureBar = base.rampStartPressureBar?.coerceIn(0.0, MAX_PRESSURE_BAR),
                rampEndPressureBar = base.rampEndPressureBar?.coerceIn(0.0, MAX_PRESSURE_BAR),
                rampDurationMs = base.rampDurationMs?.coerceAtLeast(1_000L)
            )
            StageType.FIXED_PRESSURE -> base.copy(
                fixedPressureBar = base.fixedPressureBar?.coerceIn(0.0, MAX_PRESSURE_BAR)
            )
            StageType.FLOW_LIMITED_PRESSURE -> base.copy(
                pressureCapBar = base.pressureCapBar?.coerceIn(0.0, MAX_PRESSURE_BAR),
                targetFlowGps = base.targetFlowGps?.coerceIn(0.0, MAX_FLOW_GPS)
            )
            StageType.YIELD_TIME_TRAJECTORY -> {
                val yt = base.yieldTime ?: return base
                val maxYield = remainingYieldG.coerceAtLeast(MIN_TARGET_WEIGHT_G)
                base.copy(
                    yieldTime = yt.copy(
                        targetYieldG = yt.targetYieldG.coerceIn(MIN_TARGET_WEIGHT_G, maxYield),
                        targetDurationS = yt.targetDurationS.coerceAtLeast(1.0),
                        maxPressureBar = yt.maxPressureBar.coerceIn(MIN_POSITIVE, MAX_PRESSURE_BAR),
                        maxFlowGps = yt.maxFlowGps.coerceIn(MIN_POSITIVE, MAX_FLOW_GPS),
                        preInfusionPressureBar = yt.preInfusionPressureBar.coerceIn(0.0, MAX_PRESSURE_BAR),
                        minExtractionPressureBar = yt.minExtractionPressureBar.coerceIn(
                            0.0,
                            yt.maxPressureBar.coerceIn(MIN_POSITIVE, MAX_PRESSURE_BAR)
                        )
                    )
                )
            }
            StageType.PRESSURE_CURVE -> {
                val pc = base.pressureCurve ?: return base
                val maxPressure = pc.maxPressureBar.coerceIn(MIN_POSITIVE, MAX_PRESSURE_BAR)
                val minPressure = pc.minPressureBar.coerceIn(0.0, maxPressure)
                base.copy(
                    pressureCurve = pc.copy(
                        durationS = pc.durationS.coerceAtLeast(1.0),
                        maxWeightG = pc.maxWeightG.coerceIn(MIN_TARGET_WEIGHT_G, profileTargetWeightG),
                        maxPressureBar = maxPressure,
                        minPressureBar = minPressure,
                        points = pc.points.map {
                            it.copy(
                                xPct = it.xPct.coerceIn(0.0, 1.0),
                                pressureBar = it.pressureBar.coerceIn(minPressure, maxPressure)
                            )
                        }
                    )
                )
            }
            StageType.STOP -> base
        }
    }
}
