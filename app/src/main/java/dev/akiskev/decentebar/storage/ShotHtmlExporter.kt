package dev.akiskev.decentebar.storage

import dev.akiskev.decentebar.model.ShotEvent
import dev.akiskev.decentebar.model.ShotEventType
import dev.akiskev.decentebar.model.ShotLog
import dev.akiskev.decentebar.model.ShotSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShotHtmlExporter {

    fun export(log: ShotLog): String {
        val title = buildTitle(log)
        val meta = buildMeta(log)
        val stageNames = log.samples.map { it.stageName }.distinct()
        val samplesJs = buildSamplesJs(log.samples, stageNames)
        val stageNamesJs = stageNames.joinToString(",") { "\"${escJs(it)}\"" }
        val eventsJs = buildEventsJs(log.events)
        val eventsRows = buildEventsRows(log.events)
        val targetFlowsJs = stageNames.joinToString(",") { name ->
            log.stageTargetFlows[name]?.let { "%.3f".format(it) } ?: "null"
        }
        val embeddedJson = ShotLogCodec.encode(log).replace("</", "<\\/")

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escHtml(title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#120F0D;color:rgba(252,243,230,0.85);padding:20px;max-width:1100px}
h1{font-size:1.35em;font-weight:600;color:rgba(252,243,230,0.95);margin-bottom:4px}
.meta{font-size:.82em;color:rgba(252,243,230,0.50);margin-bottom:20px}
.chart-wrap{position:relative;background:rgba(24,19,17,0.88);border-radius:10px;padding:16px 12px 8px;margin-bottom:24px}
.chart-wrap canvas{display:block;width:100%!important}
h2{font-size:.8em;font-weight:600;color:rgba(252,243,230,0.55);margin:24px 0 10px;text-transform:uppercase;letter-spacing:.07em}
table{width:100%;border-collapse:collapse;font-size:.78em}
th{text-align:left;padding:6px 10px;background:rgba(24,19,17,0.60);color:rgba(252,243,230,0.45);font-weight:500;border-bottom:1px solid rgba(252,243,230,0.12)}
td{padding:6px 10px;border-bottom:1px solid rgba(252,243,230,0.08);vertical-align:top}
tr:hover td{background:rgba(24,19,17,0.50)}
.t{color:rgba(252,243,230,0.42);font-variant-numeric:tabular-nums;white-space:nowrap}
.msg{color:rgba(252,243,230,0.75)}
.badge{display:inline-block;padding:1px 8px;border-radius:10px;font-size:.75em;font-weight:600;white-space:nowrap}
.b-STAGE_EXIT{background:rgba(106,158,136,0.16);color:#6A9E88}
.b-FIRST_DROP{background:rgba(196,91,91,0.16);color:#D45B5B}
.b-INFO{background:rgba(201,165,90,0.16);color:#E8CE85}
.b-STOP_COMMAND{background:rgba(176,115,85,0.16);color:#B07355}
.b-PRESSURE_COMMAND{background:rgba(176,115,85,0.12);color:#C9A55A}
.b-STATE_TRANSITION{background:rgba(201,165,90,0.12);color:#E8CE85}
.b-default{background:rgba(24,19,17,0.60);color:rgba(252,243,230,0.50)}
</style>
</head>
<body>
<h1>${escHtml(title)}</h1>
<p class="meta">${escHtml(meta)}</p>
<div class="chart-wrap"><canvas id="c"></canvas></div>
<h2>Events</h2>
<table>
<thead><tr><th>Time</th><th>Type</th><th>Detail</th></tr></thead>
<tbody>
$eventsRows</tbody>
</table>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"></script>
<script>
const S=[$stageNamesJs];
const D=$samplesJs;
const E=$eventsJs;
const TARGETS=[$targetFlowsJs];
${chartScript()}
</script>
<script type="application/json" id="shotlog-data">$embeddedJson</script>
</body>
</html>"""
    }

    private fun buildTitle(log: ShotLog): String {
        val date = log.startedAtMs?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it))
        } ?: "Unknown date"
        return "${log.profileName} — $date"
    }

    private fun buildMeta(log: ShotLog): String {
        val parts = mutableListOf<String>()
        val start = log.startedAtMs
        val stop = log.stoppedAtMs
        if (start != null && stop != null) {
            parts += "Duration: ${"%.1f".format((stop - start) / 1000.0)}s"
        }
        log.samples.lastOrNull()?.weightG?.let { parts += "Final weight: ${"%.1f".format(it)}g" }
        log.beansName?.let { parts += "Beans: $it" }
        log.grindSetting?.let { parts += "Grind: $it" }
        log.doseG?.let { parts += "Dose: ${"%.1f".format(it)}g" }
        val stageCount = log.samples.map { it.stageName }.distinct().size
        parts += "$stageCount stages · ${log.samples.size} samples · ${log.events.size} events"
        log.flowSource?.let { parts += "Flow: $it" }
        log.appVersion?.let { parts += "App: $it" }
        return parts.joinToString(" · ")
    }

    // D[i] = [timeMs, scaleFlow, pressure|null, weight, stageIdx, altFlow|null]
    private fun buildSamplesJs(samples: List<ShotSample>, stageNames: List<String>): String {
        val idx = stageNames.withIndex().associate { (i, n) -> n to i }
        return buildString {
            append('[')
            samples.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append('[')
                append(s.timeMs).append(',')
                append("%.3f".format(s.flowGps)).append(',')
                if (s.commandedPressureBar != null) append("%.2f".format(s.commandedPressureBar)) else append("null")
                append(',')
                append("%.2f".format(s.weightG)).append(',')
                append(idx[s.stageName] ?: 0).append(',')
                if (s.altFlowGps != null) append("%.3f".format(s.altFlowGps)) else append("null")
                append(']')
            }
            append(']')
        }
    }

    // E[i] = [timeMs, type, message]
    private fun buildEventsJs(events: List<ShotEvent>): String = buildString {
        append('[')
        events.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append("[${e.timeMs},\"${e.type.name}\",\"${escJs(e.message)}\"]")
        }
        append(']')
    }

    private fun buildEventsRows(events: List<ShotEvent>): String {
        if (events.isEmpty()) {
            return "<tr><td colspan=\"3\" style=\"color:#555;text-align:center\">No events recorded</td></tr>\n"
        }
        return events.joinToString("") { e ->
            val time = formatTime(e.timeMs)
            val cls = badgeClass(e.type)
            val label = e.type.name.lowercase().replace('_', ' ')
            "<tr><td class=\"t\">$time</td><td><span class=\"badge $cls\">${escHtml(label)}</span></td><td class=\"msg\">${escHtml(e.message)}</td></tr>\n"
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val f = (ms % 1000) / 100
        return "${s}.${f}s"
    }

    private fun badgeClass(type: ShotEventType): String = when (type) {
        ShotEventType.STAGE_EXIT -> "b-STAGE_EXIT"
        ShotEventType.FIRST_DROP -> "b-FIRST_DROP"
        ShotEventType.INFO -> "b-INFO"
        ShotEventType.STOP_COMMAND -> "b-STOP_COMMAND"
        ShotEventType.PRESSURE_COMMAND -> "b-PRESSURE_COMMAND"
        ShotEventType.STATE_TRANSITION -> "b-STATE_TRANSITION"
        else -> "b-default"
    }

    private fun chartScript(): String = """
// Alternating band fills (clearly visible) keyed by run order, not stage index.
const PALETTE=['rgba(252,243,230,0.000)','rgba(252,243,230,0.045)'];
const TARGET_COLOR='#F2C94C';
const times=D.map(d=>d[0]/1000);
const stageIdxs=D.map(d=>d[4]);
const hasAlt=D.some(d=>d[5]!==null);
const hasTargets=TARGETS.some(t=>t!==null);
// Build one band per contiguous run of the same stage. si = stage index (for the target lookup).
const bands=[];
let last=-1;
for(let i=0;i<D.length;i++){
  if(stageIdxs[i]!==last){
    if(bands.length)bands[bands.length-1].end=times[i];
    bands.push({name:S[stageIdxs[i]],si:stageIdxs[i],start:times[i],end:times[times.length-1],ci:bands.length});
    last=stageIdxs[i];
  }
}
const bandsPlugin={
  id:'bands',
  beforeDraw(chart){
    const{ctx,chartArea:{left,right,top,bottom},scales:{x}}=chart;
    ctx.save();
    bands.forEach(b=>{
      const x1=Math.max(left,x.getPixelForValue(b.start));
      const x2=Math.min(right,x.getPixelForValue(b.end));
      if(x2<=x1)return;
      // Fill alternate runs so adjacent stages are distinguishable.
      ctx.fillStyle=PALETTE[b.ci%PALETTE.length];
      ctx.fillRect(x1,top,x2-x1,bottom-top);
      // Separator line at the start of each band (except the first).
      if(b.ci>0){
        ctx.strokeStyle='rgba(252,243,230,0.18)';ctx.lineWidth=1;ctx.setLineDash([2,3]);
        ctx.beginPath();ctx.moveTo(x1,top);ctx.lineTo(x1,bottom);ctx.stroke();ctx.setLineDash([]);
      }
      // Stage name label near the top of each band.
      ctx.fillStyle='rgba(252,243,230,0.45)';ctx.font='10px -apple-system,sans-serif';ctx.textBaseline='top';
      ctx.fillText(b.name,x1+4,top+3);
    });
    ctx.restore();
  }
};
const dropPlugin={
  id:'drop',
  afterDatasetsDraw(chart){
    const drop=E.find(e=>e[1]==='FIRST_DROP');
    if(!drop)return;
    const{ctx,chartArea:{top,bottom},scales:{x}}=chart;
    const px=x.getPixelForValue(drop[0]/1000);
    ctx.save();ctx.strokeStyle='rgba(196,91,91,0.70)';ctx.lineWidth=1.5;ctx.setLineDash([4,3]);
    ctx.beginPath();ctx.moveTo(px,top);ctx.lineTo(px,bottom);ctx.stroke();ctx.restore();
  }
};
// Draw target-flow lines directly on the canvas (one horizontal dashed segment per
// flow-limited stage run). Done as a plugin rather than a dataset because a dataset whose
// data array mixes nulls with points does not render reliably across stage gaps.
const targetPlugin={
  id:'target',
  afterDatasetsDraw(chart){
    if(!hasTargets)return;
    // Respect the legend toggle: the legend entry is a dummy dataset, so honor its visibility here.
    const ti=chart.data.datasets.findIndex(ds=>ds.label==='Target Flow (g/s)');
    if(ti>=0&&!chart.isDatasetVisible(ti))return;
    const{ctx,chartArea:{left,right},scales:{x,yL}}=chart;
    ctx.save();ctx.strokeStyle=TARGET_COLOR;ctx.lineWidth=2;ctx.setLineDash([8,6]);
    bands.forEach(b=>{
      const tv=TARGETS[b.si];
      if(tv==null)return;
      const x1=Math.max(left,x.getPixelForValue(b.start));
      const x2=Math.min(right,x.getPixelForValue(b.end));
      if(x2<=x1)return;
      const py=yL.getPixelForValue(tv);
      ctx.beginPath();ctx.moveTo(x1,py);ctx.lineTo(x2,py);ctx.stroke();
    });
    ctx.restore();
  }
};
const xyFlow=times.map((t,i)=>({x:t,y:D[i][1]}));
const xyPressure=times.map((t,i)=>({x:t,y:D[i][2]}));
const xyWeight=times.map((t,i)=>({x:t,y:D[i][3]}));
const xyAlt=hasAlt?times.map((t,i)=>({x:t,y:D[i][5]})):null;
const datasets=[
  {label:hasAlt?'Scale Flow (g/s)':'Flow (g/s)',data:xyFlow,borderColor:'#C9A55A',backgroundColor:'transparent',borderWidth:1.5,pointRadius:0,yAxisID:'yL',tension:0.2},
  ...(hasAlt?[{label:'Calc Flow (g/s)',data:xyAlt,borderColor:'#9b6fda',backgroundColor:'transparent',borderWidth:1,borderDash:[4,3],pointRadius:0,yAxisID:'yL',tension:0.2,spanGaps:true}]:[]),
  {label:'Pressure (bar)',data:xyPressure,borderColor:'#B07355',backgroundColor:'transparent',borderWidth:1.5,pointRadius:0,yAxisID:'yL',spanGaps:true},
  {label:'Weight (g)',data:xyWeight,borderColor:'#6A9E88',backgroundColor:'transparent',borderWidth:1.5,pointRadius:0,yAxisID:'yR'},
  // Legend-only entry for the target lines (the lines themselves are drawn by targetPlugin).
  ...(hasTargets?[{label:'Target Flow (g/s)',data:[],borderColor:TARGET_COLOR,backgroundColor:'transparent',borderWidth:2,borderDash:[8,6],pointRadius:0,yAxisID:'yL'}]:[])
];
const chart=new Chart(document.getElementById('c'),{
  type:'line',
  data:{datasets},
  options:{
    animation:false,responsive:true,maintainAspectRatio:true,aspectRatio:4,
    interaction:{mode:'index',intersect:false},
    plugins:{
      legend:{labels:{color:'rgba(252,243,230,0.62)',boxWidth:12,font:{size:11}}},
      tooltip:{backgroundColor:'rgba(24,19,17,0.95)',titleColor:'rgba(252,243,230,0.62)',bodyColor:'rgba(252,243,230,0.90)',borderColor:'rgba(252,243,230,0.16)',borderWidth:1}
    },
    scales:{
      x:{type:'linear',title:{display:true,text:'Time (s)',color:'rgba(252,243,230,0.42)'},ticks:{color:'rgba(252,243,230,0.55)',maxTicksLimit:20},grid:{color:'rgba(252,243,230,0.08)'}},
      yL:{position:'left',title:{display:true,text:'Flow (g/s) / Pressure (bar)',color:'rgba(252,243,230,0.42)'},ticks:{color:'rgba(252,243,230,0.55)'},grid:{color:'rgba(252,243,230,0.08)'},min:0},
      yR:{position:'right',title:{display:true,text:'Weight (g)',color:'rgba(252,243,230,0.42)'},ticks:{color:'rgba(252,243,230,0.55)'},grid:{drawOnChartArea:false}}
    }
  },
  plugins:[bandsPlugin,dropPlugin,targetPlugin]
});
let _rt;window.addEventListener('resize',()=>{clearTimeout(_rt);_rt=setTimeout(()=>chart.resize(),60);});"""

    private fun escHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun escJs(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
}
