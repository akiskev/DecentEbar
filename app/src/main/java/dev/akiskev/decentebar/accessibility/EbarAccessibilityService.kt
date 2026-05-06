package dev.akiskev.decentebar.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.akiskev.decentebar.engine.EbarParser
import dev.akiskev.decentebar.model.EbarSnapshot
import dev.akiskev.decentebar.model.SafetyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EbarAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isEnabled.value = true
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                publishSnapshot()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        publishSnapshot()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pollJob?.cancel()
        instance = null
        _isEnabled.value = false
        super.onDestroy()
    }

    fun clickStopOrFallback(safetyConfig: SafetyConfig = SafetyConfig()): Boolean {
        return clickNodeByLabel("Stop") || dispatchTap(safetyConfig.fallbackStopX, safetyConfig.fallbackStopY)
    }

    fun clickStart(): Boolean = clickNodeByLabel("Start")

    fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun publishSnapshot() {
        val root = rootInActiveWindow
        if (root == null) {
            _snapshots.value = EbarSnapshot(timestampMs = now())
            return
        }

        val descriptions = mutableListOf<String>()
        val texts = mutableListOf<String>()
        collectVisibleNodeValues(root, descriptions, texts)
        val (screenWidth, screenHeight) = screenSize()

        _snapshots.value = EbarParser.parseSnapshot(
            activePackage = root.packageName?.toString(),
            rawDescriptions = descriptions.distinct(),
            rawTexts = texts.distinct(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            timestampMs = now(),
            maxWeightG = SafetyConfig().maxReadableWeightG,
        )
    }

    private fun collectVisibleNodeValues(
        node: AccessibilityNodeInfo,
        descriptions: MutableList<String>,
        texts: MutableList<String>
    ) {
        if (node.isVisibleToUser) {
            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(descriptions::add)
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(texts::add)
        }

        repeat(node.childCount) { index ->
            node.getChild(index)?.let { child ->
                collectVisibleNodeValues(child, descriptions, texts)
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

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val TAP_DURATION_MS = 70L

        private var instance: EbarAccessibilityService? = null
        private val _isEnabled = MutableStateFlow(false)
        private val _snapshots = MutableStateFlow(EbarSnapshot())

        val isEnabled: StateFlow<Boolean> = _isEnabled
        val snapshots: StateFlow<EbarSnapshot> = _snapshots

        fun current(): EbarAccessibilityService? = instance
    }
}
