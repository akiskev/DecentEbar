package dev.akiskev.decentebar.model

object ProfileValidator {
    fun validate(profile: ShotProfile): List<String> {
        return buildList {
            if (profile.name.isBlank()) add("Profile name is required")
            if (profile.targetWeightG <= 0.0) add("Target weight must be greater than 0")
            if (profile.stopOffsetG < 0.0) add("Stop offset cannot be negative")
            if (profile.maxShotTimeMs <= 0L) add("Max shot time must be greater than 0")
            if (profile.stages.isEmpty()) add("At least one stage is required")
            profile.stages.forEachIndexed { index, stage ->
                addAll(validateStage(index, stage))
            }
        }
    }

    private fun validateStage(index: Int, stage: ProfileStage): List<String> {
        val prefix = "Stage ${index + 1} (${stage.name.ifBlank { "unnamed" }})"
        return buildList {
            if (stage.name.isBlank()) add("$prefix name is required")
            when (stage.type) {
                StageType.FIXED_PRESSURE -> {
                    if (stage.fixedPressureBar == null) add("$prefix fixed pressure is required")
                }
                StageType.FLOW_LIMITED_PRESSURE -> {
                    // Only pressure cap and target flow are required. Deadband, pressure step and
                    // correction interval are optional: when null they are auto-tuned at runtime
                    // (see ShotController.runFlowLimitedStage), which is how the default profile and
                    // the profile editor leave them.
                    if (stage.pressureCapBar == null) add("$prefix pressure cap is required")
                    if (stage.targetFlowGps == null) add("$prefix target flow is required")
                }
                StageType.WEIGHT_BASED_PRESSURE_RAMP -> {
                    if (stage.rampEndPressureBar == null) add("$prefix end pressure is required")
                    if (stage.rampStartWeightG == null) add("$prefix start weight is required")
                    if (stage.rampEndWeightG == null) add("$prefix end weight is required")
                }
                StageType.TIME_BASED_PRESSURE_RAMP -> {
                    if (stage.rampStartPressureBar == null) add("$prefix start pressure is required")
                    if (stage.rampEndPressureBar == null) add("$prefix end pressure is required")
                    if (stage.rampDurationMs == null) add("$prefix duration is required")
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
                        if (pc.points.size < 2) add("$prefix pressure curve needs at least 2 points")
                    }
                }
                StageType.STOP -> Unit
            }

            val exit = stage.exit
            val hasExit = exit.weightGte != null ||
                exit.stageTimeGteMs != null ||
                exit.flowGte != null ||
                exit.flowLte != null ||
                exit.firstDropDetected ||
                exit.manualSkip ||
                exit.safetyTimeout ||
                stage.type == StageType.STOP
            if (!hasExit) add("$prefix needs at least one exit condition")
            if (exit.mode !in ExitMode.entries) add("$prefix has an unknown exit mode")
        }
    }
}
