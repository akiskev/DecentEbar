package dev.akiskev.decentebar.storage

import dev.akiskev.decentebar.model.ShotDerivedMetrics
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotMetric
import dev.akiskev.decentebar.model.ShotMetricPoint
import dev.akiskev.decentebar.model.ShotSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShotCompareHtmlExporter {
    fun export(shotA: ShotLog, shotB: ShotLog): String {
        val scales = ShotCompareScaleCalculator.calculate(shotA, shotB)
        val title = "Shot Compare - ${displayLabel(shotA)} vs ${displayLabel(shotB)}"
        val embeddedA = ShotLogCodec.encode(shotA).replace("</", "<\\/")
        val embeddedB = ShotLogCodec.encode(shotB).replace("</", "<\\/")

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escHtml(title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0A0C0E;color:rgba(247,242,232,0.86);padding:20px;max-width:1180px}
h1{font-size:1.45em;font-weight:650;color:rgba(247,242,232,0.96);margin-bottom:4px}
h2{font-size:.92em;font-weight:650;color:rgba(247,242,232,0.76);margin-bottom:10px}
.meta{font-size:.82em;color:rgba(247,242,232,0.56);margin-bottom:18px}
.shots{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:18px}
.shot{background:#101316;border:1px solid rgba(247,242,232,0.14);border-radius:8px;padding:12px}
.shot b{display:block;color:rgba(247,242,232,0.94);font-size:.95em;margin-bottom:5px}
.shot span{display:block;color:rgba(247,242,232,0.58);font-size:.8em;line-height:1.45}
.chart-wrap{background:#101316;border:1px solid rgba(247,242,232,0.14);border-radius:8px;padding:14px 12px 8px;margin-bottom:16px}
.chart-wrap canvas{display:block;width:100%!important}
.legend-note{font-size:.78em;color:rgba(247,242,232,0.50);margin:-3px 0 10px}
table{width:100%;border-collapse:collapse;font-size:.8em;margin-top:4px}
th{text-align:left;padding:7px 9px;background:rgba(247,242,232,0.06);color:rgba(247,242,232,0.58);font-weight:600;border-bottom:1px solid rgba(247,242,232,0.12)}
td{padding:7px 9px;border-bottom:1px solid rgba(247,242,232,0.08);vertical-align:top}
.delta{color:rgba(247,242,232,0.62)}
footer{margin-top:22px;font-size:.76em;color:rgba(247,242,232,0.38);text-align:right}
@media(max-width:720px){body{padding:12px}.shots{grid-template-columns:1fr}.chart-wrap{padding:12px 8px 6px}}
</style>
</head>
<body>
<h1>Shot Compare</h1>
<p class="meta">${escHtml(title)}</p>
<section class="shots">
<div class="shot"><b>A solid - ${escHtml(displayLabel(shotA))}</b><span>${escHtml(shotMeta(shotA))}</span></div>
<div class="shot"><b>B dashed - ${escHtml(displayLabel(shotB))}</b><span>${escHtml(shotMeta(shotB))}</span></div>
</section>
<section class="chart-wrap">
<h2>Pressure</h2>
<p class="legend-note">Shared time axis, shared pressure scale.</p>
<canvas id="pressure"></canvas>
</section>
<section class="chart-wrap">
<h2>Flow</h2>
<p class="legend-note">Target flow appears when the shot log contains it.</p>
<canvas id="flow"></canvas>
</section>
<section class="chart-wrap">
<h2>Weight</h2>
<p class="legend-note">Target weight appears when the shot log contains it.</p>
<canvas id="weight"></canvas>
</section>
<section class="chart-wrap">
<h2>Summary deltas</h2>
<table>
<thead><tr><th>Metric</th><th>A</th><th>B</th><th>Delta</th></tr></thead>
<tbody>
${summaryRows(shotA, shotB)}</tbody>
</table>
</section>
<footer>Made with Decent E-Bar</footer>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
<script>
const LABEL_A="${escJs(displayLabel(shotA))}";
const LABEL_B="${escJs(displayLabel(shotB))}";
const MAX_SECONDS=${fmt(scales.durationMs / 1000.0, 3)};
const SERIES={
  pressure:{a:${seriesJs(shotA, ShotMetric.PRESSURE)},b:${seriesJs(shotB, ShotMetric.PRESSURE)}},
  flow:{a:${seriesJs(shotA, ShotMetric.FLOW)},b:${seriesJs(shotB, ShotMetric.FLOW)},ta:${targetFlowJs(shotA)},tb:${targetFlowJs(shotB)}},
  weight:{a:${seriesJs(shotA, ShotMetric.WEIGHT)},b:${seriesJs(shotB, ShotMetric.WEIGHT)},ta:${targetWeightJs(shotA)},tb:${targetWeightJs(shotB)}}
};
function ds(label,data,color,dashed=false,width=2){
  return {label,data,borderColor:color,backgroundColor:'transparent',borderWidth:width,borderDash:dashed?[7,5]:[],pointRadius:0,tension:.18,spanGaps:true};
}
function chart(id,unit,maxY,color,data){
  const sets=[
    ds('A '+unit,data.a,color,false,2),
    ds('B '+unit,data.b,color,true,2),
    ...(data.ta&&data.ta.length?[ds('A target',data.ta,'#ECCC79',true,1.6)]:[]),
    ...(data.tb&&data.tb.length?[ds('B target',data.tb,'#FF8E67',true,1.6)]:[])
  ];
  new Chart(document.getElementById(id),{
    type:'line',
    data:{datasets:sets},
    options:{
      animation:false,responsive:true,maintainAspectRatio:true,aspectRatio:3.2,
      interaction:{mode:'index',intersect:false},
      plugins:{
        legend:{labels:{color:'rgba(247,242,232,0.64)',boxWidth:12,font:{size:11}}},
        tooltip:{backgroundColor:'rgba(16,19,22,0.96)',titleColor:'rgba(247,242,232,0.70)',bodyColor:'rgba(247,242,232,0.92)',borderColor:'rgba(247,242,232,0.16)',borderWidth:1}
      },
      scales:{
        x:{type:'linear',min:0,max:MAX_SECONDS,title:{display:true,text:'Time (s)',color:'rgba(247,242,232,0.46)'},ticks:{color:'rgba(247,242,232,0.58)',maxTicksLimit:12},grid:{color:'rgba(247,242,232,0.08)'}},
        y:{min:0,max:maxY,title:{display:true,text:unit,color:'rgba(247,242,232,0.46)'},ticks:{color:'rgba(247,242,232,0.58)'},grid:{color:'rgba(247,242,232,0.08)'}}
      }
    }
  });
}
chart('pressure','Pressure (bar)',${fmt(scales.pressureMax, 2)},'#58A9FF',SERIES.pressure);
chart('flow','Flow (g/s)',${fmt(scales.flowMax, 2)},'#F0B34A',SERIES.flow);
chart('weight','Weight (g)',${fmt(scales.weightMax, 2)},'#53CF97',SERIES.weight);
</script>
<script type="application/json" id="shotlog-a-data">$embeddedA</script>
<script type="application/json" id="shotlog-b-data">$embeddedB</script>
</body>
</html>"""
    }

    private fun seriesJs(log: ShotLog, metric: ShotMetric): String =
        pointsJs(ShotDerivedMetrics.normalizedSeries(log, metric))

    private fun targetFlowJs(log: ShotLog): String =
        pointsJs(normalizedSampleValues(log) { sample ->
            sample.correctedTargetFlowGps ?: sample.targetFlowGps ?: log.stageTargetFlows[sample.stageName]
        })

    private fun targetWeightJs(log: ShotLog): String {
        val trajectory = normalizedSampleValues(log) { it.targetWeightG }
        if (trajectory.isNotEmpty()) return pointsJs(trajectory)
        val targetYield = log.targetYieldG ?: return "[]"
        return pointsJs(
            listOf(
                ShotMetricPoint(0L, targetYield),
                ShotMetricPoint((ShotDerivedMetrics.durationMs(log) ?: 1L).coerceAtLeast(1L), targetYield)
            )
        )
    }

    private fun normalizedSampleValues(
        log: ShotLog,
        valueFor: (ShotSample) -> Double?
    ): List<ShotMetricPoint> {
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

    private fun pointsJs(points: List<ShotMetricPoint>): String =
        points.joinToString(",", prefix = "[", postfix = "]") {
            "{x:${fmt(it.timeMs / 1000.0, 3)},y:${fmt(it.value, 3)}}"
        }

    private fun summaryRows(shotA: ShotLog, shotB: ShotLog): String {
        val rows = listOf(
            row("Beans", text(shotA.beansName), text(shotB.beansName)),
            row("Profile", shotA.profileName, shotB.profileName),
            numericRow("Yield", ShotDerivedMetrics.finalYieldG(shotA), ShotDerivedMetrics.finalYieldG(shotB), "g", 1),
            numericRow(
                "Time",
                ShotDerivedMetrics.durationMs(shotA)?.let { it / 1000.0 },
                ShotDerivedMetrics.durationMs(shotB)?.let { it / 1000.0 },
                "s",
                1
            ),
            numericRow("Dose", shotA.doseG, shotB.doseG, "g", 1),
            row("Grind", text(shotA.grindSetting), text(shotB.grindSetting)),
            row("Rating", shotA.rating?.let { "$it/5" } ?: "--", shotB.rating?.let { "$it/5" } ?: "--"),
            numericRow("Target yield", shotA.targetYieldG, shotB.targetYieldG, "g", 1),
            numericRow("Target time", shotA.targetTimeS, shotB.targetTimeS, "s", 1),
            row("Flow source", text(shotA.flowSource), text(shotB.flowSource))
        )
        return rows.joinToString("") { r ->
            "<tr><td>${escHtml(r.label)}</td><td>${escHtml(r.a)}</td><td>${escHtml(r.b)}</td><td class=\"delta\">${escHtml(r.delta)}</td></tr>\n"
        }
    }

    private fun row(label: String, a: String, b: String): SummaryRow =
        SummaryRow(label, a, b, if (a == b && a != "--") "same" else "diff")

    private fun numericRow(label: String, a: Double?, b: Double?, unit: String, digits: Int): SummaryRow {
        val left = a?.let { "${fmt(it, digits)} $unit" } ?: "--"
        val right = b?.let { "${fmt(it, digits)} $unit" } ?: "--"
        val delta = if (a != null && b != null) {
            "${signedFmt(b - a, digits)} $unit"
        } else {
            "--"
        }
        return SummaryRow(label, left, right, delta)
    }

    private data class SummaryRow(
        val label: String,
        val a: String,
        val b: String,
        val delta: String
    )

    private fun shotMeta(log: ShotLog): String =
        listOfNotNull(
            (log.savedAtMs ?: log.startedAtMs)?.let(::dateTime),
            ShotDerivedMetrics.finalYieldG(log)?.let { "${fmt(it, 1)} g" },
            ShotDerivedMetrics.durationMs(log)?.let { "${fmt(it / 1000.0, 1)} s" },
            log.doseG?.let { "${fmt(it, 1)} g dose" },
            log.grindSetting?.takeIf { it.isNotBlank() }?.let { "grind $it" },
            log.roastLevel?.takeIf { it.isNotBlank() },
            log.basket?.takeIf { it.isNotBlank() },
            log.rating?.let { "$it/5" },
            "${log.samples.size} samples",
            "${log.events.size} events"
        ).joinToString(" | ").ifBlank { "--" }

    private fun displayLabel(log: ShotLog): String =
        log.beansName?.takeIf { it.isNotBlank() } ?: log.profileName

    private fun text(value: String?): String =
        value?.takeIf { it.isNotBlank() } ?: "--"

    private fun dateTime(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timeMs))

    private fun signedFmt(value: Double, digits: Int): String {
        val sign = if (value >= 0.0) "+" else ""
        return sign + fmt(value, digits)
    }

    private fun fmt(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value)

    private fun escHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun escJs(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("<", "\\u003C")
        .replace(">", "\\u003E")
        .replace("&", "\\u0026")
}
