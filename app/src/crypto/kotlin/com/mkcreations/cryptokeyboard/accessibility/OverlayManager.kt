package com.mkcreations.cryptokeyboard.accessibility

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"

        /** Single active manager (services run in the app's main process). */
        @Volatile
        private var current: OverlayManager? = null

        fun onManagerCreated(manager: OverlayManager) {
            current = manager
        }

        fun onManagerDestroyed(manager: OverlayManager) {
            if (current === manager) current = null
        }

        /** Called by panic mode: remove every overlay this process has added. */
        fun removeAllActiveOverlays() {
            current?.removeAllOverlays()
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activeOverlays = ConcurrentHashMap<String, OverlayEntry>()
    private var maxOverlays = 15

    data class OverlayEntry(
        val view: View,
        val params: WindowManager.LayoutParams,
        val bounds: Rect,
        val timestamp: Long
    )

    fun setMaxOverlays(max: Int) {
        maxOverlays = max
    }

    fun showOverlay(
        overlayId: String,
        decryptedText: String,
        keyLabel: String,
        bounds: Rect
    ) {
        if (activeOverlays.size >= maxOverlays && !activeOverlays.containsKey(overlayId)) {
            removeOldestOverlay()
        }

        val existing = activeOverlays[overlayId]
        if (existing != null) {
            if (existing.bounds != bounds) {
                updateOverlayPosition(overlayId, bounds)
            }
            return
        }

        try {
            val overlayView = createOverlayView(decryptedText, keyLabel, bounds)
            val params = createLayoutParams(bounds)

            windowManager.addView(overlayView, params)

            activeOverlays[overlayId] = OverlayEntry(
                view = overlayView,
                params = params,
                bounds = Rect(bounds),
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: $overlayId", e)
        }
    }

    fun updateOverlayPosition(overlayId: String, newBounds: Rect) {
        val entry = activeOverlays[overlayId] ?: return
        if (entry.bounds == newBounds) return

        try {
            val p = entry.params
            p.x = newBounds.left
            p.y = newBounds.top
            p.width = newBounds.width().coerceAtLeast(dp(100))
            p.height = newBounds.height().coerceAtLeast(dp(30))

            windowManager.updateViewLayout(entry.view, p)

            activeOverlays[overlayId] = entry.copy(bounds = Rect(newBounds))
        } catch (e: Exception) {
            Log.e(TAG, "Position update failed: $overlayId", e)
        }
    }

    /**
     * Instantly hides all overlays without removing them from tracking.
     * Used during scroll — overlays become invisible immediately,
     * then are properly removed/recreated when scroll ends.
     */
    fun hideAllOverlays() {
        for ((id, entry) in activeOverlays) {
            try {
                entry.view.visibility = View.INVISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Hide failed: $id", e)
            }
        }
    }

    fun removeOverlay(overlayId: String) {
        val entry = activeOverlays.remove(overlayId) ?: return
        try {
            windowManager.removeView(entry.view)
        } catch (e: Exception) {
            Log.e(TAG, "Remove failed: $overlayId", e)
        }
    }

    fun removeAllOverlays() {
        val entries = activeOverlays.entries.toList()
        activeOverlays.clear()
        for ((id, entry) in entries) {
            try {
                windowManager.removeView(entry.view)
            } catch (e: Exception) {
                Log.e(TAG, "Remove failed: $id", e)
            }
        }
    }

    fun removeOverlaysOutsideBounds(screenHeight: Int, screenWidth: Int) {
        val toRemove = mutableListOf<String>()
        for ((id, entry) in activeOverlays) {
            val b = entry.bounds
            if (b.bottom < -50 || b.top > screenHeight + 50 ||
                b.right < -50 || b.left > screenWidth + 50
            ) {
                toRemove.add(id)
            }
        }
        toRemove.forEach { removeOverlay(it) }
    }

    fun hasOverlay(overlayId: String): Boolean = activeOverlays.containsKey(overlayId)
    fun getActiveOverlayIds(): Set<String> = activeOverlays.keys.toSet()
    fun overlayCount(): Int = activeOverlays.size

    // ================================================================
    // VIEW CREATION
    // ================================================================

    private fun createOverlayView(
        decryptedText: String,
        keyLabel: String,
        bounds: Rect
    ): View {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val isRightAligned = bounds.centerX() > screenWidth / 2

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val bgColor: Int
        val borderColor: Int
        val keyColor: Int

        if (isRightAligned) {
            bgColor = 0xF5143020.toInt()
            borderColor = 0x884CAF50.toInt()
            keyColor = 0xFF66BB6A.toInt()
        } else {
            bgColor = 0xF51A1A2E.toInt()
            borderColor = 0x5542A5F5.toInt()
            keyColor = 0xFF64B5F6.toInt()
        }

        val bg = GradientDrawable().apply {
            setColor(bgColor)
            if (isRightAligned) {
                cornerRadii = floatArrayOf(
                    dpF(12), dpF(12),
                    dpF(3), dpF(3),
                    dpF(12), dpF(12),
                    dpF(12), dpF(12)
                )
            } else {
                cornerRadii = floatArrayOf(
                    dpF(3), dpF(3),
                    dpF(12), dpF(12),
                    dpF(12), dpF(12),
                    dpF(12), dpF(12)
                )
            }
            setStroke(dp(1), borderColor)
        }
        bubble.background = bg

        if (keyLabel.isNotEmpty()) {
            val keyRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val lockIcon = TextView(context).apply {
                text = "🔓"
                textSize = 8f
                setPadding(0, 0, dp(3), 0)
            }
            keyRow.addView(lockIcon)

            val keyNameText = TextView(context).apply {
                text = keyLabel
                setTextColor(keyColor)
                textSize = 8f
                setTypeface(null, Typeface.BOLD)
            }
            keyRow.addView(keyNameText)

            bubble.addView(keyRow)
        }

        val msgText = TextView(context).apply {
            text = decryptedText
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 14f
            setPadding(0, dp(2), 0, 0)
            maxLines = 20
            setLineSpacing(dp(2).toFloat(), 1f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        bubble.addView(msgText)

        container.addView(bubble)

        return container
    }

    private fun createLayoutParams(bounds: Rect): WindowManager.LayoutParams {
        val width = bounds.width().coerceAtLeast(dp(100))
        val height = bounds.height().coerceAtLeast(dp(30))

        return WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
    }

    private fun removeOldestOverlay() {
        val oldest = activeOverlays.entries.minByOrNull { it.value.timestamp }
        if (oldest != null) {
            removeOverlay(oldest.key)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun dpF(value: Int): Float =
        value * context.resources.displayMetrics.density
}