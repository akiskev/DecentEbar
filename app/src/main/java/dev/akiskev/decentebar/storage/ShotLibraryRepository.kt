package dev.akiskev.decentebar.storage

import android.content.Context
import android.content.SharedPreferences
import dev.akiskev.decentebar.model.ShotDerivedMetrics
import dev.akiskev.decentebar.model.ShotLibraryEntry
import dev.akiskev.decentebar.model.ShotLog
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Locale
import java.util.UUID

interface ShotLibraryIndexStore {
    fun readIndexJson(): String?
    fun writeIndexJson(json: String)
}

private class SharedPreferencesShotLibraryIndexStore(
    private val prefs: SharedPreferences
) : ShotLibraryIndexStore {
    override fun readIndexJson(): String? = prefs.getString(KEY_INDEX, null)

    override fun writeIndexJson(json: String) {
        prefs.edit().putString(KEY_INDEX, json).commit()
    }

    private companion object {
        const val KEY_INDEX = "shot_library_index_json"
    }
}

data class BestShotUpdate(
    val entries: List<ShotLibraryEntry>,
    val selectedShot: ShotLog?,
    val replaced: ShotLibraryEntry?
)

class ShotLibraryRepository(
    private val rootDir: File,
    private val indexStore: ShotLibraryIndexStore
) {
    constructor(context: Context) : this(
        rootDir = File(context.filesDir, LIBRARY_DIR),
        indexStore = SharedPreferencesShotLibraryIndexStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    fun loadEntries(): List<ShotLibraryEntry> = reconcile()

    fun getShot(shotId: String): ShotLog? = readLog(logFile(shotId))

    fun saveShot(log: ShotLog, nowMs: Long = System.currentTimeMillis()): ShotLibraryEntry {
        ensureRoot()
        val prepared = prepareForLibrary(log, nowMs)
        writeLog(prepared)
        return reconcile().first { it.shotId == prepared.shotId }
    }

    fun deleteShot(shotId: String): List<ShotLibraryEntry> {
        logFile(shotId).delete()
        return reconcile()
    }

    fun setBestForBean(shotId: String, best: Boolean): BestShotUpdate {
        val currentEntries = loadEntries()
        val shot = getShot(shotId)
            ?: return BestShotUpdate(currentEntries, null, null)
        val targetScope = bestScope(shot)
        var replaced: ShotLibraryEntry? = null

        if (best) {
            currentEntries
                .filter { it.shotId != shotId && it.bestForBean && bestScope(it) == targetScope }
                .forEach { entry ->
                    val log = getShot(entry.shotId) ?: return@forEach
                    writeLog(log.copy(bestForBean = false))
                    if (replaced == null) replaced = entry
                }
        }

        val updatedShot = shot.copy(bestForBean = best)
        writeLog(updatedShot)
        val entries = reconcile()
        return BestShotUpdate(
            entries = entries,
            selectedShot = getShot(shotId),
            replaced = replaced
        )
    }

    private fun reconcile(): List<ShotLibraryEntry> {
        ensureRoot()
        val logs = rootDir
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .mapNotNull(::readAndNormalizeFile)
            .sortedByDescending { it.savedAtMs ?: 0L }

        val bestScopes = mutableSetOf<String>()
        val uniqueLogs = logs
            .distinctBy { it.shotId }
            .map { log ->
                if (!log.bestForBean) {
                    log
                } else {
                    val scope = bestScope(log)
                    if (scope.isBlank() || !bestScopes.add(scope)) {
                        log.copy(bestForBean = false)
                    } else {
                        log
                    }
                }
            }

        uniqueLogs.forEach(::writeLog)
        val entries = uniqueLogs
            .mapNotNull(ShotLibraryMetrics::entryFromLog)
            .sortedByDescending { it.savedAtMs }
        indexStore.writeIndexJson(JsonCodec.json.encodeToString(entries))
        return entries
    }

    private fun readAndNormalizeFile(file: File): ShotLog? {
        val log = readLog(file) ?: return null
        val normalizedId = log.shotId
            ?.takeIf(::isSafeShotId)
            ?: file.nameWithoutExtension.takeIf(::isSafeShotId)
            ?: UUID.randomUUID().toString()
        val savedAt = log.savedAtMs
            ?: file.lastModified().takeIf { it > 0L }
            ?: log.startedAtMs
            ?: System.currentTimeMillis()
        val normalized = log.copy(
            shotId = normalizedId,
            savedAtMs = savedAt,
            rating = log.rating?.coerceIn(1, 5)
        )
        val targetFile = logFile(normalizedId)
        if (file.absolutePath != targetFile.absolutePath) {
            writeLog(normalized)
            file.delete()
        } else if (normalized != log) {
            writeLog(normalized)
        }
        return normalized
    }

    private fun prepareForLibrary(log: ShotLog, nowMs: Long): ShotLog {
        val id = log.shotId?.takeIf(::isSafeShotId) ?: UUID.randomUUID().toString()
        return log.copy(
            shotId = id,
            savedAtMs = log.savedAtMs ?: nowMs,
            rating = log.rating?.coerceIn(1, 5)
        )
    }

    private fun readLog(file: File): ShotLog? =
        runCatching {
            ShotLogCodec.decode(file.readText())
        }.getOrNull()

    private fun writeLog(log: ShotLog) {
        val id = log.shotId ?: return
        ensureRoot()
        logFile(id).writeText(ShotLogCodec.encode(log))
    }

    private fun logFile(shotId: String): File = File(rootDir, "$shotId.json")

    private fun ensureRoot() {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    private companion object {
        const val PREFS_NAME = "shot_library"
        const val LIBRARY_DIR = "shot-library"

        fun isSafeShotId(id: String): Boolean =
            id.matches(Regex("[A-Za-z0-9._-]+"))
    }
}

object ShotLibraryMetrics {
    fun normalizeBean(bean: String?): String =
        bean.orEmpty()
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")

    fun finalYieldG(log: ShotLog): Double? =
        ShotDerivedMetrics.finalYieldG(log)

    fun durationMs(log: ShotLog): Long? =
        ShotDerivedMetrics.durationMs(log)

    fun entryFromLog(log: ShotLog): ShotLibraryEntry? {
        val shotId = log.shotId ?: return null
        val savedAtMs = log.savedAtMs ?: return null
        return ShotLibraryEntry(
            shotId = shotId,
            savedAtMs = savedAtMs,
            startedAtMs = log.startedAtMs,
            profileName = log.profileName,
            beansName = log.beansName,
            normalizedBean = normalizeBean(log.beansName),
            doseG = log.doseG,
            grindSetting = log.grindSetting,
            roastLevel = log.roastLevel,
            basket = log.basket,
            rating = log.rating?.coerceIn(1, 5),
            bestForBean = log.bestForBean,
            finalYieldG = finalYieldG(log),
            durationMs = durationMs(log),
            sampleCount = log.samples.size,
            eventCount = log.events.size,
            flowSource = log.flowSource,
            targetYieldG = log.targetYieldG,
            targetTimeS = log.targetTimeS
        )
    }
}

fun bestScope(entry: ShotLibraryEntry): String =
    "${entry.normalizedBean}|${entry.profileName}"

fun bestScope(log: ShotLog): String =
    "${ShotLibraryMetrics.normalizeBean(log.beansName)}|${log.profileName}"
