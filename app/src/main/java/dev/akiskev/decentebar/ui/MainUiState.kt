package dev.akiskev.decentebar.ui

import android.net.Uri
import dev.akiskev.decentebar.ble.ScaleConnectionState
import dev.akiskev.decentebar.model.ControllerState
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.LutValidationResult
import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotLibraryEntry
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.model.ShotSample

data class SharePayload(
    val uri: Uri,
    val mimeType: String,
    val subject: String,
    val chooserTitle: String = "Share shot"
)

data class MainUiState(
    val controllerState: ControllerState = ControllerState.IDLE,
    val serviceEnabled: Boolean = false,
    val snapshot: EbarSnapshot = EbarSnapshot(),
    val profiles: List<ShotProfile> = emptyList(),
    val selectedProfile: ShotProfile = DefaultProfiles.fallbackProfile,
    val loadedLut: PressureLut? = null,
    // Set once a measured calibration sweep has produced the LUT, so the per-snapshot
    // auto-anchor stops overwriting it with the formula-derived one.
    val lutCalibrated: Boolean = false,
    val calibrationMessage: String = "",
    val lutValidation: LutValidationResult = LutValidationResult.Missing,
    val currentWeightG: Double? = null,
    val currentFlowGps: Double = 0.0,
    val currentCalcFlowGps: Double = 0.0,
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
    val events: List<ShotEvent> = emptyList(),
    val videoExportProgress: Float? = null,
    val scaleConnectionState: ScaleConnectionState = ScaleConnectionState.DISCONNECTED,
    val scaleBatteryPercent: Int? = null,
    val importedShotLog: ShotLog? = null,
    val importShotLogMessage: String = "",
    val libraryEntries: List<ShotLibraryEntry> = emptyList(),
    val selectedLibraryShotId: String? = null,
    val selectedLibraryShot: ShotLog? = null,
    val libraryCompareShots: Map<String, ShotLog> = emptyMap(),
    val libraryMessage: String = "",
    val pendingShare: SharePayload? = null,
    /** Set when a finished shot is awaiting the user's "Save to library?" decision. */
    val librarySavePromptVisible: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** When off, developer-only UI (LUT/Debug tabs, raw JSON I/O, log export tools) is hidden. */
    val devMode: Boolean = false,
    val tutorialVisible: Boolean = false,
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
