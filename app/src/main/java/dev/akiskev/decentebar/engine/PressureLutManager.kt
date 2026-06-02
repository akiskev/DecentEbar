package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.LutValidationResult
import dev.akiskev.decentebar.model.isEbarPackage
import dev.akiskev.decentebar.model.PressureCommandResult
import dev.akiskev.decentebar.model.PressureLut
import dev.akiskev.decentebar.model.PressurePoint
import dev.akiskev.decentebar.model.SafetyConfig
import dev.akiskev.decentebar.model.ScreenSpec
import dev.akiskev.decentebar.util.formatDecimals
import kotlin.math.abs

class PressureLutManager(
    private val safetyConfig: SafetyConfig = SafetyConfig()
) {
    private var lastPressureBar: Double? = null
    private var lastCommandTimeMs: Long? = null

    fun resetThrottle() {
        lastPressureBar = null
        lastCommandTimeMs = null
    }

    fun validate(
        lut: PressureLut?,
        screen: ScreenSpec,
        requireForegroundPackage: Boolean = true
    ): LutValidationResult {
        if (lut == null) return LutValidationResult.Missing

        val messages = buildList {
            if (lut.points.isEmpty()) add("Pressure LUT has no points")
            if (lut.screenWidth != screen.width) add("LUT width ${lut.screenWidth} != screen width ${screen.width}")
            if (lut.screenHeight != screen.height) add("LUT height ${lut.screenHeight} != screen height ${screen.height}")
            if (!lut.orientation.equals(screen.orientation, ignoreCase = true)) {
                add("LUT orientation ${lut.orientation} != ${screen.orientation}")
            }
            if (!isEbarPackage(lut.packageName)) add("LUT package ${lut.packageName} is not an e-bar package")
            if (requireForegroundPackage && !isEbarPackage(screen.packageName)) {
                add("Active package ${screen.packageName ?: "unknown"} is not an e-bar package")
            }

            val minPoint = lut.points.minOfOrNull { it.pressureBar }
            val maxPoint = lut.points.maxOfOrNull { it.pressureBar }
            if (minPoint == null || maxPoint == null) {
                add("Pressure LUT has no usable pressure range")
            } else {
                if (minPoint > safetyConfig.minPressureBar) {
                    add("LUT does not cover minimum ${safetyConfig.minPressureBar} bar")
                }
                if (maxPoint < safetyConfig.maxPressureBar) {
                    add("LUT does not cover maximum ${safetyConfig.maxPressureBar} bar")
                }
            }
        }

        return LutValidationResult(messages.isEmpty(), messages)
    }

    fun nearestPoint(lut: PressureLut, requestedPressureBar: Double): PressurePoint? {
        return lut.nearestPressurePoint(requestedPressureBar)
    }

    fun interpolatedPoint(lut: PressureLut, requestedPressureBar: Double): PressurePoint? {
        return lut.interpolatedPressurePoint(requestedPressureBar)
    }

    fun requestPressure(
        lut: PressureLut?,
        screen: ScreenSpec,
        requestedPressureBar: Double,
        nowMs: Long,
        force: Boolean = false,
        currentActualBar: Double? = null
    ): PressureCommandResult {
        val validation = validate(lut, screen, requireForegroundPackage = true)
        if (!validation.isValid || lut == null) {
            return PressureCommandResult(false, validation.displayText)
        }

        if (requestedPressureBar !in safetyConfig.minPressureBar..safetyConfig.maxPressureBar) {
            return PressureCommandResult(
                accepted = false,
                message = "Rejected ${requestedPressureBar.formatBar()} bar outside ${safetyConfig.minPressureBar.formatBar()}-${safetyConfig.maxPressureBar.formatBar()} bar"
            )
        }

        // When the live reading is supplied, run closed-loop: keep re-sliding while the
        // bar is off target (this also recovers slides the e-bar drops just after Start),
        // and stop once within the wider closed-loop deadband so it doesn't oscillate on
        // landing jitter. Otherwise deadband against the last commanded value.
        val baseline = currentActualBar ?: lastPressureBar
        val deadband = if (currentActualBar != null) safetyConfig.closedLoopDeadbandBar else safetyConfig.minPressureDeltaBar
        if (!force && baseline != null && abs(requestedPressureBar - baseline) < deadband) {
            return PressureCommandResult(
                accepted = false,
                message = "Suppressed pressure delta below ${deadband.formatBar()} bar",
                pressureBar = baseline
            )
        }

        val lastCommand = lastCommandTimeMs
        val elapsedMs = if (lastCommand == null) Long.MAX_VALUE else nowMs - lastCommand
        if (!force && elapsedMs < safetyConfig.pressureCommandIntervalMs) {
            return PressureCommandResult(
                accepted = false,
                message = "Suppressed pressure command throttle (${elapsedMs}ms)",
                pressureBar = baseline
            )
        }

        val point = lut.interpolatedPressurePoint(requestedPressureBar)
            ?: return PressureCommandResult(false, "No nearest LUT coordinate found")

        lastPressureBar = requestedPressureBar
        lastCommandTimeMs = nowMs
        return PressureCommandResult(
            accepted = true,
            message = "Tap ${point.pressureBar.formatBar()} bar at ${point.x.toInt()},${point.y.toInt()}",
            pressureBar = requestedPressureBar,
            point = point
        )
    }

    private fun Double.formatBar(): String = formatDecimals(2)
}
