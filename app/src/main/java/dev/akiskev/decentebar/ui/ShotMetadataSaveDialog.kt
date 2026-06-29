package dev.akiskev.decentebar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Collects the shot metadata required to store a pull in the Shot Library. Beans and dose are the
 * only required fields; everything else is optional. Shared by the Log tab's manual "Save to
 * Library" button and the post-shot "Save to library?" prompt, so both entry points write the same
 * metadata. Holds its own form state and reports the completed [ShotMetadata] through [onSave].
 */
@Composable
internal fun ShotMetadataSaveDialog(
    onDismiss: () -> Unit,
    onSave: (ShotMetadata) -> Unit
) {
    var mdBeans by rememberSaveable { mutableStateOf("") }
    var mdGrind by rememberSaveable { mutableStateOf("") }
    var mdDose by rememberSaveable { mutableStateOf("") }
    var mdRoast by rememberSaveable { mutableStateOf("") }
    var mdBasket by rememberSaveable { mutableStateOf("") }
    var mdTargetYield by rememberSaveable { mutableStateOf("") }
    var mdTargetTime by rememberSaveable { mutableStateOf("") }
    var mdTasteNotes by rememberSaveable { mutableStateOf("") }
    var mdRating by rememberSaveable { mutableStateOf("") }

    val dose = mdDose.toDoubleOrNull()
    val targetYield = mdTargetYield.toDoubleOrNull()
    val targetTime = mdTargetTime.toDoubleOrNull()
    val rating = mdRating.toIntOrNull()
    val ratingValid = mdRating.isBlank() || (rating != null && rating in 1..5)
    val targetYieldValid = mdTargetYield.isBlank() || (targetYield != null && targetYield > 0.0)
    val targetTimeValid = mdTargetTime.isBlank() || (targetTime != null && targetTime > 0.0)
    val canSave = mdBeans.isNotBlank() &&
        dose != null &&
        dose > 0 &&
        ratingValid &&
        targetYieldValid &&
        targetTimeValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shot details") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Beans and dose are enough to save. Everything else can stay blank.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = mdBeans, onValueChange = { mdBeans = it },
                    label = { Text("Beans *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mdGrind, onValueChange = { mdGrind = it },
                    label = { Text("Grinder setting") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mdDose, onValueChange = { mdDose = it },
                    label = { Text("Dose (g) *") }, singleLine = true,
                    isError = mdDose.isNotBlank() && dose == null,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mdRoast, onValueChange = { mdRoast = it },
                    label = { Text("Roast level") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mdBasket, onValueChange = { mdBasket = it },
                    label = { Text("Basket") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mdTargetYield, onValueChange = { mdTargetYield = it },
                    label = { Text("Target yield (g)") }, singleLine = true,
                    isError = !targetYieldValid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mdTargetTime, onValueChange = { mdTargetTime = it },
                    label = { Text("Target time (s)") }, singleLine = true,
                    isError = !targetTimeValid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mdRating, onValueChange = { mdRating = it },
                    label = { Text("Rating 1-5") }, singleLine = true,
                    isError = !ratingValid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mdTasteNotes, onValueChange = { mdTasteNotes = it },
                    label = { Text("Taste notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        ShotMetadata(
                            beansName = mdBeans.trim(),
                            grindSetting = mdGrind.trim(),
                            doseG = dose,
                            roastLevel = mdRoast.trim(),
                            basket = mdBasket.trim(),
                            targetYieldG = targetYield,
                            targetTimeS = targetTime,
                            tasteNotes = mdTasteNotes.trim(),
                            rating = rating
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
