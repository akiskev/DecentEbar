package dev.akiskev.decentebar.storage

import android.content.Context
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.ProfileConstraints
import dev.akiskev.decentebar.model.ProfileValidator
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotProfile
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

    fun exportProfile(profile: ShotProfile): String = json.encodeToString(profile)

    fun importProfile(rawJson: String): Result<ShotProfile> {
        return runCatching {
            val profile = json.decodeFromString<ShotProfile>(rawJson)
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
