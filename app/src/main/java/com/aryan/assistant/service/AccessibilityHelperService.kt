package com.aryan.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityHelperService : AccessibilityService() {

    companion object {
        private const val TAG = "AryanAccessibility"
        var instance: AccessibilityHelperService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${AccessibilityHelperService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                    componentName.contains("AccessibilityHelperService")
                ) {
                    return true
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityHelperService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to track active window package or events if needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityHelperService interrupted")
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    fun closeCurrentApp(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun openQuickSettings(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    fun clickOnText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            if (target != null && target.isClickable) {
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focusedNode != null && focusedNode.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollableNodes = findScrollableNodes(rootNode)
        for (node in scrollableNodes) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true
            }
        }

        // Fallback swipe gesture
        val path = Path().apply {
            moveTo(500f, 1400f)
            lineTo(500f, 400f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollableNodes = findScrollableNodes(rootNode)
        for (node in scrollableNodes) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                return true
            }
        }

        // Fallback swipe gesture
        val path = Path().apply {
            moveTo(500f, 400f)
            lineTo(500f, 1400f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findScrollableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun recurse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isScrollable) result.add(node)
            for (i in 0 until node.childCount) {
                recurse(node.getChild(i))
            }
        }
        recurse(root)
        return result
    }

    fun wakeAndUnlock(pattern: List<Int>): Boolean {
        wakeScreen()
        return unlockWithPattern(pattern)
    }

    fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "aryan:wake_screen"
            )
            wakeLock?.acquire(3000L)
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen", e)
        }
    }

    fun unlockWithPattern(pattern: List<Int>): Boolean {
        if (pattern.size < 2) return false
        wakeScreen()

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        // 1. Swipe up from bottom to reveal pattern lock screen if keyguard is active
        val swipePath = Path().apply {
            moveTo(screenWidth / 2f, screenHeight * 0.85f)
            lineTo(screenWidth / 2f, screenHeight * 0.30f)
        }
        val swipeGesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 220L))
            .build()

        return dispatchGesture(swipeGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Wait for the pattern grid UI to settle after swipe up
                Handler(Looper.getMainLooper()).postDelayed({
                    dispatchPatternGesture(pattern, screenWidth, screenHeight)
                }, 350L)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // Fallback: attempt direct drawing
                dispatchPatternGesture(pattern, screenWidth, screenHeight)
            }
        }, null)
    }

    private fun dispatchPatternGesture(pattern: List<Int>, screenWidth: Float, screenHeight: Float): Boolean {
        if (pattern.size < 2) return false

        // Standard Android pattern grid coordinates
        val gridWidth = screenWidth * 0.72f
        val startX = (screenWidth - gridWidth) / 2f
        val cellSpacing = gridWidth / 2f

        // Center vertically around 58% of screen
        val patternCenterY = screenHeight * 0.58f
        val startY = patternCenterY - cellSpacing

        fun getX(node: Int): Float = startX + (node % 3) * cellSpacing
        fun getY(node: Int): Float = startY + (node / 3) * cellSpacing

        val path = Path().apply {
            val first = pattern[0]
            moveTo(getX(first), getY(first))
            for (i in 1 until pattern.size) {
                val node = pattern[i]
                lineTo(getX(node), getY(node))
            }
        }

        val strokeDuration = (pattern.size * 120L).coerceIn(300L, 800L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, strokeDuration))
            .build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Pattern unlock gesture finished successfully")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Pattern unlock gesture cancelled")
            }
        }, null)
    }
}
