package dev.akiskev.decentebar.storage

import android.content.Context
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.ExitCondition
import dev.akiskev.decentebar.model.FlowCurveType
import dev.akiskev.decentebar.model.ProfileConstraints
import dev.akiskev.decentebar.model.ProfileStage
import dev.akiskev.decentebar.model.ProfileValidator
import dev.akiskev.decentebar.model.PressureCurveAxis
import dev.akiskev.decentebar.model.PressureCurveConfig
import dev.akiskev.decentebar.model.StageType
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotProfile
import dev.akiskev.decentebar.model.YieldTimeTrajectoryConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = JsonCodec.json

    fun loadProfiles(): List<ShotProfile> {
        val hasStoredProfiles = prefs.contains(KEY_PROFILES)
        val stored = prefs.getString(KEY_PROFILES, null)
            ?.let { raw -> runCatching { json.decodeFromString<List<ShotProfile>>(raw) }.getOrNull() }
            ?.map(ProfileConstraints::normalize)
            ?.filter { it.stages.isNotEmpty() }
            .orEmpty()
        return seedBuiltIns(stored, hasStoredProfiles)
    }

    /**
     * Ensure the bundled [DefaultProfiles.builtIns] are present. Runs once per
     * [DefaultProfiles.BUILT_INS_VERSION] — i.e. on first load after an install or an update
     * that changed the bundle — replacing stored profiles with the same name in place and
     * appending the rest. Between bumps, user edits and deletions of built-ins persist.
     */
    private fun seedBuiltIns(stored: List<ShotProfile>, hasStoredProfiles: Boolean): List<ShotProfile> {
        val builtIns = DefaultProfiles.builtIns
        if (builtIns.isEmpty()) return stored
        if (hasStoredProfiles && prefs.getInt(KEY_BUILTINS_VERSION, 0) >= DefaultProfiles.BUILT_INS_VERSION) {
            return stored
        }
        val byName = builtIns.associateBy { it.name }
        val merged = stored.map { byName[it.name] ?: it } +
            builtIns.filter { builtIn -> stored.none { it.name == builtIn.name } }
        saveProfiles(merged)
        prefs.edit().putInt(KEY_BUILTINS_VERSION, DefaultProfiles.BUILT_INS_VERSION).commit()
        return merged
    }

    fun saveProfiles(profiles: List<ShotProfile>) {
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(profiles)).commit()
    }

    fun upsert(profile: ShotProfile): List<ShotProfile> {
        val normalized = ProfileConstraints.normalize(profile)
        val next = loadProfiles()
            .filterNot { it.name == normalized.name }
            .plus(normalized)
        saveProfiles(next)
        return next
    }

    fun delete(profileName: String): List<ShotProfile> {
        val next = loadProfiles().filterNot { it.name == profileName }
        saveProfiles(next)
        return next
    }

    fun duplicate(profile: ShotProfile): List<ShotProfile> {
        val existingNames = loadProfiles().map { it.name }.toSet()
        val baseName = "${profile.name} Copy"
        val newName = generateSequence(0) { it + 1 }
            .map { index -> if (index == 0) baseName else "$baseName $index" }
            .first { it !in existingNames }
        return upsert(profile.copy(name = newName))
    }

    fun exportProfile(profile: ShotProfile): String = ProfileJsonCodec.encode(profile)

    fun importProfile(rawJson: String): Result<ShotProfile> {
        return runCatching {
            val profile = ProfileJsonCodec.decode(rawJson)
            val normalized = ProfileConstraints.normalize(profile)
            val errors = ProfileValidator.validate(normalized)
            require(errors.isEmpty()) { errors.joinToString("; ") }
            normalized
        }
    }

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_BUILTINS_VERSION = "builtins_seeded_version"
    }
}

object ShotLogCodec {
    fun encode(log: ShotLog): String = JsonCodec.json.encodeToString(log)
    fun decode(json: String): ShotLog = JsonCodec.json.decodeFromString(json)
}

object JsonCodec {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

object ProfileJsonCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val compactJson = Json(JsonCodec.json) {
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(profile: ShotProfile): String =
        compactJson.encodeToString(compact(ProfileConstraints.normalize(profile)))

    fun decode(rawJson: String): ShotProfile = JsonCodec.json.decodeFromString(rawJson)

    private fun compact(profile: ShotProfile): CompactProfile =
        CompactProfile(
            schemaVersion = profile.schemaVersion,
            name = profile.name,
            targetWeightG = profile.targetWeightG,
            stopOffsetG = profile.stopOffsetG,
            stages = profile.stages.mapNotNull(::compactStage)
        )

    private fun compactStage(stage: ProfileStage): ProfileStage? {
        val exit = compactExit(stage.exit)
        val safety = stage.safety
        return when (stage.type) {
            StageType.FIXED_PRESSURE -> ProfileStage(
                name = stage.name,
                type = StageType.FIXED_PRESSURE,
                fixedPressureBar = stage.fixedPressureBar,
                exit = exit,
                safety = safety
            )
            StageType.FLOW_LIMITED_PRESSURE -> ProfileStage(
                name = stage.name,
                type = StageType.FLOW_LIMITED_PRESSURE,
                pressureCapBar = stage.pressureCapBar,
                targetFlowGps = stage.targetFlowGps,
                flowDeadbandGps = stage.flowDeadbandGps.unlessDefault(DEFAULT_FLOW_DEADBAND_GPS),
                pressureStepBar = stage.pressureStepBar,
                correctionIntervalMs = stage.correctionIntervalMs,
                pressureStepMultiplierMax = stage.pressureStepMultiplierMax
                    .unlessDefault(DEFAULT_PRESSURE_STEP_MULTIPLIER_MAX),
                feedForward = stage.feedForward,
                exit = exit,
                safety = safety
            )
            StageType.WEIGHT_BASED_PRESSURE_RAMP -> ProfileStage(
                name = stage.name,
                type = StageType.WEIGHT_BASED_PRESSURE_RAMP,
                rampStartPressureBar = stage.rampStartPressureBar,
                rampEndPressureBar = stage.rampEndPressureBar,
                rampStartWeightG = stage.rampStartWeightG,
                rampEndWeightG = stage.rampEndWeightG,
                exit = exit,
                safety = safety
            )
            StageType.TIME_BASED_PRESSURE_RAMP -> ProfileStage(
                name = stage.name,
                type = StageType.TIME_BASED_PRESSURE_RAMP,
                rampStartPressureBar = stage.rampStartPressureBar,
                rampEndPressureBar = stage.rampEndPressureBar,
                rampDurationMs = stage.rampDurationMs,
                exit = exit,
                safety = safety
            )
            StageType.YIELD_TIME_TRAJECTORY -> ProfileStage(
                name = stage.name,
                type = StageType.YIELD_TIME_TRAJECTORY,
                yieldTime = stage.yieldTime?.let(::compactYieldTime),
                exit = exit,
                safety = safety
            )
            StageType.PRESSURE_CURVE -> ProfileStage(
                name = stage.name,
                type = StageType.PRESSURE_CURVE,
                pressureCurve = stage.pressureCurve?.let(::compactPressureCurve),
                exit = exit,
                safety = safety
            )
            StageType.STOP -> null
        }
    }

    private fun compactExit(exit: ExitCondition): ExitCondition =
        ExitCondition(
            mode = exit.mode,
            weightGte = exit.weightGte,
            stageTimeGteMs = exit.stageTimeGteMs,
            flowGte = exit.flowGte,
            flowLte = exit.flowLte,
            firstDropDetected = exit.firstDropDetected
        )

    private fun compactYieldTime(config: YieldTimeTrajectoryConfig): YieldTimeTrajectoryConfig {
        val defaults = YieldTimeTrajectoryConfig()
        val shapeScoped = when (config.curveType) {
            FlowCurveType.FLAT -> config.copy(
                startFlowGps = defaults.startFlowGps,
                peakFlowGps = defaults.peakFlowGps,
                endFlowGps = defaults.endFlowGps,
                peakAtPct = defaults.peakAtPct,
                customPoints = emptyList()
            )
            FlowCurveType.DECLINING -> config.copy(
                peakFlowGps = defaults.peakFlowGps,
                peakAtPct = defaults.peakAtPct,
                customPoints = emptyList()
            )
            FlowCurveType.RAMP_THEN_DECLINE,
            FlowCurveType.BLOOMING_DECLINE -> config.copy(customPoints = emptyList())
            FlowCurveType.CUSTOM_POINTS -> config.copy(
                startFlowGps = defaults.startFlowGps,
                peakFlowGps = defaults.peakFlowGps,
                endFlowGps = defaults.endFlowGps,
                peakAtPct = defaults.peakAtPct
            )
        }
        return shapeScoped.copy(
            targetYieldG = shapeScoped.targetYieldG.snapDefault(defaults.targetYieldG),
            targetDurationS = shapeScoped.targetDurationS.snapDefault(defaults.targetDurationS),
            startFlowGps = shapeScoped.startFlowGps.snapDefault(defaults.startFlowGps),
            peakFlowGps = shapeScoped.peakFlowGps.snapDefault(defaults.peakFlowGps),
            endFlowGps = shapeScoped.endFlowGps.snapDefault(defaults.endFlowGps),
            peakAtPct = shapeScoped.peakAtPct.snapDefault(defaults.peakAtPct),
            maxPressureBar = shapeScoped.maxPressureBar.snapDefault(defaults.maxPressureBar),
            minPressureBar = shapeScoped.minPressureBar.snapDefault(defaults.minPressureBar),
            minExtractionPressureBar = shapeScoped.minExtractionPressureBar
                .snapDefault(defaults.minExtractionPressureBar),
            maxFlowGps = shapeScoped.maxFlowGps.snapDefault(defaults.maxFlowGps),
            minFlowGps = shapeScoped.minFlowGps.snapDefault(defaults.minFlowGps),
            maxPressureRiseBarPerS = shapeScoped.maxPressureRiseBarPerS
                .snapDefault(defaults.maxPressureRiseBarPerS),
            maxPressureFallBarPerS = shapeScoped.maxPressureFallBarPerS
                .snapDefault(defaults.maxPressureFallBarPerS),
            correctionStrength = shapeScoped.correctionStrength.snapDefault(defaults.correctionStrength),
            lateShotCorrectionLimitS = shapeScoped.lateShotCorrectionLimitS
                .snapDefault(defaults.lateShotCorrectionLimitS),
            preInfusionPressureBar = shapeScoped.preInfusionPressureBar
                .snapDefault(defaults.preInfusionPressureBar),
            preInfusionMaxS = shapeScoped.preInfusionMaxS.snapDefault(defaults.preInfusionMaxS)
        )
    }

    private fun compactPressureCurve(config: PressureCurveConfig): PressureCurveConfig {
        val defaults = PressureCurveConfig()
        val axisScoped = when (config.axis) {
            PressureCurveAxis.TIME -> config.copy(maxWeightG = defaults.maxWeightG)
            PressureCurveAxis.WEIGHT -> config.copy(durationS = defaults.durationS)
        }
        return axisScoped.copy(
            durationS = axisScoped.durationS.snapDefault(defaults.durationS),
            maxWeightG = axisScoped.maxWeightG.snapDefault(defaults.maxWeightG),
            maxPressureBar = axisScoped.maxPressureBar.snapDefault(defaults.maxPressureBar),
            minPressureBar = axisScoped.minPressureBar.snapDefault(defaults.minPressureBar)
        )
    }

    private fun Double?.unlessDefault(default: Double): Double? =
        this?.takeUnless { it.isCloseTo(default) }

    private fun Double.snapDefault(default: Double): Double =
        if (isCloseTo(default)) default else this

    private fun Double.isCloseTo(other: Double): Boolean =
        kotlin.math.abs(this - other) <= DEFAULT_EPS

    @Serializable
    private data class CompactProfile(
        val schemaVersion: Int,
        val name: String,
        val targetWeightG: Double,
        val stopOffsetG: Double,
        val stages: List<ProfileStage>
    )

    private const val DEFAULT_FLOW_DEADBAND_GPS = 0.1
    private const val DEFAULT_PRESSURE_STEP_MULTIPLIER_MAX = 8.0
    private const val DEFAULT_EPS = 1e-6
}
