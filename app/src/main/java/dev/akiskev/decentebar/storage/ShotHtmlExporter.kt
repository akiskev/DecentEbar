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

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escHtml(title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#111;color:#ddd;padding:20px;max-width:1100px}
h1{font-size:1.35em;font-weight:600;color:#fff;margin-bottom:4px}
.meta{font-size:.82em;color:#777;margin-bottom:20px}
.chart-wrap{background:#1a1a1a;border-radius:10px;padding:16px 12px 8px;margin-bottom:24px}
h2{font-size:.8em;font-weight:600;color:#888;margin:24px 0 10px;text-transform:uppercase;letter-spacing:.07em}
table{width:100%;border-collapse:collapse;font-size:.78em}
th{text-align:left;padding:6px 10px;background:#181818;color:#666;font-weight:500;border-bottom:1px solid #2a2a2a}
td{padding:6px 10px;border-bottom:1px solid #1a1a1a;vertical-align:top}
tr:hover td{background:#161616}
.t{color:#555;font-variant-numeric:tabular-nums;white-space:nowrap}
.msg{color:#bbb}
.badge{display:inline-block;padding:1px 8px;border-radius:10px;font-size:.75em;font-weight:600;white-space:nowrap}
.b-STAGE_EXIT{background:#0e2e0e;color:#3d3}
.b-FIRST_DROP{background:#2e0e0e;color:#d44}
.b-INFO{background:#0e1a2e;color:#59d}
.b-STOP_COMMAND{background:#2e1a0e;color:#d94}
.b-PRESSURE_COMMAND{background:#1e1e0a;color:#99a}
.b-STATE_TRANSITION{background:#160e2e;color:#97d}
.b-default{background:#1e1e1e;color:#888}
</style>
</head>
<body>
<h1>${escHtml(title)}</h1>
<p class="meta">${escHtml(meta)}</p>
<div class="chart-wrap"><canvas id="c" height="90"></canvas></div>
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
${chartScript()}
</script>
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
        val stageCount = log.samples.map { it.stageName }.distinct().size
        parts += "$stageCount stages · ${log.samples.size} samples · ${log.events.size} events"
        return parts.joinToString(" · ")
    }

    // D[i] = [timeMs, flow, pressure|null, weight, stageIdx]
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
                append(idx[s.stageName] ?: 0)
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
const PALETTE=['rgba(60,80,160,0.18)','rgba(50,140,80,0.18)','rgba(160,60,60,0.18)','rgba(150,140,50,0.18)','rgba(50,140,150,0.18)','rgba(110,60,160,0.18)'];
const times=D.map(d=>d[0]/1000);
const flows=D.map(d=>d[1]);
const pressures=D.map(d=>d[2]);
const weights=D.map(d=>d[3]);
const stageIdxs=D.map(d=>d[4]);
const bands=[];
let last=-1;
for(let i=0;i<D.length;i++){
  if(stageIdxs[i]!==last){
    if(last>=0)bands[bands.length-1].end=times[i];
    bands.push({name:S[stageIdxs[i]],start:times[i],end:times[times.length-1],ci:bands.length});
    last=stageIdxs[i];
  }
}
const bandsPlugin={
  id:'bands',
  beforeDraw(chart){
    const{ctx,chartArea:{left,right,top,bottom},scales:{x}}=chart;
    bands.forEach(b=>{
      const x1=Math.max(left,x.getPixelForValue(b.start));
      const x2=Math.min(right,x.getPixelForValue(b.end));
      if(x2<=x1)return;
      ctx.fillStyle=PALETTE[b.ci%PALETTE.length];
      ctx.fillRect(x1,top,x2-x1,bottom-top);
      ctx.save();ctx.fillStyle='rgba(160,160,160,0.6)';ctx.font='10px sans-serif';ctx.textAlign='center';
      ctx.fillText(b.name,Math.max(x1+4,Math.min(x2-4,(x1+x2)/2)),top+11);
      ctx.restore();
    });
  }
};
const dropPlugin={
  id:'drop',
  beforeDraw(chart){
    const drop=E.find(e=>e[1]==='FIRST_DROP');
    if(!drop)return;
    const{ctx,chartArea:{top,bottom},scales:{x}}=chart;
    const px=x.getPixelForValue(drop[0]/1000);
    ctx.save();ctx.strokeStyle='rgba(210,70,70,0.8)';ctx.lineWidth=1.5;ctx.setLineDash([4,3]);
    ctx.beginPath();ctx.moveTo(px,top);ctx.lineTo(px,bottom);ctx.stroke();ctx.restore();
  }
};
new Chart(document.getElementById('c'),{
  type:'line',
  data:{
    labels:times.map(t=>t.toFixed(1)),
    datasets:[
      {label:'Flow (g/s)',data:flows,borderColor:'#5b9cf6',backgroundColor:'transparent',borderWidth:1.5,pointRadius:0,yAxisID:'yL',tension:0.2},
      {label:'Pressure (bar)',data:pressures,borderColor:'#f6a25b',backgroundColor:'transparent',borderWidth:1.5,pointRadius:0,yAxisID:'yL',spanGaps:true},
      {label:'Weight (g)',data:weights,borderColor:'#5bf6a2',backgroundColor:'transparent',borderWidth:1.5,pointRadius:0,yAxisID:'yR'}
    ]
  },
  options:{
    animation:false,responsive:true,
    interaction:{mode:'index',intersect:false},
    plugins:{
      legend:{labels:{color:'#999',boxWidth:12,font:{size:11}}},
      tooltip:{backgroundColor:'#222',titleColor:'#999',bodyColor:'#ddd',borderColor:'#333',borderWidth:1}
    },
    scales:{
      x:{title:{display:true,text:'Time (s)',color:'#555'},ticks:{color:'#555',maxTicksLimit:20},grid:{color:'#222'}},
      yL:{position:'left',title:{display:true,text:'Flow (g/s) / Pressure (bar)',color:'#555'},ticks:{color:'#555'},grid:{color:'#222'},min:0},
      yR:{position:'right',title:{display:true,text:'Weight (g)',color:'#555'},ticks:{color:'#555'},grid:{drawOnChartArea:false}}
    }
  },
  plugins:[bandsPlugin,dropPlugin]
});"""

    private fun escHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun escJs(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
}
