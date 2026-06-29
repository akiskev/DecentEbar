package dev.akiskev.decentebar.model

data class ShotMetricPoint(
    val timeMs: Long,
    val value: Double
)

enum class ShotMetric {
    PRESSURE,
    FLOW,
    WEIGHT
}

object ShotDerivedMetrics {
    fun finalYieldG(log: ShotLog): Double? =
        log.samples.lastOrNull()?.weightG

    fun durationMs(log: ShotLog): Long? {
        val start = log.startedAtMs
        val stop = log.stoppedAtMs
        if (start != null && stop != null && stop >= start) return stop - start
        return log.samples.lastOrNull()?.timeMs
    }

    fun firstDropMs(log: ShotLog): Long? {
        val eventTime = log.events.firstOrNull { it.type == ShotEventType.FIRST_DROP }?.timeMs
            ?: return null
        return normalizeTimeMs(log, eventTime)
    }

    fun normalizedSeries(log: ShotLog, metric: ShotMetric): List<ShotMetricPoint> {
        val ordered = log.samples.sortedBy { it.timeMs }
        val firstMs = ordered.firstOrNull()?.timeMs ?: 0L
        return ordered.mapNotNull { sample ->
            val value = when (metric) {
                ShotMetric.PRESSURE -> sample.commandedPressureBar ?: return@mapNotNull null
                ShotMetric.FLOW -> sample.flowGps
                ShotMetric.WEIGHT -> sample.weightG
            }
            ShotMetricPoint(
                timeMs = (sample.timeMs - firstMs).coerceAtLeast(0L),
                value = value
            )
        }
    }

    private fun normalizeTimeMs(log: ShotLog, timeMs: Long): Long {
        val ordered = log.samples.sortedBy { it.timeMs }
        val firstMs = ordered.firstOrNull()?.timeMs ?: return timeMs
        val lastMs = ordered.lastOrNull()?.timeMs ?: return timeMs
        return if (firstMs > 0L && timeMs in firstMs..lastMs) {
            timeMs - firstMs
        } else {
            timeMs
        }
    }
}
