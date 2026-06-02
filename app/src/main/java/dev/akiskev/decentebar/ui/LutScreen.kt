package dev.akiskev.decentebar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.akiskev.decentebar.engine.interpolatedPressurePoint
import dev.akiskev.decentebar.engine.nearestPressurePoint

@Composable
internal fun LutScreen(state: MainUiState, viewModel: MainViewModel) {
    var pressureValue by remember { mutableStateOf(8.0) }
    val nearest = state.loadedLut?.nearestPressurePoint(pressureValue)
    val interpolated = state.loadedLut?.interpolatedPressurePoint(pressureValue)

    val lutMetrics = listOf(
        "Loaded" to (state.loadedLut?.name ?: "No"),
        "Package" to (state.loadedLut?.packageName ?: "--"),
        "Resolution" to (state.loadedLut?.let { "${it.screenWidth} x ${it.screenHeight}" } ?: "--"),
        "Orientation" to (state.loadedLut?.orientation ?: "--"),
        "Points" to (state.loadedLut?.points?.size?.toString() ?: "0"),
        "Validation" to state.lutValidation.displayText,
        "Selected pressure" to "${pressureValue.format(2)} bar",
        "Nearest coordinate" to (nearest?.let { "${it.pressureBar.format(2)} bar at ${it.x.toInt()},${it.y.toInt()}" } ?: "--"),
        "Interpolated coordinate" to (interpolated?.let { "${it.pressureBar.format(2)} bar at ${it.x.format(1)},${it.y.format(1)}" } ?: "--")
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Panel("LUT Status") {
                MetricGrid(lutMetrics)
            }
        }

        item {
            Panel("Pressure Test") {
                SliderField(
                    label = "Pressure",
                    value = pressureValue,
                    valueRange = 0f..12f,
                    steps = 119,
                    unit = "bar",
                    onChange = { pressureValue = it }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { viewModel.testPressure(pressureValue.format(2)) }) { Text("Test Point") }
                    OutlinedButton(onClick = viewModel::exportLut) { Text("Export JSON") }
                }
                MessageLine(state.lutMessage)
            }
        }

        item {
            Panel("Calibrate") {
                Text(
                    "Open the e-bar pressure screen with no shot running, then press Calibrate. " +
                        "The bar is swept and each position's reading is measured to build an exact LUT.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = viewModel::calibratePressureBar) { Text("Calibrate Bar") }
                MessageLine(state.calibrationMessage)
            }
        }

        item {
            Panel("Export JSON") {
                OutlinedTextField(
                    value = state.exportedLutJson,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Computed LUT JSON") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
