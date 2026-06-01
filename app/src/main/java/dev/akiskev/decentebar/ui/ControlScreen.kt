package dev.akiskev.decentebar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.akiskev.decentebar.ble.ScaleConnectionState
import dev.akiskev.decentebar.model.ControllerState

@Composable
internal fun Header(state: MainUiState) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Decent E-Bar",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${state.selectedProfile.name} | stop at ${state.stopAtWeightG.format(1)} g",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            StatusBadge(state.controllerState)
        }
    }
}

@Composable
private fun StatusBadge(controllerState: ControllerState) {
    val container = when (controllerState) {
        ControllerState.ERROR -> MaterialTheme.colorScheme.errorContainer
        ControllerState.RUNNING,
        ControllerState.STAGE_TRANSITION,
        ControllerState.STOPPING -> MaterialTheme.colorScheme.tertiaryContainer
        ControllerState.ARMED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            controllerState.name,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
internal fun ControlScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    openAccessibilitySettings: () -> Unit,
    connectToScale: () -> Unit = {},
    disconnectScale: () -> Unit = {}
) {
    val scaleLabel = when (state.scaleConnectionState) {
        ScaleConnectionState.DISCONNECTED -> "Scale"
        ScaleConnectionState.SCANNING -> "Scanning…"
        ScaleConnectionState.CONNECTING -> "Connecting…"
        ScaleConnectionState.CONNECTED -> "Scale ●"
        ScaleConnectionState.ERROR -> "Scale ✕"
    }
    val scaleConnected = state.scaleConnectionState == ScaleConnectionState.CONNECTED
    val scaleBusy = state.scaleConnectionState == ScaleConnectionState.SCANNING ||
            state.scaleConnectionState == ScaleConnectionState.CONNECTING

    val metrics = listOf(
        "Controller" to state.controllerState.name,
        "Service" to if (state.serviceEnabled) "Enabled" else "Disabled",
        "E-Bar foreground" to yesNo(state.snapshot.isForeground),
        "LUT" to (state.loadedLut?.name ?: "Missing"),
        "LUT validation" to state.lutValidation.displayText,
        "Start visible" to yesNo(state.snapshot.hasStart),
        "Stop visible" to yesNo(state.snapshot.hasStop),
        "Weight" to (state.currentWeightG?.format(1)?.plus(" g") ?: "--"),
        "Flow" to "${state.currentFlowGps.format(2)} g/s",
        "Stage" to state.currentStageName,
        "Pressure" to (state.commandedPressureBar?.format(2)?.plus(" bar") ?: "--"),
        "Elapsed" to "${(state.elapsedShotTimeMs / 1000.0).format(1)} s",
        "Safety" to state.safetyStatus,
        "Last pressure" to state.lastPressureCommand,
        "Last stop" to state.lastStopCommand,
        "Scale" to state.scaleConnectionState.name,
        "Scale Batt" to (state.scaleBatteryPercent?.let { "$it %" } ?: "--")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { if (state.isArmed) viewModel.disarm() else viewModel.arm() }) {
                        Text(if (state.isArmed) "Disarm" else "Arm")
                    }
                    Button(
                        onClick = viewModel::emergencyStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("E-Stop")
                    }
                    OutlinedButton(onClick = viewModel::manualSkipStage) { Text("Skip Stage") }
                    TextButton(onClick = openAccessibilitySettings) { Text("Accessibility") }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (scaleConnected) {
                        Button(
                            onClick = disconnectScale,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) { Text(scaleLabel) }
                    } else {
                        OutlinedButton(
                            onClick = connectToScale,
                            enabled = !scaleBusy
                        ) { Text(scaleLabel) }
                    }
                    if (scaleConnected) {
                        Text(
                            "Using scale weight & flow",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        Panel("Live Status") {
            MetricGrid(metrics)
        }
    }
}
