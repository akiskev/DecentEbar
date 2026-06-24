package dev.akiskev.decentebar.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    fillContent: Boolean = false,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (fillContent) {
            Column(Modifier.padding(14.dp).fillMaxSize()) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f)) {
                    content()
                }
            }
        } else {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

/**
 * Full-width error banner shown when the accessibility service is disabled. The app can't read or
 * drive the E-Bar without it, so this is surfaced above every tab with a one-tap shortcut to the
 * system Accessibility settings.
 */
@Composable
internal fun AccessibilityWarningBanner(onEnable: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "Accessibility service disabled",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "DecentEbar can't read or control the E-Bar until you enable it in Accessibility settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Button(
                onClick = onEnable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("Enable") }
        }
    }
}

@Composable
internal fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 56.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun MessageLine(message: String) {
    if (message.isNotBlank()) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
internal fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    val labeledOptions = options.map { option -> option to label(option) }
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .heightIn(min = 48.dp)
    ) {
        labeledOptions.forEachIndexed { index, (option, text) ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.widthIn(min = ((text.length * 8) + 40).coerceAtLeast(72).dp),
                label = {
                    Text(
                        text,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            )
        }
    }
}

/**
 * Lays out metric (label, value) pairs in rows of [columns], padding the final row with
 * spacers so cells stay aligned. Used by the Control, LUT and Debug status grids.
 */
@Composable
internal fun MetricGrid(metrics: List<Pair<String, String>>, columns: Int = 3) {
    BoxWithConstraints {
        val adaptiveColumns = when {
            maxWidth < 360.dp -> 1
            maxWidth < 680.dp -> minOf(columns, 2)
            else -> columns
        }.coerceAtLeast(1)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            metrics.chunked(adaptiveColumns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { (label, value) -> MetricCell(label, value, Modifier.weight(1f)) }
                    repeat(adaptiveColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
