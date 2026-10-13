package com.mkcreations.cryptokeyboard.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
// Removed old AccessibilityService import
import com.mkcreations.cryptokeyboard.crypto.CryptoSession
import com.mkcreations.cryptokeyboard.model.AppSettings
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore
import com.mkcreations.cryptokeyboard.storage.SettingsStore
import kotlin.math.sqrt

class PanicHandler(private val context: Context) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var isListening = false

    // Face-down = downward tilt AND proximity covered (edge-triggered)
    private val faceDownDetector = FaceDownDetector()
    private val faceDownZThreshold = -6.0f   // screen pointing down (z axis points out of the screen)

    // Shake detection
    private var lastShakeTime = 0L
    private var shakeCount = 0
    private val SHAKE_THRESHOLD = 15.0f
    private val SHAKE_RESET_TIME = 1500L     // Reset count after 1.5s
    private val SHAKE_REQUIRED_COUNT = 3      // 3 shakes to trigger
    // Diagnostics: log the strongest shake peak seen per reset window (rate-limited)
    private var shakePeakLoggedAt = 0L
    private var shakePeakSinceLog = 0f

    // Sensor callbacks fire up to ~200x/s: never re-read (and decrypt) the
    // whole preference store per event — the service refreshes this snapshot
    // whenever settings change (PanicSettingsScreen → refreshPanicTriggers).
    @Volatile
    private var currentSettings: AppSettings? = null

    // Callbacks
    private var onPanicTriggered: (() -> Unit)? = null

    fun setOnPanicTriggered(callback: () -> Unit) {
        onPanicTriggered = callback
    }

    /** Registers only the sensors the current configuration needs. */
    fun startListening(settings: AppSettings) {
        currentSettings = settings
        if (isListening) stopListening()
        if (PanicConfig.usesAccelerometer(settings)) startAccelerometer()
        if (PanicConfig.usesProximity(settings)) startProximity()
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
        accelerometer = null
        proximitySensor = null
        isListening = false
    }

    /** External triggers (spacebar gestures) enter here. */
    fun triggerPanic() {
        executePanic()
    }

    private fun startAccelerometer() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
            isListening = true
            Log.d("CryptoPanic", "accelerometer registered (shake/tilt triggers active)")
        } else {
            Log.w("CryptoPanic", "NO accelerometer on this device — shake/face-down cannot fire")
        }
    }

    private fun startProximity() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor != null) {
            sensorManager?.registerListener(
                this,
                proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isListening = true
            Log.d("CryptoPanic", "proximity registered (face-down trigger active)")
        } else {
            Log.w("CryptoPanic", "NO proximity sensor on this device — face-down cannot fire")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccelerometerEvent(event)
            Sensor.TYPE_PROXIMITY -> onProximityEvent(event)
        }
    }

    private fun onAccelerometerEvent(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val settings = currentSettings ?: return
        if (!PanicConfig.isArmed(settings)) return

        if (settings.panicTriggerShake) detectShake(x, y, z)
        if (settings.panicTriggerFaceDown) {
            val faceDownNow = z < faceDownZThreshold
            if (faceDownDetector.onTilt(faceDownNow, System.currentTimeMillis())) {
                executePanic()
            }
        }
    }

    private fun onProximityEvent(event: SensorEvent) {
        val settings = currentSettings ?: return
        if (!PanicConfig.isArmed(settings) || !settings.panicTriggerFaceDown) return
        val sensor = proximitySensor ?: return
        val near = event.values[0] < sensor.maximumRange / 2f
        if (faceDownDetector.onProximityNear(near, System.currentTimeMillis())) {
            executePanic()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun detectShake(x: Float, y: Float, z: Float) {
        // Calculate acceleration magnitude (minus gravity)
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val acceleration = magnitude - SensorManager.GRAVITY_EARTH

        // Diagnostics: track the strongest peak so on-device logs show whether
        // shakes are reaching the threshold (emulators often report a flat
        // accelerometer, so no real shake can ever be detected there).
        if (acceleration > shakePeakSinceLog) shakePeakSinceLog = acceleration
        val nowForLog = System.currentTimeMillis()
        if (nowForLog - shakePeakLoggedAt > SHAKE_RESET_TIME) {
            Log.d(
                "CryptoPanic",
                "shake: peak=$shakePeakSinceLog threshold=$SHAKE_THRESHOLD " +
                    "count=$shakeCount"
            )
            shakePeakSinceLog = 0f
            shakePeakLoggedAt = nowForLog
        }

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

    private fun executePanic() {
        val settings = currentSettings
            ?: SettingsStore.getInstance(context).loadSettings()
        // The master switch may have been turned off after registration.
        if (!PanicConfig.isArmed(settings)) return

        for (action in PanicActionPlan.actions(settings)) {
            try {
                when (action) {
                    // Overlays + exchange off: both stop the visible bubble flow.
                    PanicAction.REMOVE_OVERLAYS -> {
                        com.mkcreations.cryptokeyboard.accessibility.OverlayManager
                            .removeAllActiveOverlays()
                        SharedState.isExchangeModeActive = false
                    }
                    // Flush plaintext caches and force the app to relock.
                    PanicAction.CLEAR_CACHE_AND_LOCK -> {
                        CryptoSession.forgetAll()
                        SettingsStore.getInstance(context).forceRelock()
                    }
                    PanicAction.SWITCH_IME -> switchKeyboard()
                    // DANGEROUS — user opted in via the confirmation dialog.
                    PanicAction.WIPE_KEYS -> {
                        SecureKeyStore.getInstance(context).deleteAllKeys()
                    }
                }
            } catch (e: Exception) {
                // Panic actions are best-effort; keep executing the rest.
            }
        }

        // Notify callback
        onPanicTriggered?.invoke()
    }

    /**
     * Switches back to the previously used IME when our keyboard service is
     * alive. If no previous IME can be switched to (our IME not running, or no
     * other IME enabled), this does nothing rather than falling back to the IME
     * picker — the user explicitly wants a silent switch back to the previous
     * keyboard, never an unrelated picker dialog.
     */
    private fun switchKeyboard() {
        val switched =
            dev.patrickgold.florisboard.FlorisImeService.switchToPrevInputMethod()
        Log.d("CryptoPanic", "switchKeyboard: switchedToPrevious=$switched")
    }
}