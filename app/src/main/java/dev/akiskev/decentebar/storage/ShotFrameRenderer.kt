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

// ── Espresso Warm design tokens ────────────────────────────────────────────────
private object EW {
    // Surfaces
    val background  = Color.BLACK
    val cardBg      = Color.rgb(10, 10, 10)               // card fill (opaque)

    // Data series
    val flow        = Color.rgb(201, 165,  90)            // #C9A55A  crema gold
    val pressure    = Color.rgb(176, 115,  85)            // #B07355  terracotta
    val weight      = Color.rgb(106, 158, 136)            // #6A9E88  sage
    val target      = Color.rgb(232, 206, 133)            // #E8CE85  light gold

    // Markers
    val firstDrop   = Color.argb(180, 196,  91,  91)      // muted red

    // Text
    fun textHigh()   = Color.argb(230, 252, 243, 230)     // 0.90 opacity
    fun textMed()    = Color.argb(158, 252, 243, 230)     // 0.62
    fun textMuted()  = Color.argb(107, 252, 243, 230)     // 0.42

    // Structure
    fun grid()       = Color.argb( 20, 252, 243, 230)     // 0.08
    fun gridMajor()  = Color.argb( 31, 252, 243, 230)     // 0.12
    fun axis()       = Color.argb( 41, 252, 243, 230)     // 0.16
    fun tick()       = Color.argb(140, 252, 243, 230)     // 0.55
    fun cardBorder() = Color.argb( 51, 252, 243, 230)     // 0.20
    fun divider()    = Color.argb( 36, 252, 243, 230)     // card column divider

    // Badge
    fun badgeBg()    = Color.argb( 36, 201, 165,  90)
    fun badgeBorder()= Color.argb( 71, 232, 206, 133)

    // Stage palette — one warm/cool hue per stage slot
    private val stageCols = arrayOf(
        intArrayOf(176, 115,  85),   // slot 0 – terracotta   (Preinfusion)
        intArrayOf(201, 165,  90),   // slot 1 – gold          (Wait)
        intArrayOf(232, 206, 133),   // slot 2 – light gold    (Ramp)
        intArrayOf(106, 158, 136),   // slot 3 – sage          (Main)
        intArrayOf(106, 140, 158),   // slot 4 – blue-sage     (Fade)
        intArrayOf(158, 126, 106)    // slot 5 – warm grey     (extra)
    )

    // Full-height tint — 7% opacity, drawn under chart lines as context
    fun bandFill(ci: Int): Int {
        val c = stageCols[ci % stageCols.size]
        return Color.argb(18, c[0], c[1], c[2])
    }

    // Phase-strip fill — active 22%, past 15%, future 10%
    fun stripFill(ci: Int, state: StripState): Int {
        val c = stageCols[ci % stageCols.size]
        val alpha = when (state) {
            StripState.ACTIVE -> 56
            StripState.PAST   -> 36
            StripState.FUTURE -> 22
        }
        return Color.argb(alpha, c[0], c[1], c[2])
    }

    fun stripText(ci: Int): Int {
        val c = stageCols[ci % stageCols.size]
        return Color.argb(200, c[0], c[1], c[2])
    }
}

private enum class StripState { PAST, ACTIVE, FUTURE }

// ── Renderer ──────────────────────────────────────────────────────────────────
class ShotFrameRenderer(private val log: ShotLog, private val w: Int, private val h: Int) {

    private val shotDurationMs: Long = run {
        val s = log.startedAtMs ?: 0L
        val e = log.stoppedAtMs ?: (s + (log.samples.lastOrNull()?.timeMs ?: 0L))
        max(1L, e - s)
    }

    // ── Layout dimensions ────────────────────────────────────────────────────
    private val padL       = w * 0.072f
    private val padR       = w * 0.092f
    private val titleAreaH = h * 0.086f     // title row + time
    private val stripH     = h * 0.034f     // phase strip above chart
    private val xAxisH     = h * 0.040f     // x-axis tick label row
    private val cardH      = h * 0.076f     // bottom metrics card
    private val cardPad    = h * 0.042f     // gap between plot bottom and card (must clear xAxisH labels)

    private val plotL = padL
    private val plotR = w - padR
    private val plotT = titleAreaH + stripH
    private val plotB = h.toFloat() - xAxisH - cardH - cardPad * 2f
    private val plotW = plotR - plotL
    private val plotH = plotB - plotT

    // ── Data ranges ──────────────────────────────────────────────────────────
    private val maxLeftY: Float = run {
        val maxP = log.samples.mapNotNull { it.commandedPressureBar }.maxOrNull() ?: 9.0
        val maxF = log.samples.maxOfOrNull { it.flowGps } ?: 3.0
        (ceil(max(maxP, maxF * 2.5) / 2) * 2).toFloat().coerceAtLeast(10f)
    }
    private val maxWeight: Float = run {
        val mw = log.samples.maxOfOrNull { it.weightG } ?: 35.0
        (ceil(mw / 5) * 5).toFloat().coerceAtLeast(10f)
    }

    // ── Stage bands ──────────────────────────────────────────────────────────
    private data class Band(
        val name: String, val startMs: Long, val endMs: Long,
        val ci: Int, val targetFlow: Double?
    )
    private val bands: List<Band> = run {
        val result = mutableListOf<Band>()
        var lastName = ""; var bandStart = 0L
        log.samples.forEach { s ->
            if (s.stageName != lastName) {
                if (lastName.isNotEmpty()) result += Band(
                    lastName, bandStart, s.timeMs, result.size,
                    log.stageTargetFlows[lastName]
                )
                lastName = s.stageName; bandStart = s.timeMs
            }
        }
        if (lastName.isNotEmpty()) result += Band(
            lastName, bandStart, log.samples.lastOrNull()?.timeMs ?: bandStart,
            result.size, log.stageTargetFlows[lastName]
        )
        result
    }

    private val firstDropMs: Long? = log.events
        .firstOrNull { it.type == ShotEventType.FIRST_DROP }?.timeMs

    // ── Paint objects ────────────────────────────────────────────────────────
    // Using min(w,h) as base for line widths so they scale correctly across
    // 16:9, 1:1, and 9:16 formats.
    private val base = min(w, h).toFloat()

    private val bgPaint        = Paint().apply { color = EW.background }
    private val bandFillPaint  = Paint().apply { style = Paint.Style.FILL }
    private val stripFillPaint = Paint().apply { style = Paint.Style.FILL }
    private val gridPaint      = Paint().apply { color = EW.grid();     strokeWidth = 1f; style = Paint.Style.STROKE }
    private val gridMajorPaint = Paint().apply { color = EW.gridMajor(); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val axisPaint      = Paint().apply { color = EW.axis();     strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val stripBorderPaint = Paint().apply { color = EW.axis();   strokeWidth = 1f; style = Paint.Style.STROKE }
    private val dropPaint      = Paint().apply { color = EW.firstDrop; strokeWidth = 1.5f; style = Paint.Style.STROKE }

    private val flowPaint = Paint().apply {
        color = EW.flow; strokeWidth = base * 0.00278f   // ~3.0 px at 1080
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    private val pressurePaint = Paint().apply {
        color = EW.pressure; strokeWidth = base * 0.00231f  // ~2.5 px at 1080
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    private val weightPaint = Paint().apply {
        color = EW.weight; strokeWidth = base * 0.00231f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    private val targetPaint = Paint().apply {
        color = Color.argb(178, 232, 206, 133)  // #E8CE85 at 70%
        strokeWidth = base * 0.00139f           // ~1.5 px at 1080
        style = Paint.Style.STROKE; isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(base * 0.0074f, base * 0.0074f), 0f)
    }

    private val flowDotPaint    = Paint().apply { color = EW.flow;     style = Paint.Style.FILL; isAntiAlias = true }
    private val pressureDotPaint= Paint().apply { color = EW.pressure; style = Paint.Style.FILL; isAntiAlias = true }
    private val weightDotPaint  = Paint().apply { color = EW.weight;   style = Paint.Style.FILL; isAntiAlias = true }
    private val dotR            = base * 0.0083f   // ~9 px flow dot
    private val dotRSm          = base * 0.0069f   // ~7.5 px pressure/weight

    private val tickPaint = Paint().apply {
        color = EW.tick(); textSize = h * 0.026f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }
    private val stageLabelPaint = Paint().apply {
        textSize = h * 0.021f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint().apply {
        color = EW.textHigh(); textSize = h * 0.044f; isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val timePaint = Paint().apply {
        color = EW.textMed(); textSize = h * 0.036f; isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val cardBgPaint = Paint().apply {
        color = EW.cardBg; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val cardBorderPaint = Paint().apply {
        color = EW.cardBorder(); strokeWidth = 1f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val cardDividerPaint = Paint().apply {
        color = EW.divider(); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val metricLabelPaint = Paint().apply {
        color = EW.textMuted(); textSize = h * 0.023f; isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val metricValuePaint = Paint().apply {
        textSize = h * 0.035f; isAntiAlias = true; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val legendPaint = Paint().apply {
        color = EW.textMuted(); textSize = h * 0.021f; isAntiAlias = true
    }
    private val badgePaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val badgeBorder   = Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val badgeTextPaint= Paint().apply {
        color = EW.target; textSize = h * 0.021f; isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    // ── Entry point ──────────────────────────────────────────────────────────
    fun render(canvas: Canvas, frameTimeMs: Long) {
        val visible = log.samples.filter { it.timeMs <= frameTimeMs }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        drawBandFills(canvas, frameTimeMs)
        drawPhaseStrip(canvas, frameTimeMs)
        drawGrid(canvas)
        drawAxes(canvas)
        drawFirstDrop(canvas, frameTimeMs)
        drawTargetFlowLines(canvas, frameTimeMs)
        drawLines(canvas, visible)
        drawCurrentDots(canvas, visible)
        drawAxisLabels(canvas)
        drawTitleArea(canvas, frameTimeMs)
        drawMetricsCard(canvas, visible)
    }

    // ── Coordinate helpers ───────────────────────────────────────────────────
    private fun xPx(timeMs: Long): Float = plotL + (timeMs.toFloat() / shotDurationMs) * plotW
    private fun yPxLeft(v: Float) : Float = plotB - (v / maxLeftY) * plotH
    private fun yPxRight(v: Float): Float = plotB - (v / maxWeight) * plotH

    // ── Drawing passes ────────────────────────────────────────────────────────

    /** Very subtle full-height tints — drawn up to current frame for a "reveal" effect. */
    private fun drawBandFills(canvas: Canvas, frameTimeMs: Long) {
        bands.forEach { b ->
            if (b.startMs > frameTimeMs) return@forEach
            bandFillPaint.color = EW.bandFill(b.ci)
            canvas.drawRect(xPx(b.startMs), plotT, xPx(min(b.endMs, frameTimeMs)), plotB, bandFillPaint)
        }
    }

    /**
     * Phase strip — thin row between title and chart.
     * Always shows the full shot structure so the viewer can read the plan
     * before data arrives. Active stage gets a brighter fill; future stages
     * are dimmed but visible.
     */
    private fun drawPhaseStrip(canvas: Canvas, frameTimeMs: Long) {
        val sy1 = titleAreaH
        val sy2 = titleAreaH + stripH

        bands.forEach { b ->
            val state = when {
                frameTimeMs < b.startMs -> StripState.FUTURE
                frameTimeMs <= b.endMs  -> StripState.ACTIVE
                else                    -> StripState.PAST
            }
            stripFillPaint.color = EW.stripFill(b.ci, state)
            canvas.drawRect(xPx(b.startMs), sy1, xPx(b.endMs), sy2, stripFillPaint)

            // Separator on left edge of each stage (except first)
            if (b.ci > 0) canvas.drawLine(xPx(b.startMs), sy1, xPx(b.startMs), sy2, stripBorderPaint)

            // Stage label — truncate if segment is too narrow
            stageLabelPaint.color = EW.stripText(b.ci)
            val midX = (xPx(b.startMs) + xPx(b.endMs)) / 2f
            val segW = xPx(b.endMs) - xPx(b.startMs) - 8f
            val label = if (stageLabelPaint.measureText(b.name) <= segW) b.name
                        else if (stageLabelPaint.measureText(b.name.take(4)) <= segW) b.name.take(4)
                        else ""
            if (label.isNotEmpty()) {
                canvas.drawText(label, midX, sy2 - stripH * 0.22f, stageLabelPaint)
            }
        }

        // Bottom border (divides strip from chart)
        canvas.drawLine(plotL, sy2, plotR, sy2, axisPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = if (maxLeftY > 10f) 2f else 1f
        var v = step
        while (v < maxLeftY) {
            val y = yPxLeft(v)
            canvas.drawLine(plotL, y, plotR, y, if (v.toInt() % 4 == 0) gridMajorPaint else gridPaint)
            v += step
        }
        val durSec = (shotDurationMs / 1000).toInt()
        val secStep = if (durSec > 60) 10 else 5
        var s = secStep
        while (s < durSec) {
            canvas.drawLine(xPx(s * 1000L), plotT, xPx(s * 1000L), plotB, gridPaint)
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
        dropPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
        canvas.drawLine(xPx(t), plotT, xPx(t), plotB, dropPaint)
        dropPaint.pathEffect = null
    }

    private fun drawTargetFlowLines(canvas: Canvas, frameTimeMs: Long) {
        bands.forEach { b ->
            val tf = b.targetFlow ?: return@forEach
            if (b.startMs > frameTimeMs) return@forEach
            canvas.drawLine(xPx(b.startMs), yPxLeft(tf.toFloat()),
                xPx(min(b.endMs, frameTimeMs)), yPxLeft(tf.toFloat()), targetPaint)
        }
    }

    private fun drawLines(canvas: Canvas, samples: List<ShotSample>) {
        if (samples.size < 2) return
        val fPath = Path(); val pPath = Path(); val wPath = Path()
        var fFirst = true; var pFirst = true; var wFirst = true
        samples.forEach { s ->
            val x = xPx(s.timeMs)
            val yF = yPxLeft(s.flowGps.toFloat())
            if (fFirst) { fPath.moveTo(x, yF); fFirst = false } else fPath.lineTo(x, yF)
            s.commandedPressureBar?.let { p ->
                val yP = yPxLeft(p.toFloat())
                if (pFirst) { pPath.moveTo(x, yP); pFirst = false } else pPath.lineTo(x, yP)
            }
            val yW = yPxRight(s.weightG.toFloat())
            if (wFirst) { wPath.moveTo(x, yW); wFirst = false } else wPath.lineTo(x, yW)
        }
        // Draw order: weight behind, pressure middle, flow on top
        canvas.drawPath(wPath, weightPaint)
        canvas.drawPath(pPath, pressurePaint)
        canvas.drawPath(fPath, flowPaint)
    }

    private fun drawCurrentDots(canvas: Canvas, samples: List<ShotSample>) {
        val last = samples.lastOrNull() ?: return
        val x = xPx(last.timeMs)
        canvas.drawCircle(x, yPxLeft(last.flowGps.toFloat()), dotR, flowDotPaint)
        last.commandedPressureBar?.let { canvas.drawCircle(x, yPxLeft(it.toFloat()), dotRSm, pressureDotPaint) }
        canvas.drawCircle(x, yPxRight(last.weightG.toFloat()), dotRSm, weightDotPaint)
    }

    private fun drawAxisLabels(canvas: Canvas) {
        tickPaint.textAlign = Paint.Align.RIGHT
        val step = if (maxLeftY > 10f) 2f else 1f
        var v = 0f
        while (v <= maxLeftY) {
            canvas.drawText(v.toInt().toString(), plotL - 8f, yPxLeft(v) + tickPaint.textSize * 0.35f, tickPaint)
            v += step
        }
        tickPaint.textAlign = Paint.Align.LEFT
        val wStep = if (maxWeight > 20f) 5f else 2f
        var wv = 0f
        while (wv <= maxWeight) {
            canvas.drawText(wv.toInt().toString(), plotR + 8f, yPxRight(wv) + tickPaint.textSize * 0.35f, tickPaint)
            wv += wStep
        }
        tickPaint.textAlign = Paint.Align.CENTER
        val durSec = (shotDurationMs / 1000).toInt()
        val secStep = if (durSec > 60) 10 else 5
        var s = 0
        while (s <= durSec) {
            canvas.drawText("${s}s", xPx(s * 1000L), plotB + xAxisH * 0.62f, tickPaint)
            s += secStep
        }
    }

    private fun drawTitleArea(canvas: Canvas, frameTimeMs: Long) {
        val topPad = h * 0.020f
        val titleY = topPad + titlePaint.textSize

        // Profile name — left
        titlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(log.profileName, plotL, titleY, titlePaint)

        // Elapsed time — right
        val sec = frameTimeMs / 1000; val frac = (frameTimeMs % 1000) / 100
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${sec}.${frac}s", plotR, titleY, timePaint)

        // State badge — pill below the title, left-aligned
        val activeBand = bands.lastOrNull { it.startMs <= frameTimeMs }
        if (activeBand != null) {
            val badgeText = activeBand.name.uppercase()
            badgeTextPaint.textAlign = Paint.Align.LEFT
            val hPad = h * 0.016f
            val vPad = h * 0.008f
            val bw = badgeTextPaint.measureText(badgeText) + hPad * 2f
            val bh = badgeTextPaint.textSize + vPad * 2f
            val bx = plotL
            val by = titleY + h * 0.014f
            val rect = RectF(bx, by, bx + bw, by + bh)
            val r = bh / 2f
            badgePaint.color = EW.badgeBg()
            badgeBorder.color = EW.badgeBorder()
            canvas.drawRoundRect(rect, r, r, badgePaint)
            canvas.drawRoundRect(rect, r, r, badgeBorder)
            canvas.drawText(badgeText, bx + hPad, by + bh * 0.72f, badgeTextPaint)
        }

        // Subtle legend — top right, small dots + labels
        legendPaint.textAlign = Paint.Align.RIGHT
        val ldotR = h * 0.004f
        val lItems = listOf(flowDotPaint to "Flow", pressureDotPaint to "Pressure", weightDotPaint to "Weight")
        var lx = plotR
        val ly = titleY
        lItems.reversed().forEach { (dot, label) ->
            canvas.drawText(label, lx, ly, legendPaint)
            lx -= legendPaint.measureText(label) + ldotR * 3f
            canvas.drawCircle(lx, ly - ldotR * 0.6f, ldotR, dot)
            lx -= ldotR * 4.5f
        }
    }

    private fun drawMetricsCard(canvas: Canvas, samples: List<ShotSample>) {
        val last = samples.lastOrNull()
        val cardTop   = plotB + cardPad
        val cardBot   = cardTop + cardH
        val rect      = RectF(plotL, cardTop, plotR, cardBot)
        val radius    = cardH * 0.30f

        canvas.drawRoundRect(rect, radius, radius, cardBgPaint)
        canvas.drawRoundRect(rect, radius, radius, cardBorderPaint)

        data class Metric(val color: Int, val label: String, val value: String)
        val metrics = listOf(
            Metric(EW.flow,     "Flow",     if (last != null) "${"%.2f".format(last.flowGps)} g/s" else "--"),
            Metric(EW.pressure, "Pressure", if (last?.commandedPressureBar != null) "${"%.1f".format(last.commandedPressureBar)} bar" else "--"),
            Metric(EW.weight,   "Weight",   if (last != null) "${"%.1f".format(last.weightG)} g" else "--")
        )
        val colW = rect.width() / metrics.size
        val labelY = cardTop + cardH * 0.40f
        val valueY = cardTop + cardH * 0.80f

        metrics.forEachIndexed { i, m ->
            val cx = plotL + colW * i + colW / 2f

            // Column divider (skip first)
            if (i > 0) {
                canvas.drawLine(plotL + colW * i, cardTop + cardH * 0.14f,
                    plotL + colW * i, cardTop + cardH * 0.86f, cardDividerPaint)
            }

            metricLabelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(m.label, cx, labelY, metricLabelPaint)

            metricValuePaint.color = m.color
            metricValuePaint.textAlign = Paint.Align.CENTER
            canvas.drawText(m.value, cx, valueY, metricValuePaint)
        }
    }
}
