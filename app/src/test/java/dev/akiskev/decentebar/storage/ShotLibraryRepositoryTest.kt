package dev.akiskev.decentebar.storage

import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShotLibraryRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun saveLoadAndDeleteShot() {
        val repo = repo()
        val entry = repo.saveShot(sampleLog(beans = "Ethiopia Guji", dose = 18.0), nowMs = 1000L)

        assertNotNull(repo.getShot(entry.shotId))
        assertEquals(listOf(entry.shotId), repo.loadEntries().map { it.shotId })

        val afterDelete = repo.deleteShot(entry.shotId)
        assertTrue(afterDelete.isEmpty())
        assertNull(repo.getShot(entry.shotId))
    }

    @Test
    fun reconcileDropsMissingFilesAndImportsOrphans() {
        val root = temp.newFolder()
        val store = InMemoryIndexStore()
        val repo = ShotLibraryRepository(root, store)
        val saved = repo.saveShot(sampleLog(beans = "Bean A"), nowMs = 1000L)

        File(root, "${saved.shotId}.json").delete()
        File(root, "orphan.json").writeText(ShotLogCodec.encode(sampleLog(beans = "Bean B")))
        File(root, "corrupt.json").writeText("{ not json")

        val entries = repo.loadEntries()

        assertEquals(listOf("orphan"), entries.map { it.shotId })
        assertEquals("Bean B", entries.first().beansName)
        assertTrue(store.indexJson.orEmpty().contains("orphan"))
        assertFalse(store.indexJson.orEmpty().contains(saved.shotId))
    }

    @Test
    fun legacyLogGetsLibraryIdentityAndDerivedIndexFields() {
        val repo = repo()
        val entry = repo.saveShot(
            sampleLog(
                beans = "Legacy Beans",
                startedAtMs = 10_000L,
                stoppedAtMs = 42_000L,
                finalWeight = 36.5
            ),
            nowMs = 50_000L
        )

        assertNotNull(entry.shotId)
        assertEquals(50_000L, entry.savedAtMs)
        assertEquals(36.5, entry.finalYieldG!!, 0.0001)
        assertEquals(32_000L, entry.durationMs)
        assertEquals(2, entry.sampleCount)
    }

    @Test
    fun bestForBeanIsUniqueWithinBeanAndProfile() {
        val repo = repo()
        val first = repo.saveShot(sampleLog(beans = " Ethiopia   Guji ", profile = "Lever"), nowMs = 1000L)
        val second = repo.saveShot(sampleLog(beans = "ethiopia guji", profile = "Lever"), nowMs = 2000L)

        repo.setBestForBean(first.shotId, true)
        val update = repo.setBestForBean(second.shotId, true)

        assertEquals(first.shotId, update.replaced?.shotId)
        assertFalse(repo.getShot(first.shotId)!!.bestForBean)
        assertTrue(repo.getShot(second.shotId)!!.bestForBean)
    }

    private fun repo(): ShotLibraryRepository =
        ShotLibraryRepository(temp.newFolder(), InMemoryIndexStore())

    private fun sampleLog(
        beans: String,
        profile: String = "Profile",
        dose: Double? = 18.0,
        startedAtMs: Long? = 1_000L,
        stoppedAtMs: Long? = 31_000L,
        finalWeight: Double = 40.0
    ): ShotLog = ShotLog(
        profileName = profile,
        startedAtMs = startedAtMs,
        stoppedAtMs = stoppedAtMs,
        samples = listOf(
            ShotSample(
                timeMs = 0L,
                weightG = 0.0,
                flowGps = 0.0,
                commandedPressureBar = 3.0,
                stageName = "Preinfusion"
            ),
            ShotSample(
                timeMs = 30_000L,
                weightG = finalWeight,
                flowGps = 1.2,
                commandedPressureBar = 8.0,
                stageName = "Main"
            )
        ),
        events = emptyList(),
        beansName = beans,
        doseG = dose
    )

    private class InMemoryIndexStore : ShotLibraryIndexStore {
        var indexJson: String? = null

        override fun readIndexJson(): String? = indexJson

        override fun writeIndexJson(json: String) {
            indexJson = json
        }
    }
}
