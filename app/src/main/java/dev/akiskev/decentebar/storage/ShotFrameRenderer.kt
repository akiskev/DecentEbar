package dev.akiskev.decentebar.storage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.akiskev.decentebar.model.ShotEventType
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotSample
import kotlin.math.ceil
import kotlin.math.max

// Material Design dark-theme palette
private object MD {
    // Surfaces
    val background = Color.rgb(18, 18, 18)          // #121212

    // Data lines — MD accent colors
    val flow     = Color.rgb(68, 138, 255)           // Blue A200  #448AFF
    val pressure = Color.rgb(255, 110, 64)           // Deep Orange A200  #FF6E40
    val weight   = Color.rgb(100, 255, 218)          // Teal A200  #64FFDA
    val target   = Color.rgb(255, 193, 7)            // Amber 500  #FFC107  (target-flow line)

    // First-drop marker
    val firstDrop = Color.argb(200, 239, 83, 80)     // Red 400 #EF5350

    // Structure
    fun grid()  = Color.argb(18,  255, 255, 255)     // 7% white
    fun axis()  = Color.argb(59,  255, 255, 255)     // 23% white
    fun text87()= Color.argb(222, 255, 255, 255)     // high-emphasis
    fun text60()= Color.argb(153, 255, 255, 255)     // medium-emphasis
    fun text38()= Color.argb(97,  255, 255, 255)     // disabled/hint

    // Stage band fills — MD 900-series hues at 16% opacity
    val bands = intArrayOf(
        Color.argb(41, 26,  35,  126),   // Indigo 900
        Color.argb(41, 27,  94,  32 ),   // Green 900
        Color.argb(41, 183, 28,  28 ),   // Red 900
        Color.argb(41, 230, 81,  0  ),   // Deep Orange 900
        Color.argb(41, 0,   96,  100),   // Cyan 900
        Color.argb(41, 74,  20,  140)    // Purple 900
    )
}

class ShotFrameRenderer(private val log: ShotLog, private val w: Int, private val h: Int) {

    private val shotDurationMs: Long = run {
        val s = log.startedAtMs ?: 0L
        val e = log.stoppedAtMs ?: (s + (log.samples.lastOrNull()?.timeMs ?: 0L))
        max(1L, e - s)
    }

    // Layout
    private val padL = (w * 0.072f).toInt()
    private val padR = (w * 0.072f).toInt()
    private val padT = (h * 0.12f).toInt()
    private val padB = (h * 0.10f).toInt()
    private val plotL = padL.toFloat()
    private val plotR = (w - padR).toFloat()
    private val plotT = padT.toFloat()
    private val plotB = (h - padB).toFloat()
    private val plotW = plotR - plotL
    private val plotH = plotB - plotT

    // Data ranges
    private val maxLeftY: Float = run {
        val maxP = log.samples.mapNotNull { it.commandedPressureBar }.maxOrNull() ?: 9.0
        val maxF = log.samples.maxOfOrNull { it.flowGps } ?: 3.0
        (ceil(max(maxP, maxF * 2.5) / 2) * 2).toFloat().coerceAtLeast(10f)
    }
    private val maxWeight: Float = run {
        val mw = log.samples.maxOfOrNull { it.weightG } ?: 35.0
        (ceil(mw / 5) * 5).toFloat().coerceAtLeast(10f)
    }

    private data class Band(val name: String, val startMs: Long, val endMs: Long, val color: Int, val targetFlow: Double?)
    private val bands: List<Band> = run {
        val result = mutableListOf<Band>()
        var lastName = ""
        var bandStart = 0L
        log.samples.forEach { s ->
            if (s.stageName != lastName) {
                if (lastName.isNotEmpty()) {
                    result += Band(lastName, bandStart, s.timeMs,
                        MD.bands[result.size % MD.bands.size],
                        log.stageTargetFlows[lastName])
                }
                lastName = s.stageName
                bandStart = s.timeMs
            }
        }
        if (lastName.isNotEmpty()) result += Band(lastName, bandStart,
            log.samples.lastOrNull()?.timeMs ?: bandStart,
            MD.bands[result.size % MD.bands.size],
            log.stageTargetFlows[lastName])
        result
    }

    private val firstDropMs: Long? = log.events
        .firstOrNull { it.type == ShotEventType.FIRST_DROP }?.timeMs

    // Paints
    private val lw = (h * 0.003f)
    private val bgPaint      = Paint().apply { color = MD.background }
    private val gridPaint    = Paint().apply { color = MD.grid(); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val axisPaint    = Paint().apply { color = MD.axis(); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val bandPaint    = Paint().apply { style = Paint.Style.FILL }
    private val flowPaint    = Paint().apply { color = MD.flow; strokeWidth = lw; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val pressurePaint= Paint().apply { color = MD.pressure; strokeWidth = lw; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val weightPaint  = Paint().apply { color = MD.weight; strokeWidth = lw; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val targetPaint  = Paint().apply {
        color = Color.argb(160, 255, 193, 7)  // Amber 500 at 63% — dashed target-flow line
        strokeWidth = lw * 0.7f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(h * 0.012f, h * 0.008f), 0f)
    }
    private val dropPaint    = Paint().apply { color = MD.firstDrop; strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val labelPaint   = Paint().apply { color = MD.text60(); textSize = h * 0.028f; isAntiAlias = true; typeface = Typeface.MONOSPACE }
    private val stageLabelPaint = Paint().apply { color = MD.text38(); textSize = h * 0.026f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val valuePaint   = Paint().apply { color = MD.text87(); textSize = h * 0.038f; isAntiAlias = true; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val titlePaint   = Paint().apply { color = MD.text87(); textSize = h * 0.05f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val dimPaint     = Paint().apply { color = MD.text60(); textSize = h * 0.032f; isAntiAlias = true }
    private val flowDotPaint     = Paint().apply { color = MD.flow; style = Paint.Style.FILL; isAntiAlias = true }
    private val pressureDotPaint = Paint().apply { color = MD.pressure; style = Paint.Style.FILL; isAntiAlias = true }
    private val weightDotPaint   = Paint().apply { color = MD.weight; style = Paint.Style.FILL; isAntiAlias = true }

    fun render(canvas: Canvas, frameTimeMs: Long) {
        val visibleSamples = log.samples.filter { it.timeMs <= frameTimeMs }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        drawBands(canvas, frameTimeMs)
        drawGrid(canvas)
        drawAxes(canvas)
        drawFirstDrop(canvas, frameTimeMs)
        drawTargetFlowLines(canvas, frameTimeMs)
        drawLines(canvas, visibleSamples)
        drawCurrentDots(canvas, visibleSamples)
        drawAxisLabels(canvas)
        drawCurrentValues(canvas, visibleSamples, frameTimeMs)
        drawTitle(canvas, frameTimeMs)
    }

    private fun xPx(timeMs: Long): Float = plotL + (timeMs.toFloat() / shotDurationMs) * plotW
    private fun yPxLeft(value: Float): Float = plotB - (value / maxLeftY) * plotH
    private fun yPxRight(value: Float): Float = plotB - (value / maxWeight) * plotH

    private fun drawBands(canvas: Canvas, upToMs: Long) {
        bands.forEach { b ->
            if (b.startMs > upToMs) return@forEach
            bandPaint.color = b.color
            val x1 = xPx(b.startMs)
            val x2 = xPx(minOf(b.endMs, upToMs))
            canvas.drawRect(x1, plotT, x2, plotB, bandPaint)
            val midX = (x1 + x2) / 2f
            canvas.drawText(b.name, midX, plotT + stageLabelPaint.textSize + 4f, stageLabelPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val step = if (maxLeftY > 10f) 2f else 1f
        var v = step
        while (v < maxLeftY) {
            val y = yPxLeft(v)
            canvas.drawLine(plotL, y, plotR, y, gridPaint)
            v += step
        }
        val durSec = (shotDurationMs / 1000).toInt()
        val secStep = if (durSec > 60) 10 else 5
        var s = secStep
        while (s < durSec) {
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

    private fun drawFirstDrop(canvas: Canvas, upToMs: Long) {
        val t = firstDropMs ?: return
        if (t > upToMs) return
        val x = xPx(t)
        dropPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
        canvas.drawLine(x, plotT, x, plotB, dropPaint)
        dropPaint.pathEffect = null
    }

    private fun drawTargetFlowLines(canvas: Canvas, upToMs: Long) {
        bands.forEach { b ->
            val tf = b.targetFlow ?: return@forEach
            if (b.startMs > upToMs) return@forEach
            val x1 = xPx(b.startMs)
            val x2 = xPx(minOf(b.endMs, upToMs))
            val y = yPxLeft(tf.toFloat())
            canvas.drawLine(x1, y, x2, y, targetPaint)
        }
    }

    private fun drawLines(canvas: Canvas, samples: List<ShotSample>) {
        if (samples.size < 2) return
        val flowPath = Path()
        val pressurePath = Path()
        val weightPath = Path()
        var firstFlow = true; var firstPressure = true; var firstWeight = true
        samples.forEach { s ->
            val x = xPx(s.timeMs)
            val yFlow = yPxLeft(s.flowGps.toFloat())
            if (firstFlow) { flowPath.moveTo(x, yFlow); firstFlow = false } else flowPath.lineTo(x, yFlow)
            s.commandedPressureBar?.let { p ->
                val yP = yPxLeft(p.toFloat())
                if (firstPressure) { pressurePath.moveTo(x, yP); firstPressure = false } else pressurePath.lineTo(x, yP)
            }
            val yW = yPxRight(s.weightG.toFloat())
            if (firstWeight) { weightPath.moveTo(x, yW); firstWeight = false } else weightPath.lineTo(x, yW)
        }
        canvas.drawPath(weightPath, weightPaint)
        canvas.drawPath(pressurePath, pressurePaint)
        canvas.drawPath(flowPath, flowPaint)
    }

    private fun drawCurrentDots(canvas: Canvas, samples: List<ShotSample>) {
        val last = samples.lastOrNull() ?: return
        val x = xPx(last.timeMs)
        val r = h * 0.008f
        canvas.drawCircle(x, yPxLeft(last.flowGps.toFloat()), r, flowDotPaint)
        last.commandedPressureBar?.let { canvas.drawCircle(x, yPxLeft(it.toFloat()), r, pressureDotPaint) }
        canvas.drawCircle(x, yPxRight(last.weightG.toFloat()), r, weightDotPaint)
    }

    private fun drawAxisLabels(canvas: Canvas) {
        labelPaint.textAlign = Paint.Align.RIGHT
        val step = if (maxLeftY > 10f) 2f else 1f
        var v = 0f
        while (v <= maxLeftY) {
            val y = yPxLeft(v) + labelPaint.textSize * 0.35f
            canvas.drawText(v.toInt().toString(), plotL - 8f, y, labelPaint)
            v += step
        }
        labelPaint.textAlign = Paint.Align.LEFT
        val wStep = if (maxWeight > 20f) 5f else 2f
        var wv = 0f
        while (wv <= maxWeight) {
            val y = yPxRight(wv) + labelPaint.textSize * 0.35f
            canvas.drawText(wv.toInt().toString(), plotR + 8f, y, labelPaint)
            wv += wStep
        }
        labelPaint.textAlign = Paint.Align.CENTER
        val durSec = (shotDurationMs / 1000).toInt()
        val secStep = if (durSec > 60) 10 else 5
        var s = 0
        while (s <= durSec) {
            val x = xPx(s * 1000L)
            canvas.drawText("${s}s", x, plotB + labelPaint.textSize + 6f, labelPaint)
            s += secStep
        }
    }

    private fun drawCurrentValues(canvas: Canvas, samples: List<ShotSample>, frameTimeMs: Long) {
        val last = samples.lastOrNull()
        val dotR = h * 0.006f
        val lineH = valuePaint.textSize * 1.4f
        val x0 = plotL + plotW * 0.03f
        var y = plotT + lineH

        data class Row(val color: Int, val dot: Paint, val label: String, val value: String)
        val rows = listOf(
            Row(MD.flow,     flowDotPaint,     "Flow",     if (last != null) "${"%.2f".format(last.flowGps)} g/s" else "--"),
            Row(MD.pressure, pressureDotPaint, "Pressure", if (last?.commandedPressureBar != null) "${"%.1f".format(last.commandedPressureBar)} bar" else "--"),
            Row(MD.weight,   weightDotPaint,   "Weight",   if (last != null) "${"%.1f".format(last.weightG)} g" else "--")
        )
        val rowPaint = Paint().apply { isAntiAlias = true; textSize = dimPaint.textSize }
        rows.forEach { row ->
            canvas.drawCircle(x0, y - dotR * 0.5f, dotR, row.dot)
            rowPaint.color = row.color
            rowPaint.textAlign = Paint.Align.LEFT
            val label = "${row.label}  "
            canvas.drawText(label, x0 + dotR * 2.5f, y, rowPaint)
            valuePaint.color = MD.text87()
            valuePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(row.value, x0 + dotR * 2.5f + rowPaint.measureText(label), y, valuePaint)
            y += lineH
        }
    }

    private fun drawTitle(canvas: Canvas, frameTimeMs: Long) {
        val sec = frameTimeMs / 1000
        val frac = (frameTimeMs % 1000) / 100
        titlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(log.profileName, plotL, plotT - titlePaint.textSize * 0.4f, titlePaint)
        dimPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${sec}.${frac}s", plotR, plotT - dimPaint.textSize * 0.3f, dimPaint)

        // Legend (top-right, reversed so Flow is outermost)
        val lx = plotR - w * 0.01f
        val ly = plotT + titlePaint.textSize * 0.6f
        val lr = h * 0.005f
        val legendItems = listOf(
            flowDotPaint     to "Flow (g/s)",
            pressureDotPaint to "Pressure (bar)",
            weightDotPaint   to "Weight (g)"
        )
        dimPaint.textAlign = Paint.Align.RIGHT
        var legendX = lx
        legendItems.reversed().forEach { (dot, label) ->
            canvas.drawText(label, legendX, ly, dimPaint)
            legendX -= dimPaint.measureText(label) + lr * 3
            canvas.drawCircle(legendX + lr, ly - lr * 0.5f, lr, dot)
            legendX -= lr * 3.5f
        }
    }
}
