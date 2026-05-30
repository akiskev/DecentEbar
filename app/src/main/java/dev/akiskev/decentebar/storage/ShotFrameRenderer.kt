package dev.akiskev.decentebar.storage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.akiskev.decentebar.model.ShotEventType
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotSample
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

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

    // Stage info: list of (stageName, startMs, color)
    private data class Band(val name: String, val startMs: Long, val endMs: Long, val color: Int)
    private val bands: List<Band> = run {
        val ALPHA = 0x28
        val palette = intArrayOf(
            Color.argb(ALPHA, 60,  80,  200),
            Color.argb(ALPHA, 50,  180, 80),
            Color.argb(ALPHA, 200, 70,  60),
            Color.argb(ALPHA, 180, 160, 40),
            Color.argb(ALPHA, 50,  170, 180),
            Color.argb(ALPHA, 140, 60,  200)
        )
        val result = mutableListOf<Band>()
        var i = 0
        var lastName = ""
        var bandStart = 0L
        log.samples.forEach { s ->
            if (s.stageName != lastName) {
                if (lastName.isNotEmpty()) result += Band(lastName, bandStart, s.timeMs, palette[result.size % palette.size])
                lastName = s.stageName
                bandStart = s.timeMs
                i++
            }
        }
        if (lastName.isNotEmpty()) result += Band(lastName, bandStart, log.samples.lastOrNull()?.timeMs ?: bandStart, palette[result.size % palette.size])
        result
    }

    private val firstDropMs: Long? = log.events
        .firstOrNull { it.type == ShotEventType.FIRST_DROP }?.timeMs

    // Paints
    private val bgPaint = Paint().apply { color = Color.rgb(16, 16, 18) }
    private val gridPaint = Paint().apply { color = Color.argb(40, 200, 200, 200); strokeWidth = 1f; style = Paint.Style.STROKE }
    private val axisPaint = Paint().apply { color = Color.argb(100, 200, 200, 200); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val bandPaint = Paint().apply { style = Paint.Style.FILL }
    private val flowPaint = Paint().apply { color = Color.rgb(91, 156, 246); strokeWidth = (h * 0.003f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val pressurePaint = Paint().apply { color = Color.rgb(246, 162, 91); strokeWidth = (h * 0.003f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val weightPaint = Paint().apply { color = Color.rgb(91, 246, 162); strokeWidth = (h * 0.003f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val dropPaint = Paint().apply { color = Color.argb(180, 210, 70, 70); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val labelPaint = Paint().apply { color = Color.argb(140, 200, 200, 200); textSize = h * 0.028f; isAntiAlias = true; typeface = Typeface.MONOSPACE }
    private val stageLabelPaint = Paint().apply { color = Color.argb(140, 200, 200, 200); textSize = h * 0.026f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val valuePaint = Paint().apply { color = Color.rgb(230, 230, 230); textSize = h * 0.038f; isAntiAlias = true; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val titlePaint = Paint().apply { color = Color.rgb(240, 240, 240); textSize = h * 0.05f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val dimPaint = Paint().apply { color = Color.argb(180, 140, 140, 140); textSize = h * 0.032f; isAntiAlias = true }
    private val flowDotPaint = Paint().apply { color = Color.rgb(91, 156, 246); style = Paint.Style.FILL; isAntiAlias = true }
    private val pressureDotPaint = Paint().apply { color = Color.rgb(246, 162, 91); style = Paint.Style.FILL; isAntiAlias = true }
    private val weightDotPaint = Paint().apply { color = Color.rgb(91, 246, 162); style = Paint.Style.FILL; isAntiAlias = true }

    fun render(canvas: Canvas, frameTimeMs: Long) {
        val visibleSamples = log.samples.filter { it.timeMs <= frameTimeMs }

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        drawBands(canvas, frameTimeMs)
        drawGrid(canvas)
        drawAxes(canvas)
        drawFirstDrop(canvas, frameTimeMs)
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
            // Stage label at top of band
            val midX = (x1 + x2) / 2f
            canvas.drawText(b.name, midX, plotT + stageLabelPaint.textSize + 4f, stageLabelPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        // Horizontal grid lines (left axis)
        val step = if (maxLeftY > 10f) 2f else 1f
        var v = step
        while (v < maxLeftY) {
            val y = yPxLeft(v)
            canvas.drawLine(plotL, y, plotR, y, gridPaint)
            v += step
        }
        // Vertical grid every 5 seconds
        val durSec = (shotDurationMs / 1000).toInt()
        val secStep = when {
            durSec > 60 -> 10
            durSec > 30 -> 5
            else -> 5
        }
        var s = secStep
        while (s < durSec) {
            val x = xPx(s * 1000L)
            canvas.drawLine(x, plotT, x, plotB, gridPaint)
            s += secStep
        }
    }

    private fun drawAxes(canvas: Canvas) {
        canvas.drawLine(plotL, plotT, plotL, plotB, axisPaint)   // left axis
        canvas.drawLine(plotR, plotT, plotR, plotB, axisPaint)   // right axis
        canvas.drawLine(plotL, plotB, plotR, plotB, axisPaint)   // x axis
    }

    private fun drawFirstDrop(canvas: Canvas, upToMs: Long) {
        val t = firstDropMs ?: return
        if (t > upToMs) return
        val x = xPx(t)
        dropPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 5f), 0f)
        canvas.drawLine(x, plotT, x, plotB, dropPaint)
        dropPaint.pathEffect = null
    }

    private fun drawLines(canvas: Canvas, samples: List<ShotSample>) {
        if (samples.size < 2) return

        val flowPath = Path()
        val pressurePath = Path()
        val weightPath = Path()

        var firstFlow = true
        var firstPressure = true
        var firstWeight = true

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
        // Left axis ticks
        val step = if (maxLeftY > 10f) 2f else 1f
        var v = 0f
        while (v <= maxLeftY) {
            val y = yPxLeft(v) + labelPaint.textSize * 0.35f
            canvas.drawText(v.toInt().toString(), plotL - 8f, y, labelPaint)
            v += step
        }
        // Right axis ticks
        labelPaint.textAlign = Paint.Align.LEFT
        val wStep = if (maxWeight > 20f) 5f else 2f
        var wv = 0f
        while (wv <= maxWeight) {
            val y = yPxRight(wv) + labelPaint.textSize * 0.35f
            canvas.drawText(wv.toInt().toString(), plotR + 8f, y, labelPaint)
            wv += wStep
        }
        // X axis ticks
        labelPaint.textAlign = Paint.Align.CENTER
        val durSec = (shotDurationMs / 1000).toInt()
        val secStep = when { durSec > 60 -> 10; durSec > 30 -> 5; else -> 5 }
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

        data class Row(val paint: Paint, val dot: Paint, val label: String, val value: String)
        val rows = listOf(
            Row(Paint().apply { color = Color.rgb(91, 156, 246); textSize = dimPaint.textSize; isAntiAlias = true },
                flowDotPaint, "Flow", if (last != null) "${"%.2f".format(last.flowGps)} g/s" else "--"),
            Row(Paint().apply { color = Color.rgb(246, 162, 91); textSize = dimPaint.textSize; isAntiAlias = true },
                pressureDotPaint, "Pressure", if (last?.commandedPressureBar != null) "${"%.1f".format(last.commandedPressureBar)} bar" else "--"),
            Row(Paint().apply { color = Color.rgb(91, 246, 162); textSize = dimPaint.textSize; isAntiAlias = true },
                weightDotPaint, "Weight", if (last != null) "${"%.1f".format(last.weightG)} g" else "--")
        )

        rows.forEach { row ->
            canvas.drawCircle(x0, y - dotR * 0.5f, dotR, row.dot)
            row.paint.textAlign = Paint.Align.LEFT
            valuePaint.color = Color.rgb(220, 220, 220)
            val label = "${row.label}  "
            canvas.drawText(label, x0 + dotR * 2.5f, y, row.paint)
            valuePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(row.value, x0 + dotR * 2.5f + row.paint.measureText(label), y, valuePaint)
            y += lineH
        }
    }

    private fun drawTitle(canvas: Canvas, frameTimeMs: Long) {
        val sec = frameTimeMs / 1000
        val frac = (frameTimeMs % 1000) / 100
        val timeStr = "${sec}.${frac}s"
        val profile = log.profileName

        titlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(profile, plotL, plotT - titlePaint.textSize * 0.4f, titlePaint)

        dimPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(timeStr, plotR, plotT - dimPaint.textSize * 0.3f, dimPaint)

        // Legend dots (top right)
        val lx = plotR - w * 0.01f
        val ly = plotT + titlePaint.textSize * 0.6f
        val lr = h * 0.005f
        val legendItems = listOf(
            Triple(flowDotPaint, "Flow (g/s)", dimPaint.textSize),
            Triple(pressureDotPaint, "Pressure (bar)", dimPaint.textSize),
            Triple(weightDotPaint, "Weight (g)", dimPaint.textSize)
        )
        dimPaint.textAlign = Paint.Align.RIGHT
        var legendX = lx
        legendItems.reversed().forEach { (dot, label, ts) ->
            dimPaint.textSize = ts
            canvas.drawText(label, legendX, ly, dimPaint)
            legendX -= dimPaint.measureText(label) + lr * 3
            canvas.drawCircle(legendX + lr, ly - lr * 0.5f, lr, dot)
            legendX -= lr * 3.5f
        }
    }
}
