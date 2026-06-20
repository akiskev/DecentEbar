package dev.akiskev.decentebar.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.akiskev.decentebar.engine.EbarParser
import dev.akiskev.decentebar.model.AccessibilityNodeBounds
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.SafetyConfig
import dev.akiskev.decentebar.model.isEbarPackage
import dev.akiskev.decentebar.util.screenSizePx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class EbarAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var shouldRunJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isEnabled.value = true
        shouldRunJob?.cancel()
        shouldRunJob = serviceScope.launch {
            _shouldRun.collect { active ->
                if (active) startPolling() else stopPolling()
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = serviceScope.launch {
            while (isActive) {
                publishSnapshot()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!_shouldRun.value) return
        publishSnapshot()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pollJob?.cancel()
        shouldRunJob?.cancel()
        instance = null
        _isEnabled.value = false
        super.onDestroy()
    }

    fun clickStopOrFallback(safetyConfig: SafetyConfig = SafetyConfig()): Boolean {
        if (clickNodeByLabel("Stop")) return true
        val (w, h) = screenSize()
        if (w <= 0 || h <= 0) return false
        val x = (safetyConfig.fallbackStopRx * w).toFloat()
        val y = (safetyConfig.fallbackStopRy * h).toFloat()
        return dispatchTap(x, y)
    }

    fun clickStart(): Boolean = clickNodeByLabel("Start")

    fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Drags from (startX, startY) to (endX, endY). The e-bar 3.1.0 pressure bar is a
     * slider that only moves on a swipe with enough travel — a plain tap opens a
     * manual-entry popup instead — so pressure is set by sliding to the target Y and
     * releasing there.
     */
    fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Boolean {
        // Duration scales with distance to hold a roughly constant drag velocity: a big
        // jump dispatched over a fixed short time reads as a fling (the slider ignores
        // the release position), while the same path drawn slower registers as a drag.
        val distance = abs(endY - startY) + abs(endX - startX)
        val durationMs = (distance / SWIPE_VELOCITY_PX_PER_MS).toLong()
            .coerceIn(MIN_SWIPE_DURATION_MS, MAX_SWIPE_DURATION_MS)
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCancelled(description: GestureDescription?) {
                Log.w(LOG_TAG, "pressure swipe CANCELLED ${startX.toInt()},${startY.toInt()}->${endX.toInt()},${endY.toInt()}")
            }
        }
        return dispatchGesture(gesture, callback, null)
    }

    fun publishSnapshot() {
        _snapshots.value = captureSnapshot()
    }

    fun captureSnapshot(): EbarSnapshot {
        val root = rootInActiveWindow ?: return EbarSnapshot(timestampMs = now())
        val packageName = root.packageName?.toString()
        val (screenWidth, screenHeight) = screenSize()

        if (!isEbarPackage(packageName)) {
            return EbarParser.parseSnapshot(
                activePackage = packageName,
                rawDescriptions = emptyList(),
                rawTexts = emptyList(),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                timestampMs = now(),
                maxWeightG = SafetyConfig().maxReadableWeightG,
            )
        }

        val descriptions = mutableListOf<String>()
        val texts = mutableListOf<String>()
        val nodes = mutableListOf<AccessibilityNodeBounds>()
        collectVisibleNodeValues(root, descriptions, texts, nodes)

        return EbarParser.parseSnapshot(
            activePackage = packageName,
            rawDescriptions = descriptions.distinct(),
            rawTexts = texts.distinct(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            timestampMs = now(),
            maxWeightG = SafetyConfig().maxReadableWeightG,
            nodes = nodes,
        )
    }

    private fun collectVisibleNodeValues(
        node: AccessibilityNodeInfo,
        descriptions: MutableList<String>,
        texts: MutableList<String>,
        nodes: MutableList<AccessibilityNodeBounds>
    ) {
        if (node.isVisibleToUser) {
            val description = node.contentDescription?.toString()?.takeIf(String::isNotBlank)
            val text = node.text?.toString()?.takeIf(String::isNotBlank)
            description?.let(descriptions::add)
            text?.let(texts::add)

            val bounds = Rect().also(node::getBoundsInScreen)
            if (!bounds.isEmpty) {
                nodes.add(
                    AccessibilityNodeBounds(
                        text = text,
                        contentDescription = description,
                        className = node.className?.toString(),
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        clickable = node.isClickable,
                        scrollable = node.isScrollable
                    )
                )
            }
        }

        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child ->
                collectVisibleNodeValues(child, descriptions, texts, nodes)
            }
        }
    }

    private fun clickNodeByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByLabel(root, label) ?: return false
        val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: node
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodeByLabel(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null

        val description = node.contentDescription?.toString()
        val text = node.text?.toString()
        if (description.equals(label, ignoreCase = true) || text.equals(label, ignoreCase = true)) {
            return node
        }

        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child ->
                val found = findNodeByLabel(child, label)
                if (found != null) return found
            }
        }

        return null
    }

    private fun screenSize(): Pair<Int, Int> = screenSizePx(this)

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val LOG_TAG = "DecentEbar"
        private const val POLL_INTERVAL_MS = 50L
        private const val TAP_DURATION_MS = 70L
        // Drag velocity for pressure swipes. ~117px swipes at 150ms (≈0.78 px/ms)
        // registered as drags; ~449px at 150ms (≈3.0 px/ms) flung past the target and
        // were ignored. Hold velocity near the known-good drag rate and clamp duration.
        private const val SWIPE_VELOCITY_PX_PER_MS = 0.7f
        private const val MIN_SWIPE_DURATION_MS = 120L
        private const val MAX_SWIPE_DURATION_MS = 800L

        private var instance: EbarAccessibilityService? = null
        private val _isEnabled = MutableStateFlow(false)
        private val _snapshots = MutableStateFlow(EbarSnapshot())
        private val _shouldRun = MutableStateFlow(false)

        val isEnabled: StateFlow<Boolean> = _isEnabled
        val snapshots: StateFlow<EbarSnapshot> = _snapshots
        val shouldRun: StateFlow<Boolean> = _shouldRun

        fun current(): EbarAccessibilityService? = instance

        fun setShouldRun(value: Boolean) {
            _shouldRun.value = value
        }

        /**
         * Authoritative check of whether the user has enabled this accessibility service in system
         * settings, independent of whether the service is currently bound in this process. Used on
         * app start/resume so the UI can prompt immediately when it's off, without waiting for (or
         * being misled by) a not-yet-completed bind after a cold start.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, EbarAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
            }
            return false
        }
    }
}
