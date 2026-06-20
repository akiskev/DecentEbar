package dev.akiskev.decentebar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    connectToScale: () -> Unit = {},
    disconnectScale: () -> Unit = {}
) {
    val scaleLabel = when (state.scaleConnectionState) {
        ScaleConnectionState.DISCONNECTED -> "Connect to Bookoo scale"
        ScaleConnectionState.SCANNING -> "Scanning…"
        ScaleConnectionState.CONNECTING -> "Connecting…"
        ScaleConnectionState.CONNECTED -> "Scale ●"
        ScaleConnectionState.ERROR -> "Scale ✕"
    }
    val scaleConnected = state.scaleConnectionState == ScaleConnectionState.CONNECTED
    val scaleBusy = state.scaleConnectionState == ScaleConnectionState.SCANNING ||
            state.scaleConnectionState == ScaleConnectionState.CONNECTING

    // Only readiness/diagnostic fields that are meaningful while this app is in the foreground.
    // Live shot telemetry (weight, flow, pressure, stage, …) is intentionally omitted: during a
    // shot the app runs in the background, so nobody is looking at it here.
    val metrics = listOf(
        "Service" to if (state.serviceEnabled) "Enabled" else "Disabled",
        "Scale Batt" to (state.scaleBatteryPercent?.let { "$it %" } ?: "--"),
        "Safety" to state.safetyStatus
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Machine controls on the left …
                Button(onClick = { if (state.isArmed) viewModel.disarm() else viewModel.arm() }) {
                    Text(if (state.isArmed) "Disarm" else "Arm")
                }
                Button(
                    onClick = viewModel::emergencyStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("E-Stop")
                }

                Spacer(Modifier.weight(1f))

                // … scale connection on the right.
                if (scaleConnected) {
                    Text(
                        "Using scale weight & flow",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
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
            }
        }

        Panel("Status") {
            MetricGrid(metrics)
        }
    }
}
