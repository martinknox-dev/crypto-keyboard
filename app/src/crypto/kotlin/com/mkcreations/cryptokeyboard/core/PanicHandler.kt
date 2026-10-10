package com.mkcreations.cryptokeyboard.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.inputmethod.InputMethodManager
// Removed old AccessibilityService import
import com.mkcreations.cryptokeyboard.crypto.DecryptCache
import com.mkcreations.cryptokeyboard.crypto.ReplayGuard
import com.mkcreations.cryptokeyboard.model.PanicTrigger
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore
import com.mkcreations.cryptokeyboard.storage.SettingsStore
import kotlin.math.sqrt

class PanicHandler(private val context: Context) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var isListening = false

    // Shake detection
    private var lastShakeTime = 0L
    private var shakeCount = 0
    private val SHAKE_THRESHOLD = 15.0f
    private val SHAKE_RESET_TIME = 1500L     // Reset count after 1.5s
    private val SHAKE_REQUIRED_COUNT = 3      // 3 shakes to trigger

    // Callbacks
    private var onPanicTriggered: (() -> Unit)? = null

    fun setOnPanicTriggered(callback: () -> Unit) {
        onPanicTriggered = callback
    }

    fun startListening(trigger: PanicTrigger) {
        if (isListening) stopListening()

        when (trigger) {
            PanicTrigger.SHAKE -> startShakeDetection()
            PanicTrigger.SCREEN_DOWN -> startOrientationDetection()
            PanicTrigger.POWER_BUTTON -> {
                // Power button detection requires screen off broadcast
                // Handled separately in service
            }
            PanicTrigger.VOLUME_UP -> {
                // Volume button detection handled in keyboard service
            }
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
        isListening = false
    }

    private fun startShakeDetection() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
            isListening = true
        }
    }

    private fun startOrientationDetection() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isListening = true
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val settings = SettingsStore.getInstance(context).loadSettings()

        when (settings.panicTrigger) {
            PanicTrigger.SHAKE -> detectShake(x, y, z)
            PanicTrigger.SCREEN_DOWN -> detectScreenDown(z)
            else -> {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun detectShake(x: Float, y: Float, z: Float) {
        // Calculate acceleration magnitude (minus gravity)
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val acceleration = magnitude - SensorManager.GRAVITY_EARTH

        if (acceleration > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()

            if (now - lastShakeTime > SHAKE_RESET_TIME) {
                shakeCount = 0
            }

            shakeCount++
            lastShakeTime = now

            if (shakeCount >= SHAKE_REQUIRED_COUNT) {
                shakeCount = 0
                executePanic()
            }
        }
    }

    private fun detectScreenDown(zAcceleration: Float) {
        // When phone is face down, z-axis shows negative gravity
        if (zAcceleration > 8.0f) { // Phone is face down (z > ~8 m/s² means gravity pulling from top)
            executePanic()
        }
    }

    private fun executePanic() {
        val settings = SettingsStore.getInstance(context).loadSettings()

        // Step 1: Remove all overlays (instant)
        if (settings.panicRemoveOverlays) {
            try {
                com.mkcreations.cryptokeyboard.accessibility.OverlayManager.removeAllActiveOverlays()
            } catch (e: Exception) {
                // Overlay removal is best-effort during panic
            }
        }

        // Step 2: Clear cache (plaintext) and the replay dedup state
        if (settings.panicClearCache) {
            DecryptCache.clearAll()
            ReplayGuard.shared.clear()
        }

        // Step 3: Disable exchange mode
        if (settings.panicDisableExchange) {
            SharedState.isExchangeModeActive = false
        }

        // Step 4: Switch keyboard
        if (settings.panicSwitchKeyboard) {
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (e: Exception) {
                // May not work from all contexts
            }
        }

        // Step 5: Lock app
        if (settings.panicLockApp) {
            SettingsStore.getInstance(context).saveSettings(
                settings.copy(appLockEnabled = true)
            )
        }

        // Step 6: Clear all keys (DANGEROUS — user opted in)
        if (settings.panicClearKeys) {
            SecureKeyStore.getInstance(context).deleteAllKeys()
        }

        // Notify callback
        onPanicTriggered?.invoke()
    }
}