package dev.akiskev.decentebar.storage

import android.content.Context
import dev.akiskev.decentebar.model.DefaultProfiles
import dev.akiskev.decentebar.model.ProfileValidator
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = JsonCodec.json

    fun loadProfiles(): List<ShotProfile> {
        val stored = prefs.getString(KEY_PROFILES, null)
            ?.let { raw -> runCatching { json.decodeFromString<List<ShotProfile>>(raw) }.getOrNull() }
            .orEmpty()
        return seedBuiltIns(stored)
    }

    /**
     * Ensure the bundled [DefaultProfiles.builtIns] are present. Runs once per
     * [DefaultProfiles.BUILT_INS_VERSION] — i.e. on first load after an install or an update
     * that changed the bundle — replacing stored profiles with the same name in place and
     * appending the rest. Between bumps, user edits and deletions of built-ins persist.
     */
    private fun seedBuiltIns(stored: List<ShotProfile>): List<ShotProfile> {
        if (stored.isNotEmpty() && prefs.getInt(KEY_BUILTINS_VERSION, 0) >= DefaultProfiles.BUILT_INS_VERSION) {
            return stored
        }
        val byName = DefaultProfiles.builtIns.associateBy { it.name }
        val merged = stored.map { byName[it.name] ?: it } +
            DefaultProfiles.builtIns.filter { builtIn -> stored.none { it.name == builtIn.name } }
        saveProfiles(merged)
        prefs.edit().putInt(KEY_BUILTINS_VERSION, DefaultProfiles.BUILT_INS_VERSION).commit()
        return merged
    }

    fun saveProfiles(profiles: List<ShotProfile>) {
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(profiles)).commit()
    }

    fun upsert(profile: ShotProfile): List<ShotProfile> {
        val next = loadProfiles()
            .filterNot { it.name == profile.name }
            .plus(profile)
        saveProfiles(next)
        return next
    }

    fun delete(profileName: String): List<ShotProfile> {
        val next = loadProfiles().filterNot { it.name == profileName }
            .ifEmpty { listOf(DefaultProfiles.flow33Dark) }
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
            val errors = ProfileValidator.validate(profile)
            require(errors.isEmpty()) { errors.joinToString("; ") }
            profile
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
