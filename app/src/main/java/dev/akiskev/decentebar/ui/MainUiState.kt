package dev.akiskev.decentebar.ui

import dev.akiskev.decentebar.model.ControllerState
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.LutValidationResult
import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.model.ShotSample

data class MainUiState(
    val controllerState: ControllerState = ControllerState.IDLE,
    val serviceEnabled: Boolean = false,
    val snapshot: EbarSnapshot = EbarSnapshot(),
    val profiles: List<ShotProfile> = listOf(DefaultProfiles.flow34),
    val selectedProfile: ShotProfile = DefaultProfiles.flow34,
    val loadedLut: PressureLut? = null,
    val lutValidation: LutValidationResult = LutValidationResult.Missing,
    val currentWeightG: Double? = null,
    val currentFlowGps: Double = 0.0,
    val currentStageIndex: Int = -1,
    val commandedPressureBar: Double? = null,
    val elapsedShotTimeMs: Long = 0L,
    val safetyStatus: String = "Idle",
    val lastPressureCommand: String = "--",
    val lastStopCommand: String = "--",
    val lastSafetyError: String = "--",
    val profileMessage: String = "",
    val lutMessage: String = "",
    val logMessage: String = "",
    val exportedProfileJson: String = "",
    val exportedLutJson: String = "",
    val exportedLogJson: String = "",
    val samples: List<ShotSample> = emptyList(),
    val events: List<ShotEvent> = emptyList()
) {
    val isArmed: Boolean
        get() = controllerState == ControllerState.ARMED ||
            controllerState == ControllerState.RUNNING ||
            controllerState == ControllerState.STAGE_TRANSITION ||
            controllerState == ControllerState.STOPPING

    val currentStageName: String
        get() = selectedProfile.stages.getOrNull(currentStageIndex)?.name ?: "--"

    val stopAtWeightG: Double
        get() = selectedProfile.targetWeightG - selectedProfile.stopOffsetG
}
