package dev.akiskev.decentebar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

internal data class FirstUseTutorialStep(
    val title: String,
    val body: String
)

internal val FirstUseTutorialSteps = listOf(
    FirstUseTutorialStep(
        title = "Stay in control",
        body = "Decent E-Bar drives the Wendougee E-Bar espresso machine by sending gestures to its on-screen pressure control. Keep the machine within reach, and use E-Stop or Disarm immediately if a shot behaves unexpectedly."
    ),
    FirstUseTutorialStep(
        title = "Enable Accessibility",
        body = "Use the warning banner to open Android Accessibility settings, then enable Decent E-Bar Controller so the app can read the E-Bar screen and send safe control gestures."
    ),
    FirstUseTutorialStep(
        title = "Open the E-Bar pressure screen",
        body = "Open the Wendougee E-Bar pressure screen before arming so Decent E-Bar can find the pressure control and move it during the shot."
    ),
    FirstUseTutorialStep(
        title = "Choose and save a profile",
        body = "Use the bundled profile, create a new one, or import a shared profile from the Profile tab. Save the profile before using it for a shot."
    ),
    FirstUseTutorialStep(
        title = "Optional scale connection",
        body = "Tap Connect to Bookoo scale in the Control tab to pair a Bookoo Mini. If no scale is connected, Decent E-Bar falls back to accessibility-based weight and flow readings."
    ),
    FirstUseTutorialStep(
        title = "Run, stop, and review",
        body = "Tap Arm, then start the shot in the E-Bar app. Use E-Stop or Disarm when needed, then review or export the shot log from the Log tab. You can review this tutorial any time from About > First-use tutorial."
    )
)

@Composable
internal fun FirstUseTutorialDialog(
    onSkip: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stepIndex by rememberSaveable { mutableStateOf(0) }
    val steps = FirstUseTutorialSteps
    val step = steps[stepIndex]
    val isLastStep = stepIndex == steps.lastIndex

    Dialog(onDismissRequest = onSkip) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "First-use tutorial",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Step ${stepIndex + 1} of ${steps.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LinearProgressIndicator(
                    progress = { (stepIndex + 1).toFloat() / steps.size },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = step.body,
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onSkip) {
                        Text("Skip")
                    }
                    TextButton(
                        onClick = { stepIndex -= 1 },
                        enabled = stepIndex > 0
                    ) {
                        Text("Back")
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                if (isLastStep) {
                                    onDone()
                                } else {
                                    stepIndex += 1
                                }
                            }
                        ) {
                            Text(if (isLastStep) "Done" else "Next")
                        }
                    }
                }
            }
        }
    }
}
