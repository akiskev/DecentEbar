package dev.akiskev.decentebar.ui

internal object LibraryCompareSelection {
    const val MAX_SELECTED_SHOTS = 2

    fun toggle(selected: List<String>, shotId: String): List<String> {
        val current = selected.distinct().take(MAX_SELECTED_SHOTS)
        return if (shotId in current) {
            current - shotId
        } else if (current.size >= MAX_SELECTED_SHOTS) {
            current
        } else {
            current + shotId
        }
    }

    fun prune(selected: List<String>, availableShotIds: Collection<String>): List<String> {
        val available = availableShotIds.toSet()
        return selected.distinct()
            .filter { it in available }
            .take(MAX_SELECTED_SHOTS)
    }

    fun isReady(selected: List<String>): Boolean =
        selected.distinct().size == MAX_SELECTED_SHOTS
}
