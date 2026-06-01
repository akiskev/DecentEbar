package dev.akiskev.decentebar.ui

import dev.akiskev.decentebar.util.formatDecimals

internal fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

internal fun Double.format(decimals: Int): String = formatDecimals(decimals)

internal fun Float.format(decimals: Int): String = toDouble().formatDecimals(decimals)
