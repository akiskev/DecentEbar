package dev.akiskev.decentebar.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.akiskev.decentebar.accessibility.EbarAccessibilityService
import dev.akiskev.decentebar.ble.BookooScaleManager
import dev.akiskev.decentebar.engine.FlowEstimator
import dev.akiskev.decentebar.engine.PressureLutManager
import dev.akiskev.decentebar.model.BuiltInPressureLut
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.ProfileValidator
import dev.akiskev.decentebar.model.SafetyConfig
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.storage.JsonCodec
import dev.akiskev.decentebar.storage.ProfileRepository
import dev.akiskev.decentebar.storage.ShotLogCodec
import dev.akiskev.decentebar.storage.ShotVideoExporter
import dev.akiskev.decentebar.util.screenSizePx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Owns the screen state and wiring. The shot state machine lives in [ShotController]; this class
 * forwards accessibility snapshots and scale readings to it, and handles everything around the
 * shot itself: profile CRUD, LUT export, and shot-log import/export (JSON, HTML, MP4).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val safetyConfig = SafetyConfig()
    private val profileRepository = ProfileRepository(application)
    private val lutManager = PressureLutManager(safetyConfig)
    private val flowEstimator = FlowEstimator(safetyConfig.maxFlowGps)
    private val scaleManager = BookooScaleManager(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val controller = ShotController(
        state = _uiState,
        safetyConfig = safetyConfig,
        lutManager = lutManager,
        flowEstimator = flowEstimator,
        startScaleTimer = scaleManager::startTimer
    )

    init {
        val profiles = profileRepository.loadProfiles()
        val selected = profiles.firstOrNull() ?: DefaultProfiles.flow34
        val (initW, initH) = screenSizePx(application)
        val lut = BuiltInPressureLut.buildFor(initW, initH)
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = selected,
                loadedLut = lut,
                lutValidation = controller.validateLut(it.snapshot, lut, requireForegroundPackage = false),
                exportedLutJson = ""
            )
        }

        viewModelScope.launch {
            EbarAccessibilityService.isEnabled.collect { enabled ->
                _uiState.update { it.copy(serviceEnabled = enabled) }
            }
        }

        // Both source flows are collected on viewModelScope (Dispatchers.Main.immediate). This main-
        // thread confinement is what makes ShotController's unsynchronized runtime state safe.
        viewModelScope.launch {
            EbarAccessibilityService.snapshots.collect(controller::onSnapshot)
        }

        viewModelScope.launch {
            scaleManager.connectionState.collect { state ->
                _uiState.update { it.copy(scaleConnectionState = state) }
            }
        }

        viewModelScope.launch {
            scaleManager.latestReading.filterNotNull().collect(controller::onScaleReading)
        }
    }

    override fun onCleared() {
        super.onCleared()
        scaleManager.close()
    }

    // --- Shot controls (delegated) ---

    fun arm() = controller.arm()

    fun disarm() = controller.disarm()

    fun emergencyStop() = controller.emergencyStop()

    fun manualSkipStage() = controller.manualSkipStage()

    // --- Scale ---

    fun connectToScale() = scaleManager.startScan()

    fun disconnectScale() = scaleManager.disconnect()

    // --- Shot log ---

    fun resetShotLog() {
        _uiState.update {
            it.copy(samples = emptyList(), events = emptyList(), exportedLogJson = "")
        }
    }

    fun exportShotLog() {
        currentShotLogJson()
    }

    fun currentShotLog(): ShotLog? {
        val state = _uiState.value
        if (state.samples.isEmpty() && state.events.isEmpty()) return null
        val targetFlows = state.selectedProfile.stages
            .mapNotNull { stage -> stage.targetFlowGps?.let { stage.name to it } }
            .toMap()
        return ShotLog(
            profileName = state.selectedProfile.name,
            startedAtMs = controller.shotStartMs,
            stoppedAtMs = controller.shotStoppedMs,
            samples = state.samples,
            events = state.events,
            stageTargetFlows = targetFlows
        )
    }

    fun currentShotLogJson(): String {
        val log = currentShotLog() ?: run {
            _uiState.update { it.copy(logMessage = "No shot data to export") }
            return ""
        }
        val encoded = ShotLogCodec.encode(log)
        _uiState.update { it.copy(exportedLogJson = encoded) }
        return encoded
    }

    fun exportShotVideo(uri: Uri, format: ShotVideoExporter.Format, log: ShotLog? = null) {
        val resolvedLog = log ?: currentShotLog() ?: run {
            _uiState.update { it.copy(logMessage = "No shot data to export") }
            return
        }
        _uiState.update { it.copy(videoExportProgress = 0f) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ShotVideoExporter.export(
                        context = getApplication(),
                        log = resolvedLog,
                        outputUri = uri,
                        format = format,
                        onProgress = { p ->
                            _uiState.update { it.copy(videoExportProgress = p) }
                        }
                    )
                }
            }
            _uiState.update {
                it.copy(
                    videoExportProgress = null,
                    logMessage = if (result.isSuccess) "Video saved" else "Video export failed: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun importShotLogFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = readJsonFromUri(getApplication(), uri)
            if (content == null) {
                _uiState.update { it.copy(importShotLogMessage = "Failed to read file") }
                return@launch
            }
            val log = parseShotLog(content)
            if (log == null) {
                _uiState.update { it.copy(importShotLogMessage = "Unrecognised shot log file") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    importedShotLog = log,
                    importShotLogMessage = "Loaded: ${log.profileName} (${log.samples.size} samples)"
                )
            }
        }
    }

    fun clearImportedShotLog() {
        _uiState.update { it.copy(importedShotLog = null, importShotLogMessage = "") }
    }

    private fun parseShotLog(content: String): ShotLog? {
        runCatching { ShotLogCodec.decode(content) }.getOrNull()?.let { return it }
        val match = Regex(
            """<script[^>]+id="shotlog-data"[^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE
        ).find(content)
        if (match != null) {
            val json = match.groupValues[1].trim().replace("<\\/", "</")
            runCatching { ShotLogCodec.decode(json) }.getOrNull()?.let { return it }
        }
        return null
    }

    // --- Profiles ---

    fun setProfileMessage(msg: String) {
        _uiState.update { it.copy(profileMessage = msg) }
    }

    fun setLogMessage(msg: String) {
        _uiState.update { it.copy(logMessage = msg) }
    }

    fun selectProfile(name: String) {
        val profile = _uiState.value.profiles.firstOrNull { it.name == name } ?: return
        _uiState.update {
            it.copy(selectedProfile = profile, profileMessage = "Selected ${profile.name}")
        }
    }

    fun saveProfile(profile: ShotProfile) {
        val errors = ProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(profileMessage = errors.joinToString("; ")) }
            return
        }

        val profiles = profileRepository.upsert(profile)
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = profile,
                profileMessage = "Saved ${profile.name}",
                exportedProfileJson = profileRepository.exportProfile(profile)
            )
        }
    }

    fun deleteSelectedProfile() {
        val state = _uiState.value
        val profiles = profileRepository.delete(state.selectedProfile.name)
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = profiles.first(),
                profileMessage = "Deleted ${state.selectedProfile.name}"
            )
        }
    }

    fun duplicateSelectedProfile() {
        val profiles = profileRepository.duplicate(_uiState.value.selectedProfile)
        val duplicated = profiles.last()
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = duplicated,
                profileMessage = "Duplicated as ${duplicated.name}"
            )
        }
    }

    fun exportSelectedProfile() {
        selectedProfileJson()
    }

    fun selectedProfileJson(): String {
        val profile = _uiState.value.selectedProfile
        val encoded = profileRepository.exportProfile(profile)
        _uiState.update {
            it.copy(exportedProfileJson = encoded, profileMessage = "Exported ${profile.name}")
        }
        return encoded
    }

    fun importProfileJson(rawJson: String) {
        profileRepository.importProfile(rawJson)
            .onSuccess { profile ->
                val profiles = profileRepository.upsert(profile)
                _uiState.update {
                    it.copy(
                        profiles = profiles,
                        selectedProfile = profile,
                        profileMessage = "Imported ${profile.name}",
                        exportedProfileJson = profileRepository.exportProfile(profile)
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(profileMessage = "Import failed: ${error.message}") }
            }
    }

    // --- LUT ---

    fun exportLut() {
        val lut = _uiState.value.loadedLut
        _uiState.update {
            it.copy(
                exportedLutJson = lut?.let { JsonCodec.json.encodeToString(it) }.orEmpty(),
                lutMessage = if (lut == null) "No LUT (screen size unknown)" else "Exported ${lut.name}"
            )
        }
    }

    fun testPressure(pressureText: String) {
        val pressure = pressureText.toDoubleOrNull()
        if (pressure == null) {
            _uiState.update { it.copy(lutMessage = "Enter a valid pressure") }
            return
        }
        EbarAccessibilityService.current()?.captureSnapshot()?.let { fresh ->
            val validation = controller.validateLut(fresh, _uiState.value.loadedLut, requireForegroundPackage = false)
            _uiState.update { it.copy(snapshot = fresh, lutValidation = validation) }
        }
        controller.commandPressure(pressure, System.currentTimeMillis(), force = true, source = "Manual LUT test")
    }
}
