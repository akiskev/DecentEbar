package dev.akiskev.decentebar.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
    LaunchedEffect(value) {
        if (!textHasFocus) localText = value.format(decimals)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(labelWidth))
        Slider(
            value = value.toFloat().coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f)
        )
        BasicTextField(
            value = localText,
            onValueChange = { localText = it },
            singleLine = true,
            textStyle = TextStyle(
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    innerTextField()
                }
            },
            modifier = Modifier
                .width(60.dp)
                .onFocusChanged { focusState ->
                    textHasFocus = focusState.isFocused
                    if (!focusState.isFocused) {
                        val parsed = localText.toDoubleOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(
                                valueRange.start.toDouble(),
                                valueRange.endInclusive.toDouble()
                            )
                            onChange(clamped)
                            localText = clamped.format(decimals)
                        } else {
                            localText = value.format(decimals)
                        }
                    }
                }
        )
        Text(unit, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
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
    LaunchedEffect(value) {
        if (!textHasFocus) localText = value.toString()
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(labelWidth))
        Slider(
            value = value.toFloat().coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { onChange(it.roundToLong()) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f)
        )
        BasicTextField(
            value = localText,
            onValueChange = { localText = it },
            singleLine = true,
            textStyle = TextStyle(
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    innerTextField()
                }
            },
            modifier = Modifier
                .width(72.dp)
                .onFocusChanged { focusState ->
                    textHasFocus = focusState.isFocused
                    if (!focusState.isFocused) {
                        val parsed = localText.toLongOrNull()
                        if (parsed != null) {
                            val clamped = parsed.coerceIn(
                                valueRange.start.toLong(),
                                valueRange.endInclusive.toLong()
                            )
                            onChange(clamped)
                            localText = clamped.toString()
                        } else {
                            localText = value.toString()
                        }
                    }
                }
        )
        Text(unit, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
    }
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
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Switch(
            checked = value != null,
            onCheckedChange = { enabled -> onChange(if (enabled) defaultValue else null) }
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
                "$label: —",
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
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Switch(
            checked = value != null,
            onCheckedChange = { enabled -> onChange(if (enabled) defaultValue else null) }
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
                "$label: —",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
