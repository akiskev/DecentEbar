package dev.akiskev.decentebar.storage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import dev.akiskev.decentebar.model.ShotDerivedMetrics
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotMetric
import dev.akiskev.decentebar.model.ShotMetricPoint
import dev.akiskev.decentebar.model.ShotSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ShotCompareRenderer(
    private val shotA: ShotLog,
    private val shotB: ShotLog,
    width: Int,
    height: Int
) {
    private val w = width.coerceAtLeast(1)
    private val h = height.coerceAtLeast(1)
    private val base = min(w, h).toFloat().coerceAtLeast(1f)
    private val scales = ShotCompareScaleCalculator.calculate(shotA, shotB)

    private data class Chart(
        val title: String,
        val metric: ShotMetric,
        val maxY: Double,
        val unit: String,
        val color: Int
    )

    private object CompareColors {
        val background = Color.rgb(10, 12, 14)
        val panel = Color.rgb(16, 19, 22)
        val chart = Color.rgb(14, 17, 20)
        val flow = Color.rgb(240, 179, 74)
        val pressure = Color.rgb(88, 169, 255)
        val weight = Color.rgb(83, 207, 151)
        val target = Color.rgb(236, 204, 121)
        val correctedTarget = Color.rgb(255, 142, 103)
        val firstDropA = Color.rgb(242, 93, 93)
        val firstDropB = Color.rgb(255, 161, 122)

        fun textHigh() = Color.argb(242, 247, 242, 232)
        fun textMed() = Color.argb(184, 247, 242, 232)
        fun textMuted() = Color.argb(132, 247, 242, 232)
        fun grid() = Color.argb(26, 247, 242, 232)
        fun axis() = Color.argb(86, 247, 242, 232)
        fun border() = Color.argb(62, 247, 242, 232)
    }

    private val bgPaint = Paint().apply { color = CompareColors.background; style = Paint.Style.FILL }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CompareColors.panel; style = Paint.Style.FILL }
    private val chartPaint = Paint().apply { color = CompareColors.chart; style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CompareColors.border()
        strokeWidth = 1.25f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint().apply {
        color = CompareColors.grid()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint().apply {
        color = CompareColors.axis()
        strokeWidth = 1.25f
        style = Paint.Style.STROKE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CompareColors.textHigh()
        textSize = (base * 0.040f).coerceIn(18f, 36f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CompareColors.textHigh()
        textSize = (base * 0.028f).coerceIn(13f, 24f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CompareColors.textMed()
        textSize = (base * 0.022f).coerceIn(11f, 20f)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CompareColors.textMuted()
        textSize = (base * 0.018f).coerceIn(9f, 16f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CompareColors.textMuted()
        textSize = (base * 0.017f).coerceIn(9f, 15f)
        typeface = Typeface.MONOSPACE
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = (base * 0.004f).coerceIn(2f, 5f)
    }
    private val signaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 247, 242, 232)
        textSize = (base * 0.014f).coerceIn(8f, 13f)
        textAlign = Paint.Align.RIGHT
    }

    fun render(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val padX = (w * 0.035f).coerceAtLeast(18f)
        val padY = (h * 0.030f).coerceAtLeast(12f)
        val gap = (base * 0.023f).coerceIn(10f, 24f)
        val metadataH = (base * 0.175f).coerceIn(88f, 150f).coerceAtMost(h * 0.26f)
        val metadataRect = RectF(padX, padY, w - padX, padY + metadataH)
        drawMetadataBand(canvas, metadataRect)

        val chartTop = metadataRect.bottom + gap
        val footerH = (base * 0.030f).coerceIn(14f, 26f)
        val availableChartH = (h - chartTop - padY - footerH - gap * 2f).coerceAtLeast(140f)
        val chartH = availableChartH / 3f
        val charts = listOf(
            Chart("Pressure", ShotMetric.PRESSURE, scales.pressureMax, "bar", CompareColors.pressure),
            Chart("Flow", ShotMetric.FLOW, scales.flowMax, "g/s", CompareColors.flow),
            Chart("Weight", ShotMetric.WEIGHT, scales.weightMax, "g", CompareColors.weight)
        )

        charts.forEachIndexed { index, chart ->
            val top = chartTop + index * (chartH + gap)
            val rect = RectF(padX, top, w - padX, top + chartH)
            drawChart(canvas, rect, chart)
        }

        canvas.drawText("Made with Decent E-Bar", w - padX, h - padY * 0.42f, signaturePaint)
    }

    private fun drawMetadataBand(canvas: Canvas, rect: RectF) {
        canvas.drawRoundRect(rect, 8f, 8f, panelPaint)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        val inner = (base * 0.024f).coerceIn(10f, 24f)
        val titleBaseline = rect.top + inner + titlePaint.textSize
        titlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Shot Compare", rect.left + inner, titleBaseline, titlePaint)

        val legendY = titleBaseline - titlePaint.textSize * 0.34f
        val legendRight = rect.right - inner
        drawLegendSwatch(canvas, legendRight - base * 0.190f, legendY, "A solid", CompareColors.textMed(), false)
        drawLegendSwatch(canvas, legendRight - base * 0.090f, legendY, "B dashed", CompareColors.textMed(), true)

        val columnTop = titleBaseline + (base * 0.030f).coerceIn(12f, 24f)
        val columnGap = inner * 1.2f
        val columnW = (rect.width() - inner * 2f - columnGap) / 2f
        drawShotSummary(canvas, "A", shotA, rect.left + inner, columnTop, columnW, false)
        drawShotSummary(canvas, "B", shotB, rect.left + inner + columnW + columnGap, columnTop, columnW, true)
    }

    private fun drawShotSummary(
        canvas: Canvas,
        prefix: String,
        log: ShotLog,
        x: Float,
        top: Float,
        maxWidth: Float,
        dashed: Boolean
    ) {
        val swatchY = top + bodyPaint.textSize * 0.38f
        drawLegendSwatch(canvas, x, swatchY, prefix, CompareColors.textHigh(), dashed)
        val textX = x + (base * 0.055f).coerceIn(26f, 46f)
        val textWidth = (maxWidth - (textX - x)).coerceAtLeast(24f)
        bodyPaint.color = CompareColors.textHigh()
        bodyPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        bodyPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(fitText(primaryLabel(log), bodyPaint, textWidth), textX, top + bodyPaint.textSize * 0.75f, bodyPaint)

        bodyPaint.color = CompareColors.textMed()
        bodyPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(fitText(secondaryLabel(log), bodyPaint, textWidth), textX, top + bodyPaint.textSize * 2.05f, bodyPaint)
    }

    private fun drawLegendSwatch(canvas: Canvas, x: Float, y: Float, label: String, textColor: Int, dashed: Boolean) {
        val swatchW = (base * 0.032f).coerceIn(16f, 34f)
        swatchPaint.color = CompareColors.textHigh()
        swatchPaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(base * 0.012f, base * 0.008f), 0f) else null
        canvas.drawLine(x, y, x + swatchW, y, swatchPaint)
        swatchPaint.pathEffect = null

        smallPaint.color = textColor
        smallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, x + swatchW + base * 0.008f, y + smallPaint.textSize * 0.34f, smallPaint)
    }

    private fun drawChart(canvas: Canvas, rect: RectF, chart: Chart) {
        canvas.drawRoundRect(rect, 8f, 8f, chartPaint)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        val leftLabelW = (tickPaint.measureText(formatAxisValue(chart.maxY, chart.unit)) + base * 0.035f)
            .coerceIn(40f, 76f)
        val titleH = (sectionPaint.textSize * 1.75f).coerceAtLeast(24f)
        val bottomLabelH = (tickPaint.textSize * 1.85f).coerceAtLeast(18f)
        val plot = RectF(
            rect.left + leftLabelW,
            rect.top + titleH,
            rect.right - base * 0.015f,
            rect.bottom - bottomLabelH
        )

        sectionPaint.color = chart.color
        sectionPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${chart.title} (${chart.unit})", plot.left, rect.top + sectionPaint.textSize * 1.18f, sectionPaint)

        drawGridAndAxes(canvas, plot, chart)

        canvas.save()
        canvas.clipRect(plot)
        drawFirstDrop(canvas, plot, shotA, CompareColors.firstDropA, "A")
        drawFirstDrop(canvas, plot, shotB, CompareColors.firstDropB, "B")
        drawTargets(canvas, plot, chart, shotA, false)
        drawTargets(canvas, plot, chart, shotB, true)
        drawSeries(
            canvas = canvas,
            plot = plot,
            series = ShotDerivedMetrics.normalizedSeries(shotA, chart.metric),
            maxY = chart.maxY,
            paint = linePaint(chart.color, alpha = 255, dashed = false)
        )
        drawSeries(
            canvas = canvas,
            plot = plot,
            series = ShotDerivedMetrics.normalizedSeries(shotB, chart.metric),
            maxY = chart.maxY,
            paint = linePaint(chart.color, alpha = 170, dashed = true)
        )
        canvas.restore()
    }

    private fun drawGridAndAxes(canvas: Canvas, plot: RectF, chart: Chart) {
        tickPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val frac = i / 4f
            val y = plot.bottom - plot.height() * frac
            val value = chart.maxY * frac
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            canvas.drawText(formatAxisValue(value, chart.unit), plot.left - base * 0.010f, y + tickPaint.textSize * 0.35f, tickPaint)
        }

        tickPaint.textAlign = Paint.Align.CENTER
        for (i in 0..4) {
            val frac = i / 4f
            val x = plot.left + plot.width() * frac
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val seconds = scales.durationMs * frac / 1000.0
            canvas.drawText("${seconds.format(0)}s", x, plot.bottom + tickPaint.textSize * 1.25f, tickPaint)
        }

        canvas.drawLine(plot.left, plot.top, plot.left, plot.bottom, axisPaint)
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axisPaint)
    }

    private fun drawFirstDrop(canvas: Canvas, plot: RectF, log: ShotLog, color: Int, label: String) {
        val dropMs = ShotDerivedMetrics.firstDropMs(log) ?: return
        val x = timeToX(plot, dropMs)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(190, Color.red(color), Color.green(color), Color.blue(color))
            strokeWidth = (base * 0.0022f).coerceIn(1f, 3f)
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(base * 0.010f, base * 0.007f), 0f)
        }
        canvas.drawLine(x, plot.top, x, plot.bottom, paint)
        smallPaint.color = Color.argb(205, Color.red(color), Color.green(color), Color.blue(color))
        smallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("drop $label", (x + base * 0.006f).coerceAtMost(plot.right - base * 0.060f), plot.top + smallPaint.textSize, smallPaint)
    }

    private fun drawTargets(canvas: Canvas, plot: RectF, chart: Chart, log: ShotLog, dashedForShotB: Boolean) {
        val series = when (chart.metric) {
            ShotMetric.PRESSURE -> emptyList()
            ShotMetric.FLOW -> targetFlowSeries(log)
            ShotMetric.WEIGHT -> targetWeightSeries(log)
        }
        if (series.isEmpty()) return

        val targetColor = when (chart.metric) {
            ShotMetric.FLOW -> CompareColors.correctedTarget
            ShotMetric.WEIGHT -> CompareColors.target
            ShotMetric.PRESSURE -> CompareColors.target
        }
        val paint = linePaint(targetColor, alpha = if (dashedForShotB) 130 else 170, dashed = true, target = true)
        drawSeries(canvas, plot, series, chart.maxY, paint)

        val label = if (dashedForShotB) "target B" else "target A"
        smallPaint.color = Color.argb(if (dashedForShotB) 145 else 180, Color.red(targetColor), Color.green(targetColor), Color.blue(targetColor))
        smallPaint.textAlign = Paint.Align.RIGHT
        val y = plot.top + smallPaint.textSize * (if (dashedForShotB) 2.2f else 1.0f)
        canvas.drawText(label, plot.right - base * 0.010f, y, smallPaint)
    }

    private fun drawSeries(canvas: Canvas, plot: RectF, series: List<ShotMetricPoint>, maxY: Double, paint: Paint) {
        if (series.isEmpty()) return
        val ordered = series.sortedBy { it.timeMs }
        if (ordered.size == 1) {
            val only = ordered.first()
            drawDot(canvas, timeToX(plot, only.timeMs), valueToY(plot, only.value, maxY), paint)
            return
        }

        val path = Path()
        ordered.forEachIndexed { index, point ->
            val x = timeToX(plot, point.timeMs)
            val y = valueToY(plot, point.value, maxY)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        haloPaint.pathEffect = paint.pathEffect
        haloPaint.strokeWidth = paint.strokeWidth + (base * 0.0036f).coerceIn(2f, 5f)
        canvas.drawPath(path, haloPaint)
        canvas.drawPath(path, paint)
        haloPaint.pathEffect = null

        ordered.lastOrNull()?.let { last ->
            drawDot(canvas, timeToX(plot, last.timeMs), valueToY(plot, last.value, maxY), paint)
        }
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, paint: Paint) {
        val radius = (base * 0.006f).coerceIn(3f, 7f)
        val dotPaint = Paint(paint).apply {
            style = Paint.Style.FILL
            pathEffect = null
        }
        canvas.drawCircle(x, y, radius + 2f, dotHaloPaint)
        canvas.drawCircle(x, y, radius, dotPaint)
    }

    private fun linePaint(color: Int, alpha: Int, dashed: Boolean, target: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            strokeWidth = (base * if (target) 0.0021f else 0.0031f).coerceIn(if (target) 1.5f else 2f, if (target) 4f else 6f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (dashed) pathEffect = DashPathEffect(floatArrayOf(base * 0.014f, base * 0.010f), 0f)
        }

    private fun targetFlowSeries(log: ShotLog): List<ShotMetricPoint> =
        normalizedSampleValues(log) { sample ->
            sample.correctedTargetFlowGps ?: sample.targetFlowGps ?: log.stageTargetFlows[sample.stageName]
        }

    private fun targetWeightSeries(log: ShotLog): List<ShotMetricPoint> {
        val trajectory = normalizedSampleValues(log) { it.targetWeightG }
        if (trajectory.isNotEmpty()) return trajectory
        val targetYield = log.targetYieldG ?: return emptyList()
        return listOf(
            ShotMetricPoint(0L, targetYield),
            ShotMetricPoint((ShotDerivedMetrics.durationMs(log) ?: 1L).coerceAtLeast(1L), targetYield)
        )
    }

    private fun normalizedSampleValues(log: ShotLog, valueFor: (ShotSample) -> Double?): List<ShotMetricPoint> {
        val ordered = log.samples.sortedBy { it.timeMs }
        val firstMs = ordered.firstOrNull()?.timeMs ?: 0L
        return ordered.mapNotNull { sample ->
            val value = valueFor(sample)?.takeIf { it.isFinite() } ?: return@mapNotNull null
            ShotMetricPoint(
                timeMs = (sample.timeMs - firstMs).coerceAtLeast(0L),
                value = value
            )
        }
    }

    private fun timeToX(plot: RectF, timeMs: Long): Float {
        val frac = (timeMs.toDouble() / scales.durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        return plot.left + plot.width() * frac
    }

    private fun valueToY(plot: RectF, value: Double, maxY: Double): Float {
        val frac = (value / maxY).coerceIn(0.0, 1.0).toFloat()
        return plot.bottom - plot.height() * frac
    }

    private fun primaryLabel(log: ShotLog): String {
        val beans = log.beansName?.takeIf { it.isNotBlank() } ?: "Unknown beans"
        return "$beans / ${log.profileName}"
    }

    private fun secondaryLabel(log: ShotLog): String =
        listOfNotNull(
            ShotDerivedMetrics.finalYieldG(log)?.let { "${it.format(1)} g" },
            ShotDerivedMetrics.durationMs(log)?.let { "${(it / 1000.0).format(1)} s" },
            log.doseG?.let { "${it.format(1)} g dose" },
            (log.savedAtMs ?: log.startedAtMs)?.let(::shortDate)
        ).joinToString(" | ").ifBlank { "--" }

    private fun shortDate(timeMs: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(timeMs))

    private fun formatAxisValue(value: Double, unit: String): String =
        when (unit) {
            "g/s" -> value.format(1)
            else -> if (value >= 10.0) value.format(0) else value.format(1)
        }

    private fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val suffix = "..."
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end).trimEnd() + suffix) > maxWidth) {
            end--
        }
        return if (end <= 0) "" else text.take(end).trimEnd() + suffix
    }

    private fun Double.format(digits: Int): String =
        String.format(Locale.US, "%.${digits}f", this)
}
