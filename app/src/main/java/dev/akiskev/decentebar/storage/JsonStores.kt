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
        val stored = prefs.getString(KEY_PROFILES, null) ?: return listOf(DefaultProfiles.flow33Dark)
        return runCatching { json.decodeFromString<List<ShotProfile>>(stored) }
            .getOrDefault(listOf(DefaultProfiles.flow33Dark))
            .ifEmpty { listOf(DefaultProfiles.flow33Dark) }
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
