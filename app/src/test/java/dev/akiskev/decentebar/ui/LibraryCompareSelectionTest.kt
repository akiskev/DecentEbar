package dev.akiskev.decentebar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCompareSelectionTest {
    @Test
    fun toggleAddsAndRemovesShotIds() {
        val selected = LibraryCompareSelection.toggle(emptyList(), "a")

        assertEquals(listOf("a"), selected)
        assertEquals(emptyList<String>(), LibraryCompareSelection.toggle(selected, "a"))
    }

    @Test
    fun toggleAllowsExactlyTwoSelections() {
        val selected = listOf("a", "b")

        assertEquals(selected, LibraryCompareSelection.toggle(selected, "c"))
        assertTrue(LibraryCompareSelection.isReady(selected))
        assertFalse(LibraryCompareSelection.isReady(listOf("a")))
    }

    @Test
    fun pruneDropsUnavailableIdsAndKeepsSelectionCap() {
        val pruned = LibraryCompareSelection.prune(
            selected = listOf("a", "b", "c"),
            availableShotIds = listOf("b", "c")
        )

        assertEquals(listOf("b", "c"), pruned)
    }
}
