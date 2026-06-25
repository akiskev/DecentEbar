package dev.akiskev.decentebar.model

object ProfileValidator {
    fun validate(profile: ShotProfile): List<String> {
        return buildList {
            if (profile.name.isBlank()) add("Profile name is required")
            if (profile.targetWeightG <= 0.0) add("Target weight must be greater than 0")
            if (profile.stopOffsetG < 0.0) add("Stop offset cannot be negative")
            if (profile.stopOffsetG >= profile.targetWeightG) add("Stop offset must be lower than target weight")
            if (profile.maxShotTimeMs <= 0L) add("Max shot time must be greater than 0")
            if (profile.maxShotTimeMs > ProfileConstraints.MAX_PROFILE_TIME_MS) {
                add("Max shot time cannot exceed ${ProfileConstraints.MAX_PROFILE_TIME_MS / 1000}s")
            }
            val yieldSum = ProfileConstraints.yieldTargetSumG(profile)
            if (yieldSum > profile.targetWeightG + 1e-6) {
                add("Sum of yield/time stage yields cannot exceed target weight")
            }
            if (profile.stages.isEmpty()) add("At least one stage is required")
            profile.stages.forEachIndexed { index, stage ->
                addAll(validateStage(index, stage, profile.targetWeightG))
            }
        }
    }

    private fun validateStage(index: Int, stage: ProfileStage, profileTargetWeightG: Double): List<String> {
        val prefix = "Stage ${index + 1} (${stage.name.ifBlank { "unnamed" }})"
        return buildList {
            if (stage.name.isBlank()) add("$prefix name is required")
            when (stage.type) {
                StageType.FIXED_PRESSURE -> {
                    if (stage.fixedPressureBar == null) add("$prefix fixed pressure is required")
                    if (stage.fixedPressureBar != null && stage.fixedPressureBar !in 0.0..ProfileConstraints.MAX_PRESSURE_BAR) {
                        add("$prefix fixed pressure must be between 0 and ${ProfileConstraints.MAX_PRESSURE_BAR.toInt()} bar")
                    }
                }
                StageType.FLOW_LIMITED_PRESSURE -> {
                    // Only pressure cap and target flow are required. Deadband, pressure step and
                    // correction interval are optional: when null they are auto-tuned at runtime
                    // (see ShotController.runFlowLimitedStage), which is how the default profile and
                    // the profile editor leave them.
                    if (stage.pressureCapBar == null) {
                        add("$prefix pressure cap is required")
                    } else if (stage.pressureCapBar <= 0.0) {
                        add("$prefix pressure cap must be greater than 0")
                    }
                    if (stage.targetFlowGps == null) {
                        add("$prefix target flow is required")
                    } else if (stage.targetFlowGps <= 0.0) {
                        add("$prefix target flow must be greater than 0")
                    }
                }
                StageType.WEIGHT_BASED_PRESSURE_RAMP -> {
                    if (stage.rampStartPressureBar == null) add("$prefix start pressure is required")
                    if (stage.rampEndPressureBar == null) add("$prefix end pressure is required")
                    if (stage.rampStartWeightG == null) add("$prefix start weight is required")
                    if (stage.rampEndWeightG == null) add("$prefix end weight is required")
                    if (stage.rampStartWeightG != null && stage.rampStartWeightG < 0.0) add("$prefix start weight cannot be negative")
                    if (stage.rampEndWeightG != null && stage.rampEndWeightG > profileTargetWeightG) {
                        add("$prefix end weight cannot exceed profile target weight")
                    }
                    if (stage.rampStartWeightG != null && stage.rampEndWeightG != null &&
                        stage.rampEndWeightG < stage.rampStartWeightG
                    ) {
                        add("$prefix end weight must be at least start weight")
                    }
                }
                StageType.TIME_BASED_PRESSURE_RAMP -> {
                    if (stage.rampStartPressureBar == null) add("$prefix start pressure is required")
                    if (stage.rampEndPressureBar == null) add("$prefix end pressure is required")
                    if (stage.rampDurationMs == null) add("$prefix duration is required")
                    if (stage.rampDurationMs != null && stage.rampDurationMs <= 0L) add("$prefix duration must be greater than 0")
                }
                StageType.YIELD_TIME_TRAJECTORY -> {
                    val yt = stage.yieldTime
                    if (yt == null) {
                        add("$prefix yield/time config is required")
                    } else {
                        if (yt.targetYieldG <= 0.0) add("$prefix target yield must be greater than 0")
                        if (yt.targetDurationS <= 0.0) add("$prefix target time must be greater than 0")
                        if (yt.maxFlowGps <= 0.0) add("$prefix max flow must be greater than 0")
                        if (yt.maxPressureBar <= 0.0) add("$prefix max pressure must be greater than 0")
                        if (yt.minFlowGps > yt.maxFlowGps) add("$prefix min flow cannot exceed max flow")
                        if (yt.minPressureBar > yt.maxPressureBar) add("$prefix min pressure cannot exceed max pressure")
                        if (yt.minExtractionPressureBar < 0.0) add("$prefix extraction floor cannot be negative")
                        if (yt.minExtractionPressureBar > yt.maxPressureBar) add("$prefix extraction floor cannot exceed max pressure")
                        if (yt.correctionStrength !in 0.0..1.0) add("$prefix correction strength must be between 0 and 1")
                        if (yt.maxPressureRiseBarPerS <= 0.0) add("$prefix max pressure rise rate must be greater than 0")
                        if (yt.maxPressureFallBarPerS <= 0.0) add("$prefix max pressure fall rate must be greater than 0")
                        if (yt.lateShotCorrectionLimitS < 0.0) add("$prefix late-shot correction window cannot be negative")
                        if (yt.curveType == FlowCurveType.CUSTOM_POINTS && yt.customPoints.size < 2) {
                            add("$prefix custom curve needs at least 2 points")
                        }
                        if (yt.targetYieldG > profileTargetWeightG) add("$prefix target yield cannot exceed profile target weight")
                    }
                }
                StageType.PRESSURE_CURVE -> {
                    val pc = stage.pressureCurve
                    if (pc == null) {
                        add("$prefix pressure curve config is required")
                    } else {
                        if (pc.maxPressureBar <= 0.0) add("$prefix max pressure must be greater than 0")
                        if (pc.minPressureBar > pc.maxPressureBar) add("$prefix min pressure cannot exceed max pressure")
                        if (pc.axis == PressureCurveAxis.TIME && pc.durationS <= 0.0) add("$prefix duration must be greater than 0")
                        if (pc.axis == PressureCurveAxis.WEIGHT && pc.maxWeightG <= 0.0) add("$prefix max weight must be greater than 0")
                        if (pc.axis == PressureCurveAxis.WEIGHT && pc.maxWeightG > profileTargetWeightG) {
                            add("$prefix max weight cannot exceed profile target weight")
                        }
                        if (pc.points.size < 2) add("$prefix pressure curve needs at least 2 points")
                        if (pc.points.any { it.xPct !in 0.0..1.0 }) add("$prefix pressure curve points must stay on the X axis")
                        if (pc.points.any { it.pressureBar < pc.minPressureBar || it.pressureBar > pc.maxPressureBar }) {
                            add("$prefix pressure curve points must stay within pressure limits")
                        }
                    }
                }
                StageType.STOP -> {
                    add("$prefix stop stages are no longer supported")
                }
            }

            val exit = stage.exit
            val hasExit = exit.weightGte != null ||
                exit.stageTimeGteMs != null ||
                exit.flowGte != null ||
                exit.flowLte != null ||
                exit.firstDropDetected
            if (!hasExit) add("$prefix needs at least one exit condition")
            if (exit.mode !in ExitMode.entries) add("$prefix has an unknown exit mode")
            if (exit.weightGte != null && exit.weightGte > profileTargetWeightG) {
                add("$prefix exit weight cannot exceed profile target weight")
            }
            if (exit.stageTimeGteMs != null && exit.stageTimeGteMs <= 0L) add("$prefix exit time must be greater than 0")
            if (exit.stageTimeGteMs != null && exit.stageTimeGteMs > ProfileConstraints.MAX_PROFILE_TIME_MS) {
                add("$prefix exit time cannot exceed ${ProfileConstraints.MAX_PROFILE_TIME_MS / 1000}s")
            }
            if (exit.flowGte != null && exit.flowLte != null && exit.flowGte > exit.flowLte) {
                add("$prefix flow >= cannot exceed flow <=")
            }
            if (stage.safety.maxStageTimeMs != null && stage.safety.maxStageTimeMs <= 0L) {
                add("$prefix stage max time must be greater than 0")
            }
            if (stage.safety.maxStageTimeMs != null && stage.safety.maxStageTimeMs > ProfileConstraints.MAX_PROFILE_TIME_MS) {
                add("$prefix stage max time cannot exceed ${ProfileConstraints.MAX_PROFILE_TIME_MS / 1000}s")
            }
        }
    }
}
