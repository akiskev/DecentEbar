package dev.akiskev.decentebar.ui

import java.util.Locale

internal fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

internal fun Double.format(decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", this)

internal fun Float.format(decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", this)
