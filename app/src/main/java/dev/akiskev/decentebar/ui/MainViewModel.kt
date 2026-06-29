package dev.akiskev.decentebar.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.akiskev.decentebar.accessibility.EbarAccessibilityService
import dev.akiskev.decentebar.ble.BookooScaleManager
import dev.akiskev.decentebar.engine.FlowEstimator
import dev.akiskev.decentebar.engine.PressureLutManager
import dev.akiskev.decentebar.model.BuiltInPressureLut
import dev.akiskev.decentebar.model.ControllerState
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.PressurePoint
import dev.akiskev.decentebar.model.ProfileValidator
import dev.akiskev.decentebar.model.SafetyConfig
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.model.ShotTargetResolver
import dev.akiskev.decentebar.storage.JsonCodec
import dev.akiskev.decentebar.storage.ProfileRepository
import dev.akiskev.decentebar.storage.ShotLibraryRepository
import dev.akiskev.decentebar.storage.ShotLogCodec
import dev.akiskev.decentebar.storage.ShotShareExporter
import dev.akiskev.decentebar.storage.ShotShareFormat
import dev.akiskev.decentebar.storage.ShotVideoExporter
import dev.akiskev.decentebar.util.screenSizePx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** User-entered shot metadata collected by the save dialog. */
data class ShotMetadata(
    val beansName: String,
    val grindSetting: String,
    val doseG: Double?,
    val roastLevel: String,
    val basket: String,
    val targetYieldG: Double?,
    val targetTimeS: Double?,
    val tasteNotes: String,
    val rating: Int?
)

/**
 * Owns the screen state and wiring. The shot state machine lives in [ShotController]; this class
 * forwards accessibility snapshots and scale readings to it, and handles everything around the
 * shot itself: profile CRUD, LUT export, and shot-log import/export (JSON, HTML, MP4).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val safetyConfig = SafetyConfig()
    private val settingsPrefs = application.getSharedPreferences("settings", Application.MODE_PRIVATE)
    private val profileRepository = ProfileRepository(application)
    private val shotLibraryRepository = ShotLibraryRepository(application)
    private val lutManager = PressureLutManager(safetyConfig)
    private val flowEstimator = FlowEstimator(safetyConfig.maxFlowGps)
    private val scaleManager = BookooScaleManager(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // "Save to library?" prompt wiring. A shot is pulled with the E-Bar app in the foreground, so
    // DecentEbar is backgrounded while it runs. When the shot reaches STOPPED with recorded data we
    // latch [shotPendingSavePrompt]; the prompt is then shown the next time the user brings
    // DecentEbar back to the foreground (or immediately if it already is).
    private var appInForeground = false
    private var shotPendingSavePrompt = false

    private val controller = ShotController(
        state = _uiState,
        safetyConfig = safetyConfig,
        lutManager = lutManager,
        flowEstimator = flowEstimator,
        prepareScaleForShotStart = scaleManager::prepareForShotStart
    )

    init {
        ShotShareExporter.clearShareCache(application)
        val profiles = profileRepository.loadProfiles()
        val selected = profiles.firstOrNull() ?: DefaultProfiles.fallbackProfile
        val libraryEntries = shotLibraryRepository.loadEntries()
        val (initW, initH) = screenSizePx(application)
        val lut = BuiltInPressureLut.buildFor(initW, initH)
        _uiState.update {
            it.copy(
                profiles = profiles,
                selectedProfile = selected,
                libraryEntries = libraryEntries,
                selectedLibraryShotId = libraryEntries.firstOrNull()?.shotId,
                selectedLibraryShot = libraryEntries.firstOrNull()?.let { entry ->
                    shotLibraryRepository.getShot(entry.shotId)
                },
                loadedLut = lut,
                lutValidation = controller.validateLut(it.snapshot, lut, requireForegroundPackage = false),
                exportedLutJson = "",
                themeMode = loadThemeMode(),
                devMode = settingsPrefs.getBoolean(KEY_DEV_MODE, false),
                tutorialVisible = !settingsPrefs.getBoolean(KEY_FIRST_USE_TUTORIAL_SEEN_V1, false)
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

        // Detect a finished pull: when the controller settles into STOPPED with recorded samples,
        // arm the "Save to library?" prompt (shown on return to foreground).
        viewModelScope.launch {
            _uiState
                .map { it.controllerState }
                .distinctUntilChanged()
                .collect { controllerState ->
                    if (controllerState == ControllerState.STOPPED && _uiState.value.samples.isNotEmpty()) {
                        shotPendingSavePrompt = true
                        showLibrarySavePromptIfPending()
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scaleManager.close()
    }

    // --- Settings ---

    /**
     * Re-checks (authoritatively, via system settings) whether the accessibility service is enabled.
     * Called from the Activity on resume so returning from the system Accessibility screen — or a
     * cold start where the service hasn't rebound yet — reflects the real state immediately.
     */
    fun refreshServiceEnabled() {
        val enabled = EbarAccessibilityService.isEnabledInSettings(getApplication())
        _uiState.update { it.copy(serviceEnabled = enabled) }
    }

    /** Called from the Activity on resume: surface a pending post-shot save prompt, if any. */
    fun onAppForegrounded() {
        appInForeground = true
        showLibrarySavePromptIfPending()
    }

    /** Called from the Activity on pause: a shot finishing now will prompt on the next resume. */
    fun onAppBackgrounded() {
        appInForeground = false
    }

    private fun showLibrarySavePromptIfPending() {
        if (appInForeground && shotPendingSavePrompt && _uiState.value.samples.isNotEmpty()) {
            shotPendingSavePrompt = false
            _uiState.update { it.copy(librarySavePromptVisible = true) }
        }
    }

    fun dismissLibrarySavePrompt() {
        _uiState.update { it.copy(librarySavePromptVisible = false) }
    }

    fun setDevMode(enabled: Boolean) {
        settingsPrefs.edit().putBoolean(KEY_DEV_MODE, enabled).apply()
        _uiState.update { it.copy(devMode = enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsPrefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun openTutorial() {
        _uiState.update { it.copy(tutorialVisible = true) }
    }

    fun completeTutorial() {
        markTutorialSeen()
    }

    fun skipTutorial() {
        markTutorialSeen()
    }

    private fun markTutorialSeen() {
        settingsPrefs.edit().putBoolean(KEY_FIRST_USE_TUTORIAL_SEEN_V1, true).apply()
        _uiState.update { it.copy(tutorialVisible = false) }
    }

    private fun loadThemeMode(): ThemeMode {
        val stored = settingsPrefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    // --- Shot controls (delegated) ---

    fun arm() {
        // Starting a new shot supersedes any unsaved-shot prompt from the previous pull.
        shotPendingSavePrompt = false
        controller.arm()
    }

    fun disarm() = controller.disarm()

    fun emergencyStop() = controller.emergencyStop()

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

    fun currentShotLog(metadata: ShotMetadata? = null): ShotLog? {
        val state = _uiState.value
        if (state.samples.isEmpty() && state.events.isEmpty()) return null
        val targetFlows = state.selectedProfile.stages
            .mapNotNull { stage -> stage.targetFlowGps?.let { stage.name to it } }
            .toMap()
        // Flow source: samples carry altFlowGps (the parallel software estimate) only when the
        // BLE scale drove the loop, so it's a reliable during-shot indicator of the data source.
        val scaleDriven = state.samples.any { it.altFlowGps != null }
        val targets = metadata?.let {
            ShotTargetResolver.resolve(
                profile = state.selectedProfile,
                userTargetYieldG = it.targetYieldG,
                userTargetTimeS = it.targetTimeS
            )
        }
        return ShotLog(
            profileName = state.selectedProfile.name,
            startedAtMs = controller.shotStartMs,
            stoppedAtMs = controller.shotStoppedMs,
            samples = state.samples,
            events = state.events,
            stageTargetFlows = targetFlows,
            beansName = metadata?.beansName?.takeIf { it.isNotBlank() },
            grindSetting = metadata?.grindSetting?.takeIf { it.isNotBlank() },
            doseG = metadata?.doseG,
            roastLevel = metadata?.roastLevel?.takeIf { it.isNotBlank() },
            basket = metadata?.basket?.takeIf { it.isNotBlank() },
            targetYieldG = targets?.targetYieldG,
            targetTimeS = targets?.targetTimeS,
            tasteNotes = metadata?.tasteNotes?.takeIf { it.isNotBlank() },
            rating = metadata?.rating?.coerceIn(1, 5),
            appVersion = appVersionName(),
            flowSource = if (scaleDriven) "scale" else "accessibility",
            scaleBatteryPercent = if (scaleDriven) state.scaleBatteryPercent else null,
            profile = state.selectedProfile
        )
    }

    private fun appVersionName(): String? = runCatching {
        val ctx = getApplication<Application>()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrNull()

    fun currentShotLogJson(): String {
        val log = currentShotLog() ?: run {
            _uiState.update { it.copy(logMessage = "No shot data to export") }
            return ""
        }
        val encoded = ShotLogCodec.encode(log)
        _uiState.update { it.copy(exportedLogJson = encoded) }
        return encoded
    }

    fun saveCurrentShotToLibrary(metadata: ShotMetadata) {
        val log = currentShotLog(metadata) ?: run {
            _uiState.update { it.copy(logMessage = "No shot data to save") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { shotLibraryRepository.saveShot(log) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { entry ->
                        val entries = shotLibraryRepository.loadEntries()
                        state.copy(
                            libraryEntries = entries,
                            selectedLibraryShotId = entry.shotId,
                            selectedLibraryShot = shotLibraryRepository.getShot(entry.shotId),
                            logMessage = "Saved to Shot Library",
                            libraryMessage = "Saved ${entry.beansName ?: entry.profileName}"
                        )
                    },
                    onFailure = { error ->
                        state.copy(logMessage = "Library save failed: ${error.message}")
                    }
                )
            }
        }
    }

    fun saveImportedShotToLibrary() {
        val log = _uiState.value.importedShotLog ?: run {
            _uiState.update { it.copy(importShotLogMessage = "No imported shot to save") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { shotLibraryRepository.saveShot(log) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { entry ->
                        val entries = shotLibraryRepository.loadEntries()
                        state.copy(
                            libraryEntries = entries,
                            selectedLibraryShotId = entry.shotId,
                            selectedLibraryShot = shotLibraryRepository.getShot(entry.shotId),
                            importShotLogMessage = "Saved imported shot to Library",
                            libraryMessage = "Saved ${entry.beansName ?: entry.profileName}"
                        )
                    },
                    onFailure = { error ->
                        state.copy(importShotLogMessage = "Library save failed: ${error.message}")
                    }
                )
            }
        }
    }

    fun selectLibraryShot(shotId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val log = shotLibraryRepository.getShot(shotId)
            _uiState.update {
                it.copy(
                    selectedLibraryShotId = shotId,
                    selectedLibraryShot = log,
                    libraryMessage = if (log == null) "Shot not found" else ""
                )
            }
        }
    }

    fun loadLibraryCompareShots(shotIds: List<String>) {
        val ids = shotIds.distinct().take(2)
        if (ids.size != 2) {
            _uiState.update { it.copy(libraryCompareShots = emptyMap()) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val shots = ids.mapNotNull { id ->
                shotLibraryRepository.getShot(id)?.let { log -> id to log }
            }.toMap()
            _uiState.update {
                it.copy(
                    libraryCompareShots = shots,
                    libraryMessage = if (shots.size == 2) "" else "Compare shots not found"
                )
            }
        }
    }

    fun clearLibraryCompareShots() {
        _uiState.update { it.copy(libraryCompareShots = emptyMap()) }
    }

    fun deleteLibraryShot(shotId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = shotLibraryRepository.deleteShot(shotId)
            val selected = entries.firstOrNull()
            _uiState.update {
                it.copy(
                    libraryEntries = entries,
                    selectedLibraryShotId = selected?.shotId,
                    selectedLibraryShot = selected?.let { entry -> shotLibraryRepository.getShot(entry.shotId) },
                    libraryCompareShots = it.libraryCompareShots - shotId,
                    libraryMessage = "Deleted shot"
                )
            }
        }
    }

    fun setLibraryShotBest(shotId: String, best: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val update = shotLibraryRepository.setBestForBean(shotId, best)
            val selected = update.selectedShot
            val scopeName = selected?.beansName?.takeIf { it.isNotBlank() } ?: "this bean"
            val replaced = update.replaced?.beansName?.takeIf { it.isNotBlank() }
            val message = when {
                selected == null -> "Shot not found"
                best && replaced != null -> "Best updated for $scopeName; replaced $replaced"
                best -> "Marked best for $scopeName"
                else -> "Best marker cleared"
            }
            _uiState.update {
                it.copy(
                    libraryEntries = update.entries,
                    selectedLibraryShotId = selected?.shotId ?: it.selectedLibraryShotId,
                    selectedLibraryShot = selected ?: it.selectedLibraryShot,
                    libraryMessage = message
                )
            }
        }
    }

    fun shareLibraryShot(shotId: String, format: ShotShareFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            val log = shotLibraryRepository.getShot(shotId)
            if (log == null) {
                _uiState.update { it.copy(libraryMessage = "Shot not found") }
                return@launch
            }
            val result = runCatching {
                ShotShareExporter.createShareFile(getApplication(), log, format)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { share ->
                        state.copy(
                            pendingShare = SharePayload(
                                uri = share.uri,
                                mimeType = share.mimeType,
                                subject = share.subject
                            ),
                            libraryMessage = "Prepared ${format.label} share"
                        )
                    },
                    onFailure = { error ->
                        state.copy(libraryMessage = "Share failed: ${error.message}")
                    }
                )
            }
        }
    }

    fun shareLibraryComparison(shotIds: List<String>, format: ShotShareFormat) {
        val ids = shotIds.distinct().take(2)
        if (ids.size != 2) {
            _uiState.update { it.copy(libraryMessage = "Select two shots to compare") }
            return
        }
        if (format == ShotShareFormat.JSON) {
            _uiState.update { it.copy(libraryMessage = "Compare share supports PNG and HTML") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val shotA = shotLibraryRepository.getShot(ids[0])
            val shotB = shotLibraryRepository.getShot(ids[1])
            if (shotA == null || shotB == null) {
                _uiState.update { it.copy(libraryMessage = "Compare shots not found") }
                return@launch
            }
            val result = runCatching {
                ShotShareExporter.createCompareShareFile(getApplication(), shotA, shotB, format)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { share ->
                        state.copy(
                            pendingShare = SharePayload(
                                uri = share.uri,
                                mimeType = share.mimeType,
                                subject = share.subject,
                                chooserTitle = "Share comparison"
                            ),
                            libraryMessage = "Prepared compare ${format.label} share"
                        )
                    },
                    onFailure = { error ->
                        state.copy(libraryMessage = "Compare share failed: ${error.message}")
                    }
                )
            }
        }
    }

    fun clearPendingShare() {
        _uiState.update { it.copy(pendingShare = null) }
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

    fun setLibraryMessage(msg: String) {
        _uiState.update { it.copy(libraryMessage = msg) }
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
                selectedProfile = profiles.firstOrNull() ?: DefaultProfiles.fallbackProfile,
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

    fun profileJson(profile: ShotProfile): String {
        val errors = ProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(profileMessage = errors.joinToString("; ")) }
            return ""
        }
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

    /**
     * Sweeps the pressure bar through a range of Y positions, reading the value the
     * e-bar actually shows at each (settled), and builds an exact pressure→Y LUT from
     * the measured points. Run with the e-bar pressure screen open and no shot running.
     * The resulting LUT is applied for the session and its per-bar fractions are logged
     * (tag DecentEbar) so they can be baked into [BuiltInPressureLut] permanently.
     */
    fun calibratePressureBar() {
        viewModelScope.launch {
            val service = EbarAccessibilityService.current()
            if (service == null) {
                _uiState.update { it.copy(calibrationMessage = "Accessibility service not connected") }
                return@launch
            }

            _uiState.update { it.copy(calibrationMessage = "Open the e-bar pressure screen now…") }
            delay(4_000)

            val probe = service.captureSnapshot()
            val bar = BuiltInPressureLut.findPressureBar(probe.nodes, probe.screenWidth, probe.screenHeight)
            if (bar == null) {
                _uiState.update { it.copy(calibrationMessage = "Pressure bar not found — open the e-bar pressure screen and retry") }
                return@launch
            }

            val x = bar.centerX.toFloat()
            val top = bar.top.toFloat()
            val bottom = bar.bottom.toFloat()
            val mid = (top + bottom) / 2f
            val height = bottom - top

            // Sweep within the active track, staying clear of the "Pr." label dead zone
            // at the bottom, from the low-pressure end up to the high-pressure end.
            val yLowPressure = bottom - 80f
            val yHighPressure = top + 25f
            val steps = 24
            val samples = mutableListOf<Pair<Float, Double>>()

            for (i in 0..steps) {
                val y = yLowPressure + (yHighPressure - yLowPressure) * (i.toFloat() / steps)
                val toward = if (y <= mid) CALIBRATION_HOP_PX else -CALIBRATION_HOP_PX
                val startY = (y + toward).coerceIn(top, bottom)
                service.dispatchSwipe(x, startY, x, y)
                delay(650)
                val reading = readBarPressureValue(service.captureSnapshot())
                if (reading != null) {
                    samples.add(y to reading)
                    Log.i(CALIB_TAG, "calib step $i: y=${y.toInt()} reads ${"%.2f".format(reading)} bar")
                }
                _uiState.update { it.copy(calibrationMessage = "Calibrating… ${i + 1}/${steps + 1}") }
            }

            if (samples.size < 5) {
                _uiState.update { it.copy(calibrationMessage = "Calibration failed — only ${samples.size} readings") }
                return@launch
            }

            val points = (0..12).map { barValue ->
                val y = yForBar(samples, barValue.toDouble())
                Log.i(CALIB_TAG, "calib LUT: $barValue bar -> y=${y.toInt()} (frac=${"%.4f".format((y - top) / height)})")
                PressurePoint(barValue.toDouble(), x, y)
            }
            val lut = PressureLut(
                name = "E-Bar pressure LUT (calibrated)",
                screenWidth = maxOf(probe.screenWidth, probe.screenHeight),
                screenHeight = minOf(probe.screenWidth, probe.screenHeight),
                orientation = "landscape",
                points = points
            )
            val validation = controller.validateLut(probe, lut, requireForegroundPackage = false)
            _uiState.update {
                it.copy(
                    loadedLut = lut,
                    lutCalibrated = true,
                    lutValidation = validation,
                    calibrationMessage = "Calibrated from ${samples.size} readings — applied"
                )
            }
        }
    }

    private fun readBarPressureValue(snapshot: EbarSnapshot): Double? {
        val bar = BuiltInPressureLut.findPressureBar(snapshot.nodes, snapshot.screenWidth, snapshot.screenHeight) ?: return null
        val desc = bar.contentDescription ?: return null
        return desc.split('\n', ' ', '\t').firstNotNullOfOrNull { it.trim().toDoubleOrNull() }
    }

    /** Interpolates the measured (Y, pressure) samples to find the Y for a given bar. */
    private fun yForBar(samples: List<Pair<Float, Double>>, bar: Double): Float {
        val sorted = samples.sortedBy { it.second }
        if (bar <= sorted.first().second) return sorted.first().first
        if (bar >= sorted.last().second) return sorted.last().first
        for (i in 1 until sorted.size) {
            val (y0, p0) = sorted[i - 1]
            val (y1, p1) = sorted[i]
            if (bar in p0..p1) {
                val t = if (p1 == p0) 0.0 else (bar - p0) / (p1 - p0)
                return (y0 + (y1 - y0) * t).toFloat()
            }
        }
        return sorted.last().first
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

    private companion object {
        const val CALIB_TAG = "DecentEbar"
        const val CALIBRATION_HOP_PX = 100f
        const val KEY_DEV_MODE = "dev_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_FIRST_USE_TUTORIAL_SEEN_V1 = "first_use_tutorial_seen_v1"
    }
}
