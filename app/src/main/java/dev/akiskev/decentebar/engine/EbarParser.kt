package dev.akiskev.decentebar.engine

import dev.akiskev.decentebar.model.AccessibilityNodeBounds
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.isEbarPackage
import java.util.Locale
import kotlin.math.roundToInt

object EbarParser {
    private val liveWeightRegex = Regex(
        pattern = """(?i)\bWt\.?\s*(-?\d{1,3})(?:\s*\.\s*(\d{1,2}))?\s*g\b"""
    )

    fun parseSnapshot(
        activePackage: String?,
        rawDescriptions: List<String>,
        rawTexts: List<String>,
        screenWidth: Int,
        screenHeight: Int,
        timestampMs: Long,
        maxWeightG: Double,
        nodes: List<AccessibilityNodeBounds> = emptyList()
    ): EbarSnapshot {
        val allValues = (rawDescriptions + rawTexts).mapNotNull { it.takeIf(String::isNotBlank) }
        val normalizedControls = allValues.map { normalizeControlText(it) }
        val isForeground = isEbarPackage(activePackage)
        val orientation = when {
            screenWidth > screenHeight -> "landscape"
            screenHeight > screenWidth -> "portrait"
            else -> "unknown"
        }

        return EbarSnapshot(
            timestampMs = timestampMs,
            isForeground = isForeground,
            activePackage = activePackage,
            hasStart = normalizedControls.any { it == "start" },
            hasStop = normalizedControls.any { it == "stop" },
            hasWeigh = normalizedControls.any { it == "weigh" },
            hasPressurePriority = normalizedControls.any { it == "pressure priority" },
            hasFlowRatePriority = normalizedControls.any { it == "flow rate priority" },
            hasPressureLabel = normalizedControls.any { it == "pr.(bar)" || it == "pr. bar" },
            weightG = parseWeight(allValues, maxWeightG),
            rawDescriptions = rawDescriptions,
            rawTexts = rawTexts,
            nodes = nodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            orientation = orientation
        )
    }

    fun parseWeight(rawValues: List<String>, maxWeightG: Double = 150.0): Double? {
        val candidates = mutableListOf<Double>()

        rawValues.forEach { value ->
            parseWeightFromSingleValue(value, maxWeightG)?.let(candidates::add)
        }

        parseWeightFromTokenStream(rawValues, maxWeightG)?.let(candidates::add)

        return candidates.lastOrNull()
    }

    private fun parseWeightFromSingleValue(value: String, maxWeightG: Double): Double? {
        val normalized = value.replace(',', '.')
        return liveWeightRegex.findAll(normalized)
            .mapNotNull { match ->
                val integerPart = match.groupValues[1]
                val decimalPart = match.groupValues[2].takeIf(String::isNotBlank)
                val numberText = if (decimalPart == null) integerPart else "$integerPart.$decimalPart"
                numberText.toDoubleOrNull()?.takeIf { it in 0.0..maxWeightG }
            }
            .lastOrNull()
    }

    private fun parseWeightFromTokenStream(rawValues: List<String>, maxWeightG: Double): Double? {
        val tokens = rawValues
            .flatMap { value ->
                value.replace(',', '.')
                    .replace("\r", "\n")
                    .split(Regex("""\s+"""))
                    .flatMap { splitPunctuation(it) }
            }
            .filter { it.isNotBlank() }

        val candidates = mutableListOf<Double>()
        tokens.forEachIndexed { index, token ->
            if (normalizeControlText(token) != "wt.") return@forEachIndexed

            val window = tokens.drop(index + 1).take(5)
            val gramsIndex = window.indexOfFirst { normalizeControlText(it) == "g" }
            if (gramsIndex < 0) return@forEachIndexed

            parseNumberTokens(window.take(gramsIndex))?.takeIf { it in 0.0..maxWeightG }?.let(candidates::add)
        }

        return candidates.lastOrNull()
    }

    private fun splitPunctuation(token: String): List<String> {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed == ".") return listOf(trimmed)
        return trimmed
            .replace("Wt.", "Wt. ")
            .replace("wt.", "wt. ")
            .replace("g", " g ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
    }

    private fun parseNumberTokens(tokens: List<String>): Double? {
        val cleaned = tokens.map { it.trim() }.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return null

        val dotIndex = cleaned.indexOf(".")

        val (integerPart, decimalPart) = if (dotIndex != -1) {
            cleaned.subList(0, dotIndex).joinToString("") to
                    cleaned.subList(dotIndex + 1, cleaned.size).joinToString("")
        } else {
            cleaned.joinToString("") to null
        }

        val numberText = if (decimalPart.isNullOrEmpty()) {
            integerPart
        } else {
            "$integerPart.$decimalPart"
        }

        return numberText.replace(',', '.').toDoubleOrNull()
    }

    private fun normalizeControlText(value: String): String {
        return value.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.US)
    }

    fun formatWeight(value: Double?): String = value?.let { "${roundToTenth(it)} g" } ?: "--"

    private fun roundToTenth(value: Double): String {
        val rounded = (value * 10.0).roundToInt() / 10.0
        return String.format(Locale.US, "%.1f", rounded)
    }
}
