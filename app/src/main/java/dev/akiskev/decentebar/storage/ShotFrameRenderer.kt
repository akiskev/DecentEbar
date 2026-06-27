package dev.akiskev.decentebar.storage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import dev.akiskev.decentebar.model.ShotEventType
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotSample
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private object DashboardColors {
    val background = Color.rgb(10, 12, 14)
    val chartBg = Color.rgb(14, 17, 20)
    val cardBg = Color.rgb(16, 19, 22)

    val flow = Color.rgb(240, 179, 74)
    val pressure = Color.rgb(88, 169, 255)
    val weight = Color.rgb(83, 207, 151)
    val target = Color.rgb(236, 204, 121)
    val correctedTarget = Color.rgb(255, 142, 103)
    val firstDrop = Color.argb(205, 242, 93, 93)

    fun textHigh() = Color.argb(242, 247, 242, 232)
    fun textMed() = Color.argb(184, 247, 242, 232)
    fun textMuted() = Color.argb(128, 247, 242, 232)

    fun grid() = Color.argb(22, 247, 242, 232)
    fun gridMajor() = Color.argb(40, 247, 242, 232)
    fun axis() = Color.argb(82, 247, 242, 232)
    fun divider() = Color.argb(38, 247, 242, 232)
    fun cardBorder() = Color.argb(64, 247, 242, 232)

    private val stageCols = arrayOf(
        intArrayOf(233, 149, 54),   // preinfuse: amber
        intArrayOf(70, 182, 162),   // wait: teal
        intArrayOf(74, 144, 226),   // ramp: blue
        intArrayOf(221, 98, 123),   // extract: coral
        intArrayOf(135, 146, 162),  // finish: slate
        intArrayOf(166, 126, 74)    // extra: bronze
    )

    fun bandFill(ci: Int, state: PhaseState): Int {
        val c = stageCols[ci % stageCols.size]
        val alpha = when (state) {
            PhaseState.ACTIVE -> 18
            PhaseState.PAST -> 10
            PhaseState.FUTURE -> 5
        }
        return Color.argb(alpha, c[0], c[1], c[2])
    }

    fun stripFill(ci: Int, state: PhaseState): Int {
        val c = stageCols[ci % stageCols.size]
        val alpha = when (state) {
            PhaseState.ACTIVE -> 118
            PhaseState.PAST -> 56
            PhaseState.FUTURE -> 28
        }
        return Color.argb(alpha, c[0], c[1], c[2])
    }

    fun stripText(ci: Int): Int {
        val c = stageCols[ci % stageCols.size]
        return Color.argb(220, c[0], c[1], c[2])
    }
}

private enum class PhaseState { PAST, ACTIVE, FUTURE }
private const val SIGNATURE = "akiskev.dev"

class ShotFrameRenderer(
    private val log: ShotLog,
    private val w: Int,
    private val h: Int,
    private val layout: Layout = Layout.ANALYTICS
) {

    enum class Layout { ANALYTICS, YOUTUBE_PIP }

    private val base = min(w, h).toFloat()
    private val isPortrait = h > w

    private val shotDurationMs: Long = run {
        val s = log.startedAtMs ?: 0L
        val e = log.stoppedAtMs ?: (s + (log.samples.lastOrNull()?.timeMs ?: 0L))
        max(1L, e - s)
    }

    private data class Band(
        val name: String,
        val startMs: Long,
        val endMs: Long,
        val ci: Int,
        val targetFlow: Double?
    )

    private val bands: List<Band> = run {
        val result = mutableListOf<Band>()
        var lastName = ""
        var bandStart = 0L
        log.samples.forEach { s ->
            if (s.stageName != lastName) {
                if (lastName.isNotEmpty()) {
                    result += Band(lastName, bandStart, max(s.timeMs, bandStart + 1L), result.size, log.stageTargetFlows[lastName])
                }
                lastName = s.stageName
                bandStart = s.timeMs
            }
        }
        if (lastName.isNotEmpty()) {
            val lastSampleMs = log.samples.lastOrNull()?.timeMs ?: bandStart
            result += Band(lastName, bandStart, max(lastSampleMs, bandStart + 1L), result.size, log.stageTargetFlows[lastName])
        }
        result
    }

    private val firstDropMs: Long? = log.events
        .firstOrNull { it.type == ShotEventType.FIRST_DROP }?.timeMs

    private val maxLeftY: Float = run {
        val maxP = log.samples.mapNotNull { it.commandedPressureBar }.maxOrNull() ?: 9.0
        val maxFlow = max(
            log.samples.maxOfOrNull { it.flowGps } ?: 3.0,
            log.samples.mapNotNull { it.altFlowGps }.maxOrNull() ?: 0.0
        )
        val maxTargetFlow = max(
            max(log.stageTargetFlows.values.maxOrNull() ?: 0.0, log.samples.mapNotNull { it.targetFlowGps }.maxOrNull() ?: 0.0),
            log.samples.mapNotNull { it.correctedTargetFlowGps }.maxOrNull() ?: 0.0
        )
        (ceil(max(maxP, max(maxFlow, maxTargetFlow) * 2.5) / 2.0) * 2.0).toFloat().coerceAtLeast(10f)
    }

    private val maxWeight: Float = run {
        val actual = log.samples.maxOfOrNull { it.weightG } ?: 35.0
        val target = log.samples.mapNotNull { it.targetWeightG }.maxOrNull() ?: 0.0
        (ceil(max(actual, target) / 5.0) * 5.0).toFloat().coerceAtLeast(10f)
    }

    private val bgPaint = Paint().apply { color = DashboardColors.background }
    private val blackPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val chartBgPaint = Paint().apply { color = DashboardColors.chartBg; style = Paint.Style.FILL }
    private val chartBorderPaint = Paint().apply {
        color = DashboardColors.cardBorder()
        strokeWidth = 1.25f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val panelPaint = Paint().apply {
        color = Color.rgb(13, 16, 19)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val windowBorderPaint = Paint().apply {
        color = Color.argb(120, 247, 242, 232)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val bandFillPaint = Paint().apply { style = Paint.Style.FILL }
    private val stripFillPaint = Paint().apply { style = Paint.Style.FILL }
    private val gridPaint = Paint().apply { color = DashboardColors.grid(); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val gridMajorPaint = Paint().apply { color = DashboardColors.gridMajor(); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val axisPaint = Paint().apply { color = DashboardColors.axis(); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val phaseSeparatorPaint = Paint().apply { color = DashboardColors.axis(); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val dropPaint = Paint().apply { color = DashboardColors.firstDrop; strokeWidth = base * 0.0015f; style = Paint.Style.STROKE }

    private val flowPaint = Paint().apply {
        color = DashboardColors.flow
        strokeWidth = base * 0.0030f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val pressurePaint = Paint().apply {
        color = DashboardColors.pressure
        strokeWidth = base * 0.0026f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val weightPaint = Paint().apply {
        color = DashboardColors.weight
        strokeWidth = base * 0.0026f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val targetPaint = Paint().apply {
        color = Color.argb(170, Color.red(DashboardColors.target), Color.green(DashboardColors.target), Color.blue(DashboardColors.target))
        strokeWidth = base * 0.0015f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(base * 0.008f, base * 0.006f), 0f)
    }
    private val plannedFlowPaint = Paint().apply {
        color = Color.argb(170, Color.red(DashboardColors.target), Color.green(DashboardColors.target), Color.blue(DashboardColors.target))
        strokeWidth = base * 0.0015f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(base * 0.006f, base * 0.004f), 0f)
    }
    private val correctedFlowPaint = Paint().apply {
        color = Color.argb(
            190,
            Color.red(DashboardColors.correctedTarget),
            Color.green(DashboardColors.correctedTarget),
            Color.blue(DashboardColors.correctedTarget)
        )
        strokeWidth = base * 0.0015f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(base * 0.003f, base * 0.003f), 0f)
    }
    private val targetWeightPaint = Paint().apply {
        color = Color.argb(140, Color.red(DashboardColors.weight), Color.green(DashboardColors.weight), Color.blue(DashboardColors.weight))
        strokeWidth = base * 0.0015f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(base * 0.006f, base * 0.004f), 0f)
    }

    private val lineHaloPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val dotHaloPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val flowDotPaint = Paint().apply { color = DashboardColors.flow; style = Paint.Style.FILL; isAntiAlias = true }
    private val pressureDotPaint = Paint().apply { color = DashboardColors.pressure; style = Paint.Style.FILL; isAntiAlias = true }
    private val weightDotPaint = Paint().apply { color = DashboardColors.weight; style = Paint.Style.FILL; isAntiAlias = true }
    private val dotR = base * 0.0078f
    private val dotRSm = base * 0.0066f

    private val tickPaint = Paint().apply {
        color = DashboardColors.textMed()
        textSize = base * 0.024f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    private val stageLabelPaint = Paint().apply {
        textSize = base * 0.0175f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val titlePaint = Paint().apply {
        color = DashboardColors.textHigh()
        textSize = base * if (isPortrait) 0.039f else 0.042f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val timePaint = Paint().apply {
        color = DashboardColors.textHigh()
        textSize = base * if (isPortrait) 0.036f else 0.038f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val legendPaint = Paint().apply {
        color = DashboardColors.textMed()
        textSize = base * 0.020f
        isAntiAlias = true
    }
    private val legendSwatchPaint = Paint().apply {
        strokeWidth = base * 0.0027f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val cardBgPaint = Paint().apply {
        color = DashboardColors.cardBg
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val cardBorderPaint = Paint().apply {
        color = DashboardColors.cardBorder()
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val cardDividerPaint = Paint().apply {
        color = DashboardColors.divider()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val metricLabelPaint = Paint().apply {
        color = DashboardColors.textMuted()
        textSize = base * 0.020f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val metricValuePaint = Paint().apply {
        textSize = base * 0.033f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val pipLabelPaint = Paint().apply {
        color = DashboardColors.textMuted()
        textSize = base * 0.016f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pipValuePaint = Paint().apply {
        color = DashboardColors.textHigh()
        textSize = base * 0.034f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pipBrandPaint = Paint().apply {
        color = DashboardColors.textHigh()
        textSize = base * 0.052f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pipBrandAccentPaint = Paint().apply {
        color = DashboardColors.flow
        textSize = base * 0.052f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pipProfilePaint = Paint().apply {
        color = DashboardColors.textMed()
        textSize = base * 0.026f
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val pipSmallPaint = Paint().apply {
        color = DashboardColors.textMed()
        textSize = base * 0.017f
        isAntiAlias = true
    }
    private val pipMetricValuePaint = Paint().apply {
        textSize = base * 0.040f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val pipMetricLabelPaint = Paint().apply {
        color = DashboardColors.textMuted()
        textSize = base * 0.018f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val signaturePaint = Paint().apply {
        color = Color.argb(105, 246, 241, 230)
        textSize = base * 0.014f
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val plotL = w * if (isPortrait) 0.126f else 0.072f
    private val plotR = w - w * if (isPortrait) 0.100f else 0.080f
    private val plotW = plotR - plotL
    private val headerTop = h * if (isPortrait) 0.026f else 0.027f
    private val titleBaseline = headerTop + titlePaint.textSize
    private val legendBaseline = titleBaseline + legendPaint.textSize * if (isPortrait) 1.55f else 1.42f
    private val stripTop = legendBaseline + base * if (isPortrait) 0.028f else 0.022f
    private val stripH = (base * if (isPortrait) 0.033f else 0.030f).coerceIn(28f, 42f)
    private val stripBottom = stripTop + stripH
    private val plotT = stripBottom + base * if (isPortrait) 0.026f else 0.024f
    private val axisLabelH = max(tickPaint.textSize * 1.65f, base * 0.040f)
    private val cardH = (base * if (isPortrait) 0.130f else 0.078f).coerceIn(84f, 145f)
    private val cardBottom = h - h * if (isPortrait) 0.035f else 0.034f
    private val cardTop = cardBottom - cardH
    private val plotB = cardTop - axisLabelH - base * if (isPortrait) 0.026f else 0.022f
    private val plotH = plotB - plotT
    private val cardRect = RectF(plotL, cardTop, plotR, cardBottom)

    fun render(canvas: Canvas, frameTimeMs: Long) {
        val visible = log.samples.filter { it.timeMs <= frameTimeMs }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        if (layout == Layout.YOUTUBE_PIP) {
            drawYoutubePipFrame(canvas, frameTimeMs, visible)
            return
        }

        drawChartSurface(canvas)
        drawBandFills(canvas, frameTimeMs)
        drawGrid(canvas)

        canvas.save()
        canvas.clipRect(plotL, plotT, plotR, plotB)
        drawFirstDrop(canvas, frameTimeMs)
        drawTargetFlowLines(canvas, frameTimeMs)
        drawYieldTrajectory(canvas, visible)
        drawLines(canvas, visible)
        drawCurrentDots(canvas, visible)
        canvas.restore()

        drawAxes(canvas)
        drawAxisLabels(canvas)
        drawPhaseStrip(canvas, frameTimeMs)
        drawTitleArea(canvas, frameTimeMs)
        drawMetricsCard(canvas, visible)
        drawSignature(canvas)
    }

    private fun xPx(timeMs: Long): Float = plotL + (timeMs.toFloat() / shotDurationMs) * plotW
    private fun yPxLeft(v: Float): Float = plotB - (v / maxLeftY) * plotH
    private fun yPxRight(v: Float): Float = plotB - (v / maxWeight) * plotH

    private fun phaseState(b: Band, frameTimeMs: Long): PhaseState = when {
        frameTimeMs < b.startMs -> PhaseState.FUTURE
        frameTimeMs <= b.endMs -> PhaseState.ACTIVE
        else -> PhaseState.PAST
    }

    private fun activeBand(frameTimeMs: Long): Band? =
        bands.firstOrNull { frameTimeMs >= it.startMs && frameTimeMs <= it.endMs }
            ?: bands.lastOrNull { frameTimeMs >= it.startMs }
            ?: bands.firstOrNull()

    private fun drawYoutubePipFrame(canvas: Canvas, frameTimeMs: Long, samples: List<ShotSample>) {
        val padX = w * 0.030f
        val padY = h * 0.040f
        val gap = w * 0.018f
        val sideW = w * 0.250f
        val metricsBottom = h - padY
        val metricsH = (base * 0.118f).coerceIn(108f, 142f)
        val metricsTop = metricsBottom - metricsH
        val mainTop = h * 0.126f
        val mainBottom = metricsTop - h * 0.030f
        val railGap = base * 0.021f
        val railH = (base * 0.035f).coerceIn(32f, 44f)
        val leftColumnW = w - padX * 2f - sideW - gap
        val maxVideoH = (mainBottom - mainTop - railGap - railH).coerceAtLeast(base * 0.30f)
        val videoW = min(leftColumnW, maxVideoH * 16f / 9f)
        val videoH = videoW * 9f / 16f
        val videoLeft = padX + (leftColumnW - videoW) / 2f
        val videoTop = mainTop
        val videoRect = RectF(videoLeft, videoTop, videoLeft + videoW, videoTop + videoH)
        val railRect = RectF(videoRect.left, videoRect.bottom + railGap, videoRect.right, videoRect.bottom + railGap + railH)
        val sideRect = RectF(padX + leftColumnW + gap, mainTop, w - padX, mainBottom)
        val metricsRect = RectF(padX, metricsTop, w - padX, metricsBottom)
        val headerBaseline = padY + pipBrandPaint.textSize * 0.82f
        val elapsed = formatElapsed(frameTimeMs)

        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(elapsed, w - padX, headerBaseline, timePaint)
        drawPipBrandHeader(canvas, padX, headerBaseline, w - padX - timePaint.measureText(elapsed) - gap)

        drawPipVideoWindow(canvas, videoRect)
        drawPipStageRail(canvas, frameTimeMs, railRect)
        drawPipSidePanel(canvas, frameTimeMs, samples, sideRect)
        drawPipMetrics(canvas, samples, metricsRect)
        drawSignature(canvas)
    }

    private fun drawPipBrandHeader(canvas: Canvas, x: Float, baseline: Float, maxRight: Float) {
        val decent = "DECENT "
        val ebar = "EBar"
        val gap = base * 0.023f
        val dividerGap = base * 0.015f
        val logoW = pipBrandPaint.measureText(decent) + pipBrandAccentPaint.measureText(ebar)
        val availableW = (maxRight - x).coerceAtLeast(0f)
        val profileMaxW = (availableW - logoW - gap - dividerGap * 2f).coerceAtLeast(0f)

        pipBrandPaint.textAlign = Paint.Align.LEFT
        pipBrandAccentPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(decent, x, baseline, pipBrandPaint)
        canvas.drawText(ebar, x + pipBrandPaint.measureText(decent), baseline, pipBrandAccentPaint)

        if (profileMaxW <= pipProfilePaint.measureText("...")) return

        val dividerX = x + logoW + dividerGap
        cardDividerPaint.strokeWidth = 1.5f
        canvas.drawLine(
            dividerX,
            baseline - pipBrandPaint.textSize * 0.70f,
            dividerX,
            baseline + pipBrandPaint.textSize * 0.10f,
            cardDividerPaint
        )
        cardDividerPaint.strokeWidth = 1f

        pipProfilePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            fitText(log.profileName, pipProfilePaint, profileMaxW),
            dividerX + dividerGap,
            baseline - pipBrandPaint.textSize * 0.10f,
            pipProfilePaint
        )
    }

    private fun drawPipVideoWindow(canvas: Canvas, rect: RectF) {
        val frame = RectF(
            rect.left - base * 0.006f,
            rect.top - base * 0.006f,
            rect.right + base * 0.006f,
            rect.bottom + base * 0.006f
        )
        val radius = 8f
        canvas.drawRoundRect(frame, radius, radius, panelPaint)
        canvas.drawRect(rect, blackPaint)
        canvas.drawRect(rect, windowBorderPaint)
    }

    private fun drawPipSidePanel(canvas: Canvas, frameTimeMs: Long, samples: List<ShotSample>, rect: RectF) {
        val radius = 8f
        val inset = base * 0.024f
        val active = activeBand(frameTimeMs)
        val stageName = active?.name?.trim()?.uppercase().orEmpty().ifBlank { "--" }
        val stageElapsed = active?.let { formatElapsed((frameTimeMs - it.startMs).coerceAtLeast(0L)) } ?: "--"
        val chartRect = RectF(
            rect.left + inset,
            rect.top + rect.height() * 0.310f,
            rect.right - inset,
            rect.bottom - rect.height() * 0.140f
        )

        canvas.drawRoundRect(rect, radius, radius, panelPaint)
        canvas.drawRoundRect(rect, radius, radius, cardBorderPaint)

        pipLabelPaint.textAlign = Paint.Align.LEFT
        pipLabelPaint.color = DashboardColors.textMuted()
        canvas.drawText("STAGE", rect.left + inset, rect.top + inset + pipLabelPaint.textSize, pipLabelPaint)

        pipValuePaint.textAlign = Paint.Align.LEFT
        pipValuePaint.color = DashboardColors.textHigh()
        canvas.drawText(
            fitText(stageName, pipValuePaint, rect.width() - inset * 2f),
            rect.left + inset,
            rect.top + inset + pipLabelPaint.textSize + pipValuePaint.textSize * 1.30f,
            pipValuePaint
        )

        pipSmallPaint.textAlign = Paint.Align.LEFT
        pipSmallPaint.color = DashboardColors.textMed()
        canvas.drawText("stage $stageElapsed", rect.left + inset, rect.top + inset + pipLabelPaint.textSize + pipValuePaint.textSize * 2.05f, pipSmallPaint)

        drawPipLegend(canvas, rect.left + inset, chartRect.top - base * 0.024f)
        drawPipMiniChart(canvas, frameTimeMs, samples, chartRect)

        val shotEnd = rect.bottom - inset
        pipLabelPaint.color = DashboardColors.textMuted()
        canvas.drawText("SHOT", rect.left + inset, shotEnd - pipSmallPaint.textSize * 1.45f, pipLabelPaint)
        pipSmallPaint.color = DashboardColors.textMed()
        val shotLabel = "${formatElapsed(frameTimeMs)} / ${formatElapsed(shotDurationMs)}"
        canvas.drawText(shotLabel, rect.left + inset, shotEnd, pipSmallPaint)
    }

    private fun drawPipLegend(canvas: Canvas, xStart: Float, baseline: Float) {
        val items = listOf(
            DashboardColors.flow to "Flow",
            DashboardColors.pressure to "Pressure",
            DashboardColors.weight to "Weight"
        )
        var x = xStart
        val swatchW = base * 0.018f
        val swatchGap = base * 0.007f
        val itemGap = base * 0.015f

        legendPaint.textAlign = Paint.Align.LEFT
        legendPaint.color = DashboardColors.textMed()
        items.forEachIndexed { index, item ->
            legendSwatchPaint.color = item.first
            val y = baseline - legendPaint.textSize * 0.34f
            canvas.drawLine(x, y, x + swatchW, y, legendSwatchPaint)
            x += swatchW + swatchGap
            canvas.drawText(item.second, x, baseline, legendPaint)
            x += legendPaint.measureText(item.second)
            if (index < items.lastIndex) x += itemGap
        }
    }

    private fun drawPipMiniChart(canvas: Canvas, frameTimeMs: Long, samples: List<ShotSample>, rect: RectF) {
        fun timeToX(ms: Long): Float = rect.left + (ms.toFloat() / shotDurationMs) * rect.width()
        fun leftY(v: Float): Float = rect.bottom - (v / maxLeftY) * rect.height()
        fun rightY(v: Float): Float = rect.bottom - (v / maxWeight) * rect.height()

        canvas.drawRoundRect(rect, 6f, 6f, chartBgPaint)

        var gridIndex = 1
        while (gridIndex < 4) {
            val y = rect.top + rect.height() * gridIndex / 4f
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            gridIndex++
        }

        canvas.save()
        canvas.clipRect(rect)
        bands.forEach { b ->
            val x1 = timeToX(b.startMs).coerceIn(rect.left, rect.right)
            val x2 = timeToX(b.endMs).coerceIn(rect.left, rect.right)
            if (x2 <= x1) return@forEach
            bandFillPaint.color = DashboardColors.bandFill(b.ci, phaseState(b, frameTimeMs))
            canvas.drawRect(x1, rect.top, x2, rect.bottom, bandFillPaint)
            if (b.ci > 0) canvas.drawLine(x1, rect.top, x1, rect.bottom, phaseSeparatorPaint)
        }

        bands.forEach { b ->
            val tf = b.targetFlow ?: return@forEach
            if (b.startMs > frameTimeMs) return@forEach
            val x1 = timeToX(b.startMs)
            val x2 = timeToX(min(b.endMs, frameTimeMs))
            if (x2 > x1) canvas.drawLine(x1, leftY(tf.toFloat()), x2, leftY(tf.toFloat()), targetPaint)
        }

        val flowPath = Path()
        val pressurePath = Path()
        val weightPath = Path()
        var flowFirst = true
        var pressureFirst = true
        var weightFirst = true
        samples.forEach { sample ->
            val x = timeToX(sample.timeMs)
            val flowY = leftY(sample.flowGps.toFloat())
            if (flowFirst) flowPath.moveTo(x, flowY) else flowPath.lineTo(x, flowY)
            flowFirst = false

            if (sample.commandedPressureBar == null) {
                pressureFirst = true
            } else {
                val pressureY = leftY(sample.commandedPressureBar.toFloat())
                if (pressureFirst) pressurePath.moveTo(x, pressureY) else pressurePath.lineTo(x, pressureY)
                pressureFirst = false
            }

            val weightY = rightY(sample.weightG.toFloat())
            if (weightFirst) weightPath.moveTo(x, weightY) else weightPath.lineTo(x, weightY)
            weightFirst = false
        }

        drawDataPath(canvas, weightPath, weightPaint)
        drawDataPath(canvas, pressurePath, pressurePaint)
        drawDataPath(canvas, flowPath, flowPaint)

        samples.lastOrNull()?.let { last ->
            val x = timeToX(last.timeMs)
            drawDataDot(canvas, x, leftY(last.flowGps.toFloat()), dotR * 0.78f, flowDotPaint)
            last.commandedPressureBar?.let { drawDataDot(canvas, x, leftY(it.toFloat()), dotRSm * 0.78f, pressureDotPaint) }
            drawDataDot(canvas, x, rightY(last.weightG.toFloat()), dotRSm * 0.78f, weightDotPaint)
        }
        canvas.restore()

        canvas.drawRoundRect(rect, 6f, 6f, chartBorderPaint)
    }

    private fun drawPipStageRail(canvas: Canvas, frameTimeMs: Long, rect: RectF) {
        fun timeToX(ms: Long): Float = rect.left + (ms.toFloat() / shotDurationMs) * rect.width()

        canvas.drawRoundRect(rect, 6f, 6f, panelPaint)
        bands.forEach { band ->
            val x1 = timeToX(band.startMs).coerceIn(rect.left, rect.right)
            val x2 = timeToX(band.endMs).coerceIn(rect.left, rect.right)
            if (x2 <= x1) return@forEach
            val state = phaseState(band, frameTimeMs)
            stripFillPaint.color = DashboardColors.stripFill(band.ci, state)
            canvas.drawRect(x1, rect.top, x2, rect.bottom, stripFillPaint)
            if (band.ci > 0) canvas.drawLine(x1, rect.top, x1, rect.bottom, phaseSeparatorPaint)

            val label = phaseLabel(band.name, x2 - x1 - base * 0.010f)
            if (label.isNotEmpty()) {
                stageLabelPaint.color = if (state == PhaseState.ACTIVE) DashboardColors.textHigh() else DashboardColors.stripText(band.ci)
                canvas.drawText(label, (x1 + x2) / 2f, rect.top + rect.height() * 0.66f, stageLabelPaint)
            }
        }

        val cursorX = timeToX(frameTimeMs.coerceIn(0L, shotDurationMs))
        val cursorPaint = lineHaloPaint
        cursorPaint.pathEffect = null
        cursorPaint.strokeWidth = 1.5f
        cursorPaint.color = DashboardColors.textHigh()
        canvas.drawLine(cursorX, rect.top, cursorX, rect.bottom, cursorPaint)
        cursorPaint.color = Color.argb(160, 0, 0, 0)
        canvas.drawRoundRect(rect, 6f, 6f, chartBorderPaint)
    }

    private fun drawPipMetrics(canvas: Canvas, samples: List<ShotSample>, rect: RectF) {
        val last = samples.lastOrNull()
        val radius = 8f
        data class Metric(val color: Int, val label: String, val value: String)
        val metrics = listOf(
            Metric(DashboardColors.flow, "Flow", if (last != null) "${"%.2f".format(last.flowGps)} g/s" else "--"),
            Metric(DashboardColors.pressure, "Pressure", if (last?.commandedPressureBar != null) "${"%.1f".format(last.commandedPressureBar)} bar" else "--"),
            Metric(DashboardColors.weight, "Weight", if (last != null) "${"%.1f".format(last.weightG)} g" else "--")
        )
        val colW = rect.width() / metrics.size
        val labelY = rect.top + rect.height() * 0.36f
        val valueY = rect.top + rect.height() * 0.76f

        canvas.drawRoundRect(rect, radius, radius, cardBgPaint)
        canvas.drawRoundRect(rect, radius, radius, cardBorderPaint)

        metrics.forEachIndexed { index, metric ->
            val cx = rect.left + colW * index + colW / 2f
            if (index > 0) {
                val x = rect.left + colW * index
                canvas.drawLine(x, rect.top + rect.height() * 0.16f, x, rect.bottom - rect.height() * 0.16f, cardDividerPaint)
            }

            pipMetricLabelPaint.color = DashboardColors.textMuted()
            pipMetricLabelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(metric.label, cx, labelY, pipMetricLabelPaint)

            pipMetricValuePaint.color = metric.color
            pipMetricValuePaint.textAlign = Paint.Align.CENTER
            canvas.drawText(metric.value, cx, valueY, pipMetricValuePaint)
        }
    }

    private fun drawChartSurface(canvas: Canvas) {
        canvas.drawRect(plotL, plotT, plotR, plotB, chartBgPaint)
        canvas.drawRect(plotL, plotT, plotR, plotB, chartBorderPaint)
    }

    private fun drawBandFills(canvas: Canvas, frameTimeMs: Long) {
        bands.forEach { b ->
            val x1 = xPx(b.startMs).coerceIn(plotL, plotR)
            val x2 = xPx(b.endMs).coerceIn(plotL, plotR)
            if (x2 <= x1) return@forEach
            bandFillPaint.color = DashboardColors.bandFill(b.ci, phaseState(b, frameTimeMs))
            canvas.drawRect(x1, plotT, x2, plotB, bandFillPaint)
            if (b.ci > 0) {
                canvas.drawLine(x1, plotT, x1, plotB, phaseSeparatorPaint)
            }
        }
    }

    private fun drawPhaseStrip(canvas: Canvas, frameTimeMs: Long) {
        bands.forEach { b ->
            val x1 = xPx(b.startMs).coerceIn(plotL, plotR)
            val x2 = xPx(b.endMs).coerceIn(plotL, plotR)
            if (x2 <= x1) return@forEach

            val state = phaseState(b, frameTimeMs)
            stripFillPaint.color = DashboardColors.stripFill(b.ci, state)
            canvas.drawRect(x1, stripTop, x2, stripBottom, stripFillPaint)

            if (b.ci > 0) {
                canvas.drawLine(x1, stripTop, x1, stripBottom, phaseSeparatorPaint)
            }

            val labelW = x2 - x1 - base * 0.010f
            val label = phaseLabel(b.name, labelW)
            if (label.isNotEmpty()) {
                stageLabelPaint.color = if (state == PhaseState.ACTIVE) DashboardColors.textHigh() else DashboardColors.stripText(b.ci)
                canvas.drawText(label, (x1 + x2) / 2f, stripTop + stripH * 0.66f, stageLabelPaint)
            }
        }
        canvas.drawLine(plotL, stripTop, plotR, stripTop, axisPaint)
        canvas.drawLine(plotL, stripBottom, plotR, stripBottom, axisPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val yStep = if (maxLeftY > 10f) 2f else 1f
        var v = yStep
        while (v < maxLeftY) {
            val y = yPxLeft(v)
            canvas.drawLine(plotL, y, plotR, y, if (v.toInt() % 4 == 0) gridMajorPaint else gridPaint)
            v += yStep
        }

        val durSec = ceil(shotDurationMs / 1000.0).toInt()
        val secStep = if (durSec > 60) 10 else 5
        var s = secStep
        while (s * 1000L < shotDurationMs) {
            val x = xPx(s * 1000L)
            canvas.drawLine(x, plotT, x, plotB, gridPaint)
            s += secStep
        }
    }

    private fun drawAxes(canvas: Canvas) {
        canvas.drawLine(plotL, plotT, plotL, plotB, axisPaint)
        canvas.drawLine(plotR, plotT, plotR, plotB, axisPaint)
        canvas.drawLine(plotL, plotB, plotR, plotB, axisPaint)
    }

    private fun drawFirstDrop(canvas: Canvas, frameTimeMs: Long) {
        val t = firstDropMs ?: return
        if (t > frameTimeMs) return
        dropPaint.pathEffect = DashPathEffect(floatArrayOf(base * 0.006f, base * 0.0045f), 0f)
        val x = xPx(t)
        canvas.drawLine(x, plotT, x, plotB, dropPaint)
        dropPaint.pathEffect = null
    }

    private fun drawTargetFlowLines(canvas: Canvas, frameTimeMs: Long) {
        bands.forEach { b ->
            val tf = b.targetFlow ?: return@forEach
            if (b.startMs > frameTimeMs) return@forEach
            val x1 = xPx(b.startMs)
            val x2 = xPx(min(b.endMs, frameTimeMs))
            if (x2 <= x1) return@forEach
            val y = yPxLeft(tf.toFloat())
            canvas.drawLine(x1, y, x2, y, targetPaint)
        }
    }

    private fun drawYieldTrajectory(canvas: Canvas, samples: List<ShotSample>) {
        if (samples.none { it.targetFlowGps != null || it.targetWeightG != null }) return
        val targetWeightPath = Path()
        val plannedFlowPath = Path()
        val correctedFlowPath = Path()
        var targetWeightFirst = true
        var plannedFlowFirst = true
        var correctedFlowFirst = true

        samples.forEach { s ->
            val x = xPx(s.timeMs)
            if (s.targetWeightG == null) {
                targetWeightFirst = true
            } else {
                val y = yPxRight(s.targetWeightG.toFloat())
                if (targetWeightFirst) targetWeightPath.moveTo(x, y) else targetWeightPath.lineTo(x, y)
                targetWeightFirst = false
            }

            if (s.targetFlowGps == null) {
                plannedFlowFirst = true
            } else {
                val y = yPxLeft(s.targetFlowGps.toFloat())
                if (plannedFlowFirst) plannedFlowPath.moveTo(x, y) else plannedFlowPath.lineTo(x, y)
                plannedFlowFirst = false
            }

            if (s.correctedTargetFlowGps == null) {
                correctedFlowFirst = true
            } else {
                val y = yPxLeft(s.correctedTargetFlowGps.toFloat())
                if (correctedFlowFirst) correctedFlowPath.moveTo(x, y) else correctedFlowPath.lineTo(x, y)
                correctedFlowFirst = false
            }
        }

        drawReferencePath(canvas, targetWeightPath, targetWeightPaint)
        drawReferencePath(canvas, plannedFlowPath, plannedFlowPaint)
        drawReferencePath(canvas, correctedFlowPath, correctedFlowPaint)
    }

    private fun drawLines(canvas: Canvas, samples: List<ShotSample>) {
        if (samples.size < 2) return

        val flowPath = Path()
        val pressurePath = Path()
        val weightPath = Path()
        var flowFirst = true
        var pressureFirst = true
        var weightFirst = true

        samples.forEach { s ->
            val x = xPx(s.timeMs)
            val yF = yPxLeft(s.flowGps.toFloat())
            if (flowFirst) flowPath.moveTo(x, yF) else flowPath.lineTo(x, yF)
            flowFirst = false

            if (s.commandedPressureBar == null) {
                pressureFirst = true
            } else {
                val yP = yPxLeft(s.commandedPressureBar.toFloat())
                if (pressureFirst) pressurePath.moveTo(x, yP) else pressurePath.lineTo(x, yP)
                pressureFirst = false
            }

            val yW = yPxRight(s.weightG.toFloat())
            if (weightFirst) weightPath.moveTo(x, yW) else weightPath.lineTo(x, yW)
            weightFirst = false
        }

        drawDataPath(canvas, weightPath, weightPaint)
        drawDataPath(canvas, pressurePath, pressurePaint)
        drawDataPath(canvas, flowPath, flowPaint)
    }

    private fun drawCurrentDots(canvas: Canvas, samples: List<ShotSample>) {
        val last = samples.lastOrNull() ?: return
        val x = xPx(last.timeMs)
        drawDataDot(canvas, x, yPxLeft(last.flowGps.toFloat()), dotR, flowDotPaint)
        last.commandedPressureBar?.let { drawDataDot(canvas, x, yPxLeft(it.toFloat()), dotRSm, pressureDotPaint) }
        drawDataDot(canvas, x, yPxRight(last.weightG.toFloat()), dotRSm, weightDotPaint)
    }

    private fun drawDataPath(canvas: Canvas, path: Path, paint: Paint) {
        lineHaloPaint.pathEffect = null
        lineHaloPaint.strokeWidth = paint.strokeWidth + base * 0.0034f
        canvas.drawPath(path, lineHaloPaint)
        canvas.drawPath(path, paint)
    }

    private fun drawReferencePath(canvas: Canvas, path: Path, paint: Paint) {
        lineHaloPaint.pathEffect = paint.pathEffect
        lineHaloPaint.strokeWidth = paint.strokeWidth + base * 0.0018f
        canvas.drawPath(path, lineHaloPaint)
        canvas.drawPath(path, paint)
        lineHaloPaint.pathEffect = null
    }

    private fun drawDataDot(canvas: Canvas, x: Float, y: Float, radius: Float, paint: Paint) {
        canvas.drawCircle(x, y, radius + base * 0.0040f, dotHaloPaint)
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun drawAxisLabels(canvas: Canvas) {
        tickPaint.textAlign = Paint.Align.RIGHT
        val yStep = if (maxLeftY > 10f) 2f else 1f
        var v = 0f
        while (v <= maxLeftY) {
            canvas.drawText(v.toInt().toString(), plotL - base * 0.008f, yPxLeft(v) + tickPaint.textSize * 0.35f, tickPaint)
            v += yStep
        }

        tickPaint.textAlign = Paint.Align.LEFT
        val weightStep = if (maxWeight > 20f) 5f else 2f
        var wv = 0f
        while (wv <= maxWeight) {
            canvas.drawText(wv.toInt().toString(), plotR + base * 0.008f, yPxRight(wv) + tickPaint.textSize * 0.35f, tickPaint)
            wv += weightStep
        }

        tickPaint.textAlign = Paint.Align.CENTER
        val durSec = ceil(shotDurationMs / 1000.0).toInt()
        val secStep = if (durSec > 60) 10 else 5
        var s = 0
        while (s * 1000L <= shotDurationMs) {
            canvas.drawText("${s}s", xPx(s * 1000L), plotB + axisLabelH * 0.62f, tickPaint)
            s += secStep
        }
    }

    private fun drawTitleArea(canvas: Canvas, frameTimeMs: Long) {
        val elapsed = formatElapsed(frameTimeMs)
        val timeW = timePaint.measureText(elapsed)
        val gap = base * 0.030f
        val titleMaxW = (plotR - plotL - timeW - gap).coerceAtLeast(base * 0.20f)

        titlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(fitText(log.profileName, titlePaint, titleMaxW), plotL, titleBaseline, titlePaint)

        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(elapsed, plotR, titleBaseline, timePaint)

        drawLegend(canvas)
    }

    private fun drawLegend(canvas: Canvas) {
        data class LegendItem(val color: Int, val label: String)
        val items = listOf(
            LegendItem(DashboardColors.flow, "Flow"),
            LegendItem(DashboardColors.pressure, "Pressure"),
            LegendItem(DashboardColors.weight, "Weight")
        )

        val itemGap = base * 0.020f
        val swatchW = base * 0.020f
        val swatchGap = base * 0.008f
        val totalW = items.sumOf { (swatchW + swatchGap + legendPaint.measureText(it.label)).toDouble() }.toFloat() +
            itemGap * (items.size - 1)
        var x = (plotR - totalW).coerceAtLeast(plotL)
        val y = legendBaseline
        legendPaint.textAlign = Paint.Align.LEFT

        items.forEachIndexed { index, item ->
            legendSwatchPaint.color = item.color
            val swatchY = y - legendPaint.textSize * 0.34f
            canvas.drawLine(x, swatchY, x + swatchW, swatchY, legendSwatchPaint)
            x += swatchW + swatchGap
            legendPaint.color = DashboardColors.textMed()
            canvas.drawText(item.label, x, y, legendPaint)
            x += legendPaint.measureText(item.label)
            if (index < items.lastIndex) x += itemGap
        }
    }

    private fun drawMetricsCard(canvas: Canvas, samples: List<ShotSample>) {
        val last = samples.lastOrNull()
        val radius = 8f

        canvas.drawRoundRect(cardRect, radius, radius, cardBgPaint)
        canvas.drawRoundRect(cardRect, radius, radius, cardBorderPaint)

        data class Metric(val color: Int, val label: String, val value: String)
        val metrics = listOf(
            Metric(DashboardColors.flow, "Flow", if (last != null) "${"%.2f".format(last.flowGps)} g/s" else "--"),
            Metric(DashboardColors.pressure, "Pressure", if (last?.commandedPressureBar != null) "${"%.1f".format(last.commandedPressureBar)} bar" else "--"),
            Metric(DashboardColors.weight, "Weight", if (last != null) "${"%.1f".format(last.weightG)} g" else "--")
        )

        val colW = cardRect.width() / metrics.size
        val labelY = cardTop + cardH * 0.40f
        val valueY = cardTop + cardH * 0.78f

        metrics.forEachIndexed { i, metric ->
            val cx = plotL + colW * i + colW / 2f
            if (i > 0) {
                val x = plotL + colW * i
                canvas.drawLine(x, cardTop + cardH * 0.16f, x, cardTop + cardH * 0.84f, cardDividerPaint)
            }

            metricLabelPaint.color = DashboardColors.textMuted()
            metricLabelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(metric.label, cx, labelY, metricLabelPaint)

            metricValuePaint.color = metric.color
            metricValuePaint.textAlign = Paint.Align.CENTER
            canvas.drawText(metric.value, cx, valueY, metricValuePaint)
        }
    }

    private fun drawSignature(canvas: Canvas) {
        val y = (cardBottom + (h - cardBottom) * 0.64f)
            .coerceAtMost(h - base * 0.008f)
        canvas.drawText(SIGNATURE, plotR, y, signaturePaint)
    }

    private fun phaseLabel(name: String, maxWidth: Float): String {
        val clean = name.trim().uppercase()
        val initials = clean.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString("") { it.take(1) }
        val candidates = listOf(clean, initials, clean.take(4))
        return candidates.firstOrNull { it.isNotEmpty() && stageLabelPaint.measureText(it) <= maxWidth } ?: ""
    }

    private fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text

        val suffix = "..."
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end).trimEnd() + suffix) > maxWidth) {
            end--
        }
        return if (end <= 0) "" else text.take(end).trimEnd() + suffix
    }

    private fun formatElapsed(ms: Long): String {
        val sec = ms / 1000
        val frac = (ms % 1000) / 100
        return "$sec.${frac}s"
    }
}
