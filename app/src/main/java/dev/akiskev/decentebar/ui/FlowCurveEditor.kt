package dev.akiskev.decentebar.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.akiskev.decentebar.engine.CurveMath
import dev.akiskev.decentebar.model.CurvePoint
import dev.akiskev.decentebar.model.PressureCurveAxis
import dev.akiskev.decentebar.model.PressureCurvePoint
import dev.akiskev.decentebar.util.formatDecimals
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

private enum class CurveMode { FREEHAND, EDIT }

private const val HANDLE_R_DP = 7f
private const val TOUCH_R_DP = 26f
private const val MIN_GAP = 0.01          // keep interior points strictly ordered
private const val FREEHAND_BUCKETS = 12   // points a freehand stroke is resampled into

/**
 * Generic full-screen curve editor: the user draws Y-vs-X by hand (freehand swipe to rough in, then
 * tap/drag handles to refine). Points are stored as [CurvePoint] (timePct ∈ [0,1] = the X fraction,
 * flowGps = the Y value, whatever it represents) and kept in [CurveMath.cleanKnots] form. The X axis
 * spans `0..xMax`, the Y axis `0..yMax`; both axes show tick labels and the active point shows a live
 * value bubble. The [summary] lambda renders the top-bar readout (e.g. yield for flow, peak for
 * pressure). Used by [FlowCurveEditorContent] and [PressureCurveEditorContent].
 */
@Composable
internal fun CurveEditorContent(
    initialPoints: List<CurvePoint>,
    initialXMax: Double,
    xLabel: String,
    xUnit: String,
    xRange: ClosedFloatingPointRange<Float>,
    xDecimals: Int,
    initialYMax: Double,
    yLabel: String,
    yUnit: String,
    yRange: ClosedFloatingPointRange<Float>,
    yDecimals: Int,
    summary: (points: List<CurvePoint>, xMax: Double) -> String,
    onCancel: () -> Unit,
    onConfirm: (points: List<CurvePoint>, xMax: Double, yMax: Double) -> Unit,
) {
    val density = LocalDensity.current
    // Per-side margins: room for Y tick labels on the left and X tick labels on the bottom.
    val mL = with(density) { 30f.dp.toPx() }
    val mR = with(density) { 12f.dp.toPx() }
    val mT = with(density) { 10f.dp.toPx() }
    val mB = with(density) { 22f.dp.toPx() }
    val handleR = with(density) { HANDLE_R_DP.dp.toPx() }
    val touchR = with(density) { TOUCH_R_DP.dp.toPx() }

    var points by remember { mutableStateOf(CurveMath.cleanKnots(initialPoints)) }
    var xMax by remember { mutableStateOf(initialXMax.coerceAtLeast(1.0)) }
    var yMax by remember { mutableStateOf(initialYMax.coerceAtLeast(0.5)) }
    var mode by remember { mutableStateOf(CurveMode.EDIT) }
    var activePoint by remember { mutableStateOf<Int?>(null) }
    val undo = remember { mutableStateListOf<List<CurvePoint>>() }
    val stroke = remember { mutableStateListOf<Offset>() }

    // Native paint for axis tick labels and the active-point value bubble (Compose DrawScope has no
    // text primitive without a TextMeasurer; the native canvas is simplest and matches the MP4 path).
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    labelPaint.color = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    labelPaint.textSize = with(density) { 12f.sp.toPx() }
    val labelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)

    fun commit(next: List<CurvePoint>) {
        undo.add(points)
        if (undo.size > 30) undo.removeAt(0)
        points = CurveMath.cleanKnots(next)
    }

    fun nudgeActive(dxPct: Double, dy: Double) {
        val idx = activePoint ?: return
        if (idx !in points.indices) return
        val next = points.toMutableList()
        val endpoint = idx == 0 || idx == next.lastIndex
        val newPct = if (endpoint) {
            next[idx].timePct
        } else {
            (next[idx].timePct + dxPct)
                .coerceIn(next[idx - 1].timePct + MIN_GAP, next[idx + 1].timePct - MIN_GAP)
        }
        next[idx] = CurvePoint(newPct, (next[idx].flowGps + dy).coerceIn(0.0, yMax))
        commit(next)
        activePoint = idx.coerceAtMost(next.lastIndex)
    }

    fun deleteActive() {
        val idx = activePoint ?: return
        if (idx in points.indices && points.size > 2) {
            commit(points.filterIndexed { i, _ -> i != idx })
            activePoint = null
        }
    }

    val cs = MaterialTheme.colorScheme
    val lineColor = cs.primary
    val fillColor = cs.primary.copy(alpha = 0.14f)
    val gridColor = cs.outline.copy(alpha = 0.25f)
    val axisColor = cs.outline.copy(alpha = 0.5f)
    val handleColor = cs.tertiary
    val strokeColor = cs.primary.copy(alpha = 0.45f)

    Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // One compact top bar: summary readout + mode toggle + edit actions + Cancel/Done.
            // Landscape is wide but short, so all chrome lives on this row + one slider row; the canvas
            // takes everything in between.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(summary(points, xMax), style = MaterialTheme.typography.titleSmall, color = cs.primary)
                Spacer(Modifier.width(8.dp))
                SegmentedChoice(
                    options = CurveMode.entries,
                    selected = mode,
                    onSelected = { mode = it },
                    label = { if (it == CurveMode.FREEHAND) "Freehand" else "Edit" }
                )
                TextButton(onClick = { undo.removeLastOrNull()?.let { points = it } }, enabled = undo.isNotEmpty()) { Text("Undo") }
                TextButton(onClick = { commit(listOf(CurvePoint(0.0, yMax * 0.5), CurvePoint(1.0, yMax * 0.5))) }) { Text("Clear") }
                TextButton(
                    onClick = { nudgeActive(-0.01, 0.0) },
                    enabled = activePoint != null && activePoint !in listOf(0, points.lastIndex)
                ) { Text("X-") }
                TextButton(
                    onClick = { nudgeActive(0.01, 0.0) },
                    enabled = activePoint != null && activePoint !in listOf(0, points.lastIndex)
                ) { Text("X+") }
                TextButton(onClick = { nudgeActive(0.0, yMax * 0.02) }, enabled = activePoint != null) { Text("Y+") }
                TextButton(onClick = { nudgeActive(0.0, -yMax * 0.02) }, enabled = activePoint != null) { Text("Y-") }
                TextButton(onClick = { deleteActive() }, enabled = activePoint != null && points.size > 2) { Text("Delete point") }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = { onConfirm(points, xMax, yMax) }) { Text("Done") }
            }

            Spacer(Modifier.height(6.dp))

            // The plot. Edit mode: tap a handle = select (show value), tap empty = add, long-press a
            // handle = delete, drag = move. Freehand: drag = stroke. Keyed on mode/xMax/yMax so the
            // mapping is current; point reads use the live `points` state.
            val gestures = when (mode) {
                CurveMode.FREEHAND -> Modifier.pointerInput(xMax, yMax) {
                    detectDragGestures(
                        onDragStart = { off -> stroke.clear(); stroke.add(off) },
                        onDrag = { change, _ -> change.consume(); stroke.add(change.position) },
                        onDragEnd = {
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            if (stroke.size >= 2) {
                                commit(resampleStroke(stroke.toList(), w, h, mL, mR, mT, mB, yMax))
                                activePoint = null
                            }
                            stroke.clear()
                        },
                        onDragCancel = { stroke.clear() }
                    )
                }
                CurveMode.EDIT -> Modifier
                    .pointerInput(xMax, yMax) {
                        detectTapGestures(
                            onTap = { off ->
                                val w = size.width.toFloat(); val h = size.height.toFloat()
                                val cur = points
                                val hit = nearestHandle(off, cur, w, h, mL, mR, mT, mB, yMax, touchR)
                                if (hit != null) {
                                    activePoint = hit          // select → show its value
                                } else {
                                    val np = CurvePoint(pctForX(off.x, w, mL, mR), valueForY(off.y, h, mT, mB, yMax))
                                    commit(cur + np)
                                    activePoint = points.indexOfFirst { abs(it.timePct - np.timePct) < 1e-6 }
                                        .takeIf { it >= 0 }
                                }
                            },
                            onLongPress = { off ->
                                val w = size.width.toFloat(); val h = size.height.toFloat()
                                val cur = points
                                val hit = nearestHandle(off, cur, w, h, mL, mR, mT, mB, yMax, touchR)
                                if (hit != null && cur.size > 2) {
                                    commit(cur.filterIndexed { i, _ -> i != hit })
                                    activePoint = null
                                }
                            }
                        )
                    }
                    .pointerInput(xMax, yMax) {
                        var grab = -1
                        detectDragGestures(
                            onDragStart = { off ->
                                val w = size.width.toFloat(); val h = size.height.toFloat()
                                grab = nearestHandle(off, points, w, h, mL, mR, mT, mB, yMax, touchR) ?: -1
                                if (grab >= 0) { undo.add(points); activePoint = grab }
                            },
                            onDrag = { change, _ ->
                                if (grab in points.indices) {
                                    change.consume()
                                    val w = size.width.toFloat(); val h = size.height.toFloat()
                                    val cur = points.toMutableList()
                                    val endpoint = grab == 0 || grab == cur.lastIndex
                                    val newY = valueForY(change.position.y, h, mT, mB, yMax)
                                    val newPct = if (endpoint) cur[grab].timePct else
                                        pctForX(change.position.x, w, mL, mR)
                                            .coerceIn(cur[grab - 1].timePct + MIN_GAP, cur[grab + 1].timePct - MIN_GAP)
                                    cur[grab] = CurvePoint(newPct, newY)
                                    points = cur   // already ordered; don't re-clean mid-drag
                                }
                            },
                            onDragEnd = { grab = -1; points = CurveMath.cleanKnots(points) },
                            onDragCancel = { grab = -1 }
                        )
                    }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Curve editor canvas" }
                        .then(gestures)
                ) {
                    drawCurve(
                        points = points, yMax = yMax, xMax = xMax,
                        mL = mL, mR = mR, mT = mT, mB = mB,
                        handleR = handleR, lineColor = lineColor, fillColor = fillColor,
                        gridColor = gridColor, axisColor = axisColor, handleColor = handleColor,
                        stroke = if (stroke.isNotEmpty()) stroke.toList() else null, strokeColor = strokeColor,
                        labelPaint = labelPaint, labelBg = labelBg, activeIndex = activePoint,
                        xUnit = xUnit, yUnit = yUnit, xDecimals = xDecimals, yDecimals = yDecimals
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // X-scale + Y-scale sliders side by side (one row) so the canvas keeps the height.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    SliderField(
                        label = xLabel, value = xMax, valueRange = xRange, steps = 0,
                        unit = xUnit, decimals = xDecimals, onChange = { xMax = it }
                    )
                }
                Box(Modifier.weight(1f)) {
                    SliderField(
                        label = yLabel, value = yMax, valueRange = yRange, steps = 0,
                        unit = yUnit, decimals = yDecimals, onChange = { yMax = it }
                    )
                }
            }
            Text(
                "Freehand: drag to draw. Edit: tap a point to read it, drag to move, long-press to delete, tap empty to add.",
                style = MaterialTheme.typography.labelSmall,
                color = cs.outline,
                maxLines = 2
            )
        }
    }
}

/** Flow-curve editor (yield/time): X = time s, Y = flow g/s, summary = the computed yield (area). */
@Composable
internal fun FlowCurveEditorContent(
    initialPoints: List<CurvePoint>,
    initialDurationS: Double,
    initialMaxFlowGps: Double,
    onCancel: () -> Unit,
    onConfirm: (points: List<CurvePoint>, yieldG: Double, durationS: Double, maxFlowGps: Double) -> Unit,
) {
    CurveEditorContent(
        initialPoints = initialPoints,
        initialXMax = initialDurationS, xLabel = "Duration", xUnit = "s", xRange = 5f..90f, xDecimals = 0,
        initialYMax = initialMaxFlowGps, yLabel = "Flow scale", yUnit = "g/s", yRange = 1f..4f, yDecimals = 2,
        summary = { pts, x -> "Yield ≈ ${CurveMath.areaG(pts, x).formatDecimals(1)} g · ${x.formatDecimals(0)} s" },
        onCancel = onCancel,
        onConfirm = { pts, x, y -> onConfirm(pts, CurveMath.areaG(pts, x), x, y) }
    )
}

/** Pressure-curve editor: X = time s or weight g per [axis], Y = pressure bar. No area/prediction. */
@Composable
internal fun PressureCurveEditorContent(
    initialPoints: List<PressureCurvePoint>,
    axis: PressureCurveAxis,
    initialXMax: Double,
    initialMaxPressureBar: Double,
    onCancel: () -> Unit,
    onConfirm: (points: List<PressureCurvePoint>, xMax: Double, maxPressureBar: Double) -> Unit,
) {
    val time = axis == PressureCurveAxis.TIME
    CurveEditorContent(
        initialPoints = initialPoints.map { CurvePoint(it.xPct, it.pressureBar) },
        initialXMax = initialXMax,
        xLabel = if (time) "Duration" else "Max weight",
        xUnit = if (time) "s" else "g",
        xRange = if (time) 5f..90f else 10f..120f,
        xDecimals = 0,
        initialYMax = initialMaxPressureBar, yLabel = "Max pressure", yUnit = "bar", yRange = 1f..12f, yDecimals = 1,
        summary = { pts, _ -> "Peak ${(pts.maxOfOrNull { it.flowGps } ?: 0.0).formatDecimals(1)} bar · ${pts.size} pts" },
        onCancel = onCancel,
        onConfirm = { pts, x, y -> onConfirm(pts.map { PressureCurvePoint(it.timePct, it.flowGps) }, x, y) }
    )
}

/** Non-interactive preview of a curve (same draw path, no grid/handles/labels). */
@Composable
internal fun CurveThumbnail(
    points: List<CurvePoint>,
    yMax: Double,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val m = with(LocalDensity.current) { 4f.dp.toPx() }
    val line = cs.primary
    val fill = cs.primary.copy(alpha = 0.14f)
    val axis = cs.outline.copy(alpha = 0.4f)
    Canvas(modifier = modifier) {
        drawCurve(
            points = points, yMax = yMax.coerceAtLeast(0.5), xMax = 1.0,
            mL = m, mR = m, mT = m, mB = m,
            handleR = 0f, lineColor = line, fillColor = fill, gridColor = Color.Transparent,
            axisColor = axis, handleColor = null, stroke = null, strokeColor = Color.Transparent,
            labelPaint = null, labelBg = Color.Transparent, activeIndex = null,
            xUnit = "", yUnit = "", xDecimals = 0, yDecimals = 0
        )
    }
}

// ── Drawing ───────────────────────────────────────────────────────────────────

private fun DrawScope.drawCurve(
    points: List<CurvePoint>,
    yMax: Double,
    xMax: Double,
    mL: Float,
    mR: Float,
    mT: Float,
    mB: Float,
    handleR: Float,
    lineColor: Color,
    fillColor: Color,
    gridColor: Color,
    axisColor: Color,
    handleColor: Color?,
    stroke: List<Offset>?,
    strokeColor: Color,
    labelPaint: Paint?,
    labelBg: Color,
    activeIndex: Int?,
    xUnit: String,
    yUnit: String,
    xDecimals: Int,
    yDecimals: Int,
) {
    val w = size.width
    val h = size.height
    val plotL = mL
    val plotR = w - mR
    val plotB = h - mB
    val plotT = mT

    val xStep = niceStep(xMax / 5.0)
    val yStep = niceStep(yMax / 5.0)

    if (gridColor != Color.Transparent) {
        var s = xStep
        while (s < xMax) {
            val x = pctToX(s / xMax, w, mL, mR)
            drawLine(gridColor, Offset(x, plotT), Offset(x, plotB), 1f)
            s += xStep
        }
        var f = yStep
        while (f < yMax) {
            val y = valueToY(f, h, mT, mB, yMax)
            drawLine(gridColor, Offset(plotL, y), Offset(plotR, y), 1f)
            f += yStep
        }
    }

    drawLine(axisColor, Offset(plotL, plotB), Offset(plotR, plotB), 2f)
    drawLine(axisColor, Offset(plotL, plotT), Offset(plotL, plotB), 2f)

    // Axis tick labels: Y up the left, X along the bottom.
    if (labelPaint != null) {
        val canvas = drawContext.canvas.nativeCanvas
        val ts = labelPaint.textSize
        val yDec = if (yStep < 1.0) 1 else 0
        labelPaint.textAlign = Paint.Align.RIGHT
        var f = 0.0
        while (f <= yMax + 1e-6) {
            canvas.drawText(f.formatDecimals(yDec), plotL - 4f, valueToY(f, h, mT, mB, yMax) + ts * 0.35f, labelPaint)
            f += yStep
        }
        labelPaint.textAlign = Paint.Align.CENTER
        var s = 0.0
        while (s <= xMax + 1e-6) {
            canvas.drawText(s.formatDecimals(xDecimals), pctToX(s / xMax, w, mL, mR), plotB + ts + 3f, labelPaint)
            s += xStep
        }
    }

    val screen = points.map { Offset(pctToX(it.timePct, w, mL, mR), valueToY(it.flowGps, h, mT, mB, yMax)) }
    if (screen.size >= 2) {
        val area = Path().apply {
            moveTo(screen.first().x, plotB)
            screen.forEach { lineTo(it.x, it.y) }
            lineTo(screen.last().x, plotB)
            close()
        }
        drawPath(area, fillColor)
        for (i in 0 until screen.size - 1) {
            drawLine(lineColor, screen[i], screen[i + 1], 3f, cap = StrokeCap.Round)
        }
    }

    if (stroke != null && stroke.size >= 2) {
        val sp = Path().apply {
            moveTo(stroke.first().x, stroke.first().y)
            stroke.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(sp, strokeColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }

    if (handleColor != null && handleR > 0f) {
        screen.forEachIndexed { i, c ->
            drawCircle(handleColor, if (i == activeIndex) handleR * 1.35f else handleR, c)
        }
    }

    // Value bubble for the active (selected/dragged) point — e.g. "12s · 6.0 bar".
    if (labelPaint != null && activeIndex != null && activeIndex in points.indices) {
        val p = points[activeIndex]
        val c = Offset(pctToX(p.timePct, w, mL, mR), valueToY(p.flowGps, h, mT, mB, yMax))
        val text = "${(p.timePct * xMax).formatDecimals(xDecimals)}$xUnit · ${p.flowGps.formatDecimals(yDecimals)} $yUnit"
        labelPaint.textAlign = Paint.Align.LEFT
        val ts = labelPaint.textSize
        val tw = labelPaint.measureText(text)
        val pad = 6f
        val gap = handleR * 5f
        var tx = c.x + gap
        var ty = c.y - gap
        if (tx + tw + pad > plotR) tx = c.x - gap - tw
        if (ty - ts - pad < plotT) ty = c.y + gap + ts
        if (ty + pad > plotB) ty = plotB - pad
        if (tx - pad < plotL) tx = plotL + pad
        drawRect(labelBg, topLeft = Offset(tx - pad, ty - ts - pad), size = Size(tw + pad * 2, ts + pad * 2))
        drawContext.canvas.nativeCanvas.drawText(text, tx, ty, labelPaint)
    }
}

/** A "nice" axis tick step (1/2/5 × 10ⁿ) near [target]. */
private fun niceStep(target: Double): Double {
    if (target <= 0.0) return 1.0
    val mag = 10.0.pow(floor(log10(target)))
    val norm = target / mag
    val s = when {
        norm < 1.5 -> 1.0
        norm < 3.5 -> 2.0
        norm < 7.5 -> 5.0
        else -> 10.0
    }
    return s * mag
}

// ── Coordinate mapping (top-level so both the DrawScope and pointerInput can share it) ──

private fun pctToX(p: Double, w: Float, mL: Float, mR: Float): Float =
    mL + p.toFloat() * (w - mL - mR)

private fun valueToY(v: Double, h: Float, mT: Float, mB: Float, yMax: Double): Float {
    val plotB = h - mB
    val plotT = mT
    val frac = if (yMax <= 0.0) 0f else (v / yMax).coerceIn(0.0, 1.0).toFloat()
    return plotB - frac * (plotB - plotT)
}

private fun pctForX(x: Float, w: Float, mL: Float, mR: Float): Double =
    ((x - mL) / (w - mL - mR)).coerceIn(0f, 1f).toDouble()

private fun valueForY(y: Float, h: Float, mT: Float, mB: Float, yMax: Double): Double {
    val plotB = h - mB
    val plotT = mT
    val frac = ((plotB - y) / (plotB - plotT)).coerceIn(0f, 1f)
    return frac.toDouble() * yMax
}

/** Index of the point whose handle is within [touchR] px of [off], or null. */
private fun nearestHandle(
    off: Offset, points: List<CurvePoint>, w: Float, h: Float,
    mL: Float, mR: Float, mT: Float, mB: Float, yMax: Double, touchR: Float
): Int? {
    var best = -1
    var bestD = touchR
    points.forEachIndexed { i, p ->
        val d = hypot(off.x - pctToX(p.timePct, w, mL, mR), off.y - valueToY(p.flowGps, h, mT, mB, yMax))
        if (d <= bestD) { bestD = d; best = i }
    }
    return if (best >= 0) best else null
}

/** Resample a raw freehand stroke (screen px) into ~[FREEHAND_BUCKETS] ordered control points. */
private fun resampleStroke(
    stroke: List<Offset>, w: Float, h: Float, mL: Float, mR: Float, mT: Float, mB: Float, yMax: Double
): List<CurvePoint> {
    val sorted = stroke
        .map { CurvePoint(pctForX(it.x, w, mL, mR), valueForY(it.y, h, mT, mB, yMax)) }
        .sortedBy { it.timePct }
    if (sorted.size < 2) return sorted
    val buckets = Array(FREEHAND_BUCKETS) { mutableListOf<Double>() }
    sorted.forEach {
        val idx = (it.timePct * FREEHAND_BUCKETS).toInt().coerceIn(0, FREEHAND_BUCKETS - 1)
        buckets[idx].add(it.flowGps)
    }
    val out = mutableListOf<CurvePoint>()
    out.add(CurvePoint(0.0, sorted.first().flowGps))
    buckets.forEachIndexed { i, b -> if (b.isNotEmpty()) out.add(CurvePoint((i + 0.5) / FREEHAND_BUCKETS, b.average())) }
    out.add(CurvePoint(1.0, sorted.last().flowGps))
    return CurveMath.cleanKnots(out)
}
