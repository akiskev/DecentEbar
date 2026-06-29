package dev.akiskev.decentebar.storage

import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotExportCompatibilityTest {
    @Test
    fun legacyJsonWithoutLibraryFieldsStillDecodes() {
        val legacyJson = """
            {
              "profileName": "Legacy",
              "startedAtMs": 1000,
              "stoppedAtMs": 31000,
              "samples": [],
              "events": [],
              "beansName": "Old Beans",
              "doseG": 18.0
            }
        """.trimIndent()

        val log = ShotLogCodec.decode(legacyJson)

        assertEquals("Legacy", log.profileName)
        assertEquals("Old Beans", log.beansName)
        assertNull(log.shotId)
        assertEquals(false, log.bestForBean)
    }

    @Test
    fun htmlReportIncludesLibraryMetadataAndAttribution() {
        val html = ShotHtmlExporter.export(
            ShotLog(
                profileName = "Profile",
                startedAtMs = 1000L,
                stoppedAtMs = 31_000L,
                samples = listOf(
                    ShotSample(
                        timeMs = 0L,
                        weightG = 0.0,
                        flowGps = 0.0,
                        commandedPressureBar = 3.0,
                        stageName = "Start"
                    )
                ),
                events = emptyList(),
                beansName = "Test Beans",
                roastLevel = "Light",
                basket = "IMS 18",
                targetYieldG = 40.0,
                targetTimeS = 30.0,
                tasteNotes = "sweet, citrus",
                rating = 5,
                bestForBean = true
            )
        )

        assertTrue(html.contains("Made with Decent E-Bar"))
        assertTrue(html.contains("Roast: Light"))
        assertTrue(html.contains("Basket: IMS 18"))
        assertTrue(html.contains("Rating: 5/5"))
        assertTrue(html.contains("sweet, citrus"))
    }

    @Test
    fun compareHtmlReportIncludesEmbeddedLogsAndSharedCharts() {
        val shotA = ShotLog(
            profileName = "Profile A",
            startedAtMs = 1000L,
            stoppedAtMs = 31_000L,
            samples = listOf(
                ShotSample(0L, weightG = 0.0, flowGps = 0.0, commandedPressureBar = 3.0, stageName = "Start"),
                ShotSample(30_000L, weightG = 36.0, flowGps = 1.1, commandedPressureBar = 8.0, stageName = "Main")
            ),
            events = emptyList(),
            beansName = "A Beans",
            targetYieldG = 36.0
        )
        val shotB = ShotLog(
            profileName = "Profile B",
            startedAtMs = 2000L,
            stoppedAtMs = 34_000L,
            samples = listOf(
                ShotSample(0L, weightG = 0.0, flowGps = 0.0, commandedPressureBar = 3.0, stageName = "Start"),
                ShotSample(32_000L, weightG = 40.0, flowGps = 1.3, commandedPressureBar = 8.5, stageName = "Main")
            ),
            events = emptyList(),
            beansName = "B Beans",
            targetYieldG = 40.0
        )

        val html = ShotCompareHtmlExporter.export(shotA, shotB)

        assertTrue(html.contains("Shot Compare"))
        assertTrue(html.contains("shotlog-a-data"))
        assertTrue(html.contains("shotlog-b-data"))
        assertTrue(html.contains("Summary deltas"))
        assertTrue(html.contains("chart('pressure'"))
        assertTrue(html.contains("Made with Decent E-Bar"))
    }
}
