package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.PressurePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressureLutLookupTest {
    @Test
    fun interpolatedPressurePointInterpolatesBetweenBoundingPoints() {
        val lut = testLut(
            PressurePoint(0.0, 0f, 0f),
            PressurePoint(10.0, 100f, 50f)
        )

        val point = lut.interpolatedPressurePoint(5.0)

        assertEquals(5.0, point?.pressureBar ?: -1.0, 0.0)
        assertEquals(50f, point?.x ?: -1f, 0.0f)
        assertEquals(25f, point?.y ?: -1f, 0.0f)
    }

    @Test
    fun interpolatedPressurePointClampsOutsideRange() {
        val minPoint = PressurePoint(2.0, 20f, 10f)
        val maxPoint = PressurePoint(8.0, 80f, 40f)
        val lut = testLut(minPoint, maxPoint)

        assertEquals(minPoint, lut.interpolatedPressurePoint(1.0))
        assertEquals(maxPoint, lut.interpolatedPressurePoint(9.0))
    }

    @Test
    fun nearestPressurePointUsesLowerPressureForTieBreaks() {
        val lower = PressurePoint(4.0, 40f, 20f)
        val higher = PressurePoint(6.0, 60f, 30f)
        val lut = testLut(higher, lower)

        assertEquals(lower, lut.nearestPressurePoint(5.0))
    }

    @Test
    fun lookupReturnsNullForEmptyLut() {
        val lut = testLut()

        assertNull(lut.interpolatedPressurePoint(5.0))
        assertNull(lut.nearestPressurePoint(5.0))
    }

    private fun testLut(vararg points: PressurePoint): PressureLut {
        return PressureLut(
            name = "test",
            screenWidth = 100,
            screenHeight = 50,
            points = points.toList()
        )
    }
}
