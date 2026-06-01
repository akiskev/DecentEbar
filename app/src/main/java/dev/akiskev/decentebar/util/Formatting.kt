package dev.akiskev.decentebar.util

import java.util.Locale

/** Formats a Double with a fixed number of decimals using a stable (US) locale. */
fun Double.formatDecimals(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)
