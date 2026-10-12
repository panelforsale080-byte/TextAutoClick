package com.example.textautoclick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickA11y"
        @Volatile var instance: AutoClickAccessibilityService? = null
            private set

        fun isRunning() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* gesture-only service */ }

    override fun onInterrupt() { /* nothing pending */ }

    /** One 50ms tap at (x,y). Returns false if dispatch was rejected (busy / no service). */
    fun performClick(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()
        return try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "tap cancelled by system")
                }
            }, null)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatch failed: ${t.message}")
            false
        }
    }
}
