package dev.akiskev.decentebar.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coordinates here are taken from real `uiautomator` dumps of the e-bar app:
 *  - 3.0.x beta (package com.g472631889.stfbeta): pressure SeekBar [2793,434][3057,1256]
 *  - 3.1.0 release (package com.g472631889.stf): pressure View [2868,274][3002,1226]
 */
class AnchoredPressureLutTest {

    private fun node(
        left: Int, top: Int, right: Int, bottom: Int,
        className: String = "android.view.View",
        scrollable: Boolean = false,
        contentDescription: String? = null
    ) = AccessibilityNodeBounds(
        contentDescription = contentDescription,
        className = className,
        left = left, top = top, right = right, bottom = bottom,
        clickable = true, scrollable = scrollable
    )

    @Test
    fun anchoringTo301SeekBarReproducesBuiltInCalibration() {
        val seekBar = node(2793, 434, 3057, 1256, className = "android.widget.SeekBar")

        val anchored = BuiltInPressureLut.buildAnchoredFrom(seekBar, 3120, 1440)!!
        val builtIn = BuiltInPressureLut.buildFor(3120, 1440)!!

        // Re-anchoring onto the bar it was calibrated against must reproduce the exact taps.
        assertEquals(builtIn.points.size, anchored.points.size)
        builtIn.points.zip(anchored.points).forEach { (expected, actual) ->
            assertEquals(expected.pressureBar, actual.pressureBar, 0.0)
            assertEquals(expected.x, actual.x, 0.5f)
            assertEquals(expected.y, actual.y, 0.5f)
        }
    }

    @Test
    fun findsPressureBarOn310Layout() {
        val nodes = listOf(
            node(0, 0, 3120, 1440, className = "android.widget.ImageView"), // root canvas
            node(2633, 71, 3088, 167, contentDescription = "Start"),         // start button
            node(230, 274, 364, 1226, scrollable = true, contentDescription = "0.0\nml/s\nF.V."), // flow bar (left)
            node(2868, 274, 3002, 1226, scrollable = true, contentDescription = "0.0\nbar\nPr."), // pressure bar (right)
            node(2808, 1237, 2922, 1375, className = "android.widget.ImageView"), // minus button
            node(2948, 1237, 3062, 1375, className = "android.widget.ImageView")  // plus button
        )

        val bar = BuiltInPressureLut.findPressureBar(nodes, 3120, 1440)

        assertNotNull(bar)
        assertEquals(2868, bar!!.left)   // the right-hand bar, not the left flow bar
        assertEquals(3002, bar.right)
    }

    @Test
    fun anchored310LutCoversFullRangeWithBarAtBottom() {
        val pressureBar = node(2868, 274, 3002, 1226, scrollable = true, contentDescription = "0.0\nbar\nPr.")

        val lut = BuiltInPressureLut.buildAnchored(listOf(pressureBar), 3120, 1440)!!
        val zero = lut.points.first { it.pressureBar == 0.0 }
        val nine = lut.points.first { it.pressureBar == 9.0 }
        val twelve = lut.points.first { it.pressureBar == 12.0 }

        // 0 bar sits below 12 bar (higher y); 12 bar stays inside the node, 0 bar is
        // released just past the bottom to force true 0.
        assert(zero.y > twelve.y)
        assert(twelve.y >= 274f)
        assertEquals(2935f, zero.x, 0.5f) // center-x of the live bar

        // Measured calibration table (merged-View bar, node [274,1226], height 952):
        // per-bar fractions from a real sweep; 0 bar pushed past the node bottom.
        assertEquals(1259f, zero.y, 1f)   // 274 + 1.035*952
        assertEquals(283f, twelve.y, 1f)  // 274 + 0.010*952
        assertEquals(520f, nine.y, 1f)    // 274 + 0.258*952
    }

    @Test
    fun returnsNullWhenNoBarPresent() {
        val nodes = listOf(node(2633, 71, 3088, 167, contentDescription = "Start"))
        assertNull(BuiltInPressureLut.buildAnchored(nodes, 3120, 1440))
    }
}
