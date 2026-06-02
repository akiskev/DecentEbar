package dev.akiskev.decentebar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.akiskev.decentebar.ble.ScaleConnectionState
import dev.akiskev.decentebar.model.BuiltInPressureLut

@Composable
internal fun DebugScreen(state: MainUiState) {
    val snapshot = state.snapshot
    val barNode = BuiltInPressureLut.findPressureBar(snapshot.nodes, snapshot.screenWidth, snapshot.screenHeight)
    val anchoredLut = barNode?.let {
        BuiltInPressureLut.buildAnchoredFrom(it, snapshot.screenWidth, snapshot.screenHeight)
    }
    val zeroBar = anchoredLut?.points?.firstOrNull { it.pressureBar == 0.0 }
    val twelveBar = anchoredLut?.points?.firstOrNull { it.pressureBar == 12.0 }

    val snapshotMetrics = listOf(
        "Active package" to (state.snapshot.activePackage ?: "--"),
        "Screen" to "${state.snapshot.screenWidth} x ${state.snapshot.screenHeight}",
        "Orientation" to state.snapshot.orientation,
        "Parsed weight" to (state.snapshot.weightG?.format(1)?.plus(" g") ?: "--"),
        "Has Start" to yesNo(state.snapshot.hasStart),
        "Has Stop" to yesNo(state.snapshot.hasStop),
        "Has Weigh" to yesNo(state.snapshot.hasWeigh),
        "Pressure controls" to yesNo(state.snapshot.hasPressureControls),
        "Last pressure cmd" to state.lastPressureCommand,
        "Last stop cmd" to state.lastStopCommand,
        "Last safety error" to state.lastSafetyError
    )

    val anchorMetrics = listOf(
        "Pressure LUT source" to (state.loadedLut?.name ?: "--"),
        "Bar node bounds" to (barNode?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" } ?: "not found"),
        "Bar node class" to (barNode?.className?.substringAfterLast('.') ?: "--"),
        "0 bar tap" to (zeroBar?.let { "${it.x.toInt()}, ${it.y.toInt()}" } ?: "--"),
        "12 bar tap" to (twelveBar?.let { "${it.x.toInt()}, ${it.y.toInt()}" } ?: "--")
    )
    val scaleConnected = state.scaleConnectionState == ScaleConnectionState.CONNECTED
    val flowDiff = state.currentFlowGps - state.currentCalcFlowGps

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(0.5f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (scaleConnected) {
                Panel("Flow Comparison (Scale vs Software)") {
                    val compMetrics = listOf(
                        "Scale flow" to "${state.currentFlowGps.format(2)} g/s",
                        "Calc flow" to "${state.currentCalcFlowGps.format(2)} g/s",
                        "Diff (S−C)" to "${if (flowDiff >= 0) "+" else ""}${flowDiff.format(2)} g/s"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        compMetrics.forEach { (label, value) ->
                            MetricCell(label, value, Modifier.weight(1f))
                        }
                    }
                }
            }
            Panel("Accessibility Snapshot") {
                MetricGrid(snapshotMetrics)
            }
            Panel("Pressure Bar Anchor") {
                MetricGrid(anchorMetrics)
            }
        }
        Column(
            modifier = Modifier.weight(0.5f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Panel("Node bounds", modifier = Modifier.weight(1f), fillContent = true) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.snapshot.nodes.take(160)) { node ->
                        val tag = node.label ?: node.className?.substringAfterLast('.') ?: "?"
                        Text(
                            "[${node.left},${node.top}][${node.right},${node.bottom}] $tag",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Panel("Raw text", modifier = Modifier.weight(1f), fillContent = true) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.snapshot.rawTexts.take(160)) { text ->
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
