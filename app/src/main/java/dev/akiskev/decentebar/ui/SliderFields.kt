package dev.akiskev.decentebar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

@Composable
internal fun SliderField(
    label: String,
    value: Double,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 110.dp,
    decimals: Int = 2,
    onChange: (Double) -> Unit
) {
    var localText by remember { mutableStateOf(value.format(decimals)) }
    var textHasFocus by remember { mutableStateOf(false) }

    fun commitText(text: String): Boolean {
        val parsed = text.toDoubleOrNull() ?: return false
        val clamped = parsed.coerceIn(valueRange.start.toDouble(), valueRange.endInclusive.toDouble())
        onChange(clamped)
        return true
    }

    LaunchedEffect(value) {
        if (!textHasFocus) localText = value.format(decimals)
    }

    SliderRow(
        label = label,
        unit = unit,
        labelWidth = labelWidth,
        modifier = modifier
    ) {
        Slider(
            value = value.toFloat().coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label }
        )
        NumericTextField(
            value = localText,
            onValueChange = {
                localText = it
                commitText(it)
            },
            keyboardType = KeyboardType.Decimal,
            isError = localText.isNotBlank() && localText.toDoubleOrNull() == null,
            contentDescription = "$label value",
            modifier = Modifier.width(76.dp),
            onFocusChanged = { focused ->
                textHasFocus = focused
                if (!focused) {
                    localText = if (commitText(localText)) {
                        localText.toDoubleOrNull()
                            ?.coerceIn(valueRange.start.toDouble(), valueRange.endInclusive.toDouble())
                            ?.format(decimals)
                            ?: value.format(decimals)
                    } else {
                        value.format(decimals)
                    }
                }
            }
        )
    }
}

@Composable
internal fun SliderLongField(
    label: String,
    value: Long,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 110.dp,
    onChange: (Long) -> Unit
) {
    var localText by remember { mutableStateOf(value.toString()) }
    var textHasFocus by remember { mutableStateOf(false) }

    fun commitText(text: String): Boolean {
        val parsed = text.toLongOrNull() ?: return false
        val clamped = parsed.coerceIn(valueRange.start.toLong(), valueRange.endInclusive.toLong())
        onChange(clamped)
        return true
    }

    LaunchedEffect(value) {
        if (!textHasFocus) localText = value.toString()
    }

    SliderRow(
        label = label,
        unit = unit,
        labelWidth = labelWidth,
        modifier = modifier
    ) {
        Slider(
            value = value.toFloat().coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { onChange(it.roundToLong()) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label }
        )
        NumericTextField(
            value = localText,
            onValueChange = {
                localText = it
                commitText(it)
            },
            keyboardType = KeyboardType.Number,
            isError = localText.isNotBlank() && localText.toLongOrNull() == null,
            contentDescription = "$label value",
            modifier = Modifier.width(86.dp),
            onFocusChanged = { focused ->
                textHasFocus = focused
                if (!focused) {
                    localText = if (commitText(localText)) {
                        localText.toLongOrNull()
                            ?.coerceIn(valueRange.start.toLong(), valueRange.endInclusive.toLong())
                            ?.toString()
                            ?: value.toString()
                    } else {
                        value.toString()
                    }
                }
            }
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    unit: String,
    labelWidth: Dp,
    modifier: Modifier,
    controls: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(labelWidth))
        controls()
        Text(unit, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun NumericTextField(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    isError: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        isError = isError,
        textStyle = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { this.contentDescription = contentDescription }
            .onFocusChanged { onFocusChanged(it.isFocused) }
    )
}

@Composable
internal fun SliderDurationField(
    label: String,
    valueMs: Long,
    valueRangeSeconds: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 110.dp,
    onChange: (Long) -> Unit
) {
    SliderField(
        label = label,
        value = valueMs / 1000.0,
        valueRange = valueRangeSeconds,
        steps = steps,
        unit = "s",
        modifier = modifier,
        labelWidth = labelWidth,
        decimals = 0,
        onChange = { onChange((it * 1000.0).roundToLong()) }
    )
}

@Composable
internal fun OptionalSliderField(
    label: String,
    value: Double?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    defaultValue: Double,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 130.dp,
    decimals: Int = 2,
    onChange: (Double?) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Switch(
            checked = value != null,
            onCheckedChange = { enabled -> onChange(if (enabled) defaultValue else null) },
            modifier = Modifier.semantics { contentDescription = "$label enabled" }
        )
        if (value != null) {
            SliderField(
                label = label,
                value = value,
                valueRange = valueRange,
                steps = steps,
                unit = unit,
                modifier = Modifier.weight(1f),
                labelWidth = labelWidth,
                decimals = decimals,
                onChange = { onChange(it) }
            )
        } else {
            Text(
                "$label: --",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun OptionalSliderLongField(
    label: String,
    value: Long?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    defaultValue: Long,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 130.dp,
    onChange: (Long?) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Switch(
            checked = value != null,
            onCheckedChange = { enabled -> onChange(if (enabled) defaultValue else null) },
            modifier = Modifier.semantics { contentDescription = "$label enabled" }
        )
        if (value != null) {
            SliderLongField(
                label = label,
                value = value,
                valueRange = valueRange,
                steps = steps,
                unit = unit,
                modifier = Modifier.weight(1f),
                labelWidth = labelWidth,
                onChange = { onChange(it) }
            )
        } else {
            Text(
                "$label: --",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun OptionalSliderDurationField(
    label: String,
    valueMs: Long?,
    valueRangeSeconds: ClosedFloatingPointRange<Float>,
    steps: Int,
    defaultValueMs: Long,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 130.dp,
    onChange: (Long?) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Switch(
            checked = valueMs != null,
            onCheckedChange = { enabled -> onChange(if (enabled) defaultValueMs else null) },
            modifier = Modifier.semantics { contentDescription = "$label enabled" }
        )
        if (valueMs != null) {
            SliderDurationField(
                label = label,
                valueMs = valueMs,
                valueRangeSeconds = valueRangeSeconds,
                steps = steps,
                modifier = Modifier.weight(1f),
                labelWidth = labelWidth,
                onChange = { onChange(it) }
            )
        } else {
            Text(
                "$label: --",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
