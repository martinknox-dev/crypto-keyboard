package com.mkcreations.cryptokeyboard.ime

import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Detects horizontal swipes on the spacebar to toggle crypto mode.
 *
 * Usage: Attach this as a touch listener to the spacebar view,
 * or use the static detection method with raw coordinates.
 *
 * This is designed to be called from outside FlorisBoard's code —
 * the bridge layer will intercept spacebar touches and delegate here.
 */
class SpacebarGestureHandler(
    private val onSwipeLeft: () -> Unit,
    private val onSwipeRight: () -> Unit
) : View.OnTouchListener {

    companion object {
        private const val TAG = "SpacebarGesture"
        private const val MIN_SWIPE_DISTANCE = 100f   // minimum px to register swipe
        private const val MAX_SWIPE_Y_DRIFT = 80f      // max vertical drift allowed
        private const val MIN_SWIPE_VELOCITY = 200f     // px/second
    }

    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                startTime = System.currentTimeMillis()
                return false  // Don't consume — let spacebar still work
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = event.rawX - startX
                val deltaY = event.rawY - startY
                val elapsed = System.currentTimeMillis() - startTime

                if (elapsed > 0 && abs(deltaX) > MIN_SWIPE_DISTANCE
                    && abs(deltaY) < MAX_SWIPE_Y_DRIFT
                ) {
                    val velocity = abs(deltaX) / (elapsed / 1000f)
                    if (velocity > MIN_SWIPE_VELOCITY) {
                        if (deltaX > 0) {
                            Log.d(TAG, "Swipe RIGHT detected — velocity: $velocity")
                            onSwipeRight()
                            return true  // Consume the event
                        } else {
                            Log.d(TAG, "Swipe LEFT detected — velocity: $velocity")
                            onSwipeLeft()
                            return true
                        }
                    }
                }
                return false
            }
        }
        return false
    }

    /**
     * Static method for manual coordinate-based detection
     * (for when we can't attach a touch listener directly)
     */
    object Detector {
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L

        fun onDown(x: Float, y: Float) {
            downX = x
            downY = y
            downTime = System.currentTimeMillis()
        }

        fun onUp(x: Float, y: Float): SwipeDirection? {
            val deltaX = x - downX
            val deltaY = y - downY
            val elapsed = System.currentTimeMillis() - downTime

            if (elapsed <= 0) return null
            if (abs(deltaX) < MIN_SWIPE_DISTANCE) return null
            if (abs(deltaY) > MAX_SWIPE_Y_DRIFT) return null

            val velocity = abs(deltaX) / (elapsed / 1000f)
            if (velocity < MIN_SWIPE_VELOCITY) return null

            return if (deltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
        }
    }

    enum class SwipeDirection {
        LEFT, RIGHT
    }
}