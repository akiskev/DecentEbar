package dev.akiskev.decentebar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
        ScaleConnectionState.SCANNING -> "Scanning..."
        ScaleConnectionState.CONNECTING -> "Connecting..."
        ScaleConnectionState.CONNECTED -> "Scale connected"
        ScaleConnectionState.ERROR -> "Scale error"
    }
    val scaleConnected = state.scaleConnectionState == ScaleConnectionState.CONNECTED
    val scaleBusy = state.scaleConnectionState == ScaleConnectionState.SCANNING ||
        state.scaleConnectionState == ScaleConnectionState.CONNECTING

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
            BoxWithConstraints {
                val compact = maxWidth < 620.dp
                val actionButtonModifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 96.dp)

                if (compact) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { if (state.isArmed) viewModel.disarm() else viewModel.arm() },
                                modifier = actionButtonModifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                            ) {
                                Text(if (state.isArmed) "Disarm" else "Arm")
                            }
                            Button(
                                onClick = viewModel::emergencyStop,
                                modifier = actionButtonModifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                            ) {
                                Text("E-Stop")
                            }
                        }
                        ScaleConnectionAction(
                            scaleConnected = scaleConnected,
                            scaleBusy = scaleBusy,
                            scaleLabel = scaleLabel,
                            connectToScale = connectToScale,
                            disconnectScale = disconnectScale,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { if (state.isArmed) viewModel.disarm() else viewModel.arm() },
                            modifier = actionButtonModifier,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Text(if (state.isArmed) "Disarm" else "Arm")
                        }
                        Button(
                            onClick = viewModel::emergencyStop,
                            modifier = actionButtonModifier,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Text("E-Stop")
                        }

                        Spacer(Modifier.weight(1f))

                        if (scaleConnected) {
                            Text(
                                "Using scale weight & flow",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        ScaleConnectionAction(
                            scaleConnected = scaleConnected,
                            scaleBusy = scaleBusy,
                            scaleLabel = scaleLabel,
                            connectToScale = connectToScale,
                            disconnectScale = disconnectScale,
                            modifier = actionButtonModifier
                        )
                    }
                }
            }
        }

        Panel("Status") {
            MetricGrid(metrics)
        }
    }
}

@Composable
private fun ScaleConnectionAction(
    scaleConnected: Boolean,
    scaleBusy: Boolean,
    scaleLabel: String,
    connectToScale: () -> Unit,
    disconnectScale: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = if (scaleConnected) disconnectScale else connectToScale,
        enabled = scaleConnected || !scaleBusy,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
        Text(scaleLabel)
    }
}
