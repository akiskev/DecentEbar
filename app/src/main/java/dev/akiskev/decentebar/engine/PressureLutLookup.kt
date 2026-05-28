package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.PressurePoint
import kotlin.math.abs
import kotlin.math.roundToInt

fun PressureLut.nearestPressurePoint(requestedPressureBar: Double): PressurePoint? {
    return points.minWithOrNull(
        compareBy<PressurePoint> { abs(it.pressureBar - requestedPressureBar) }
            .thenBy { it.pressureBar }
    )
}

fun PressureLut.interpolatedPressurePoint(requestedPressureBar: Double): PressurePoint? {
    val sortedPoints = points.sortedBy { it.pressureBar }
    if (sortedPoints.isEmpty()) return null
    if (sortedPoints.size == 1) return sortedPoints[0]

    val first = sortedPoints.first()
    if (requestedPressureBar <= first.pressureBar) return first

    val last = sortedPoints.last()
    if (requestedPressureBar >= last.pressureBar) return last

    for (index in 0 until sortedPoints.size - 1) {
        val start = sortedPoints[index]
        val end = sortedPoints[index + 1]
        if (requestedPressureBar <= end.pressureBar) {
            val range = end.pressureBar - start.pressureBar
            if (range == 0.0) return start

            val progress = (requestedPressureBar - start.pressureBar) / range
            return PressurePoint(
                pressureBar = requestedPressureBar,
                x = (start.x + progress * (end.x - start.x)).roundToInt().toFloat(),
                y = (start.y + progress * (end.y - start.y)).roundToInt().toFloat()
            )
        }
    }

    return last
}
