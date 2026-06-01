package dev.akiskev.decentebar.util

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * Returns the device's screen size in pixels as (width, height). Uses the modern
 * WindowMetrics API on Android R+ and falls back to displayMetrics below that.
 * Works with any Context (Application or Service).
 */
fun screenSizePx(context: Context): Pair<Int, Int> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm?.maximumWindowMetrics?.bounds
        if (bounds != null) bounds.width() to bounds.height() else 0 to 0
    } else {
        @Suppress("DEPRECATION")
        val m = context.resources.displayMetrics
        m.widthPixels to m.heightPixels
    }
}
