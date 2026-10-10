package com.mkcreations.cryptokeyboard.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mkcreations.cryptokeyboard.storage.SettingsStore
import dev.patrickgold.florisboard.ime.text.CryptoBarHook

/**
 * PIN gate shown when the app lock is engaged. On success it records the
 * unlock and finishes. If no PIN is configured it finishes immediately
 * (defensive: never brick the keyboard).
 */
class AppLockActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var pinInput: EditText
    private lateinit var statusText: TextView
    private lateinit var unlockButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore.getInstance(this)
        if (!store.isAppLocked()) {
            finish()
            return
        }
        buildUi()
        applyBackoffIfAny()
    }

    override fun onDestroy() {
        // Re-arm the launch guard in CryptoBarHook whether we finished by
        // unlock or by the user backing out (a backing-out user is re-locked
        // on the next keyboard show).
        CryptoBarHook.onAppLockClosed()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(0xFF12121F.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Crypto Keyboard Locked"
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Enter your PIN"
            setTextColor(0xFF888899.toInt())
            textSize = 13f
            setPadding(0, 24, 0, 8)
        })
        pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF555566.toInt())
            hint = "PIN"
        }
        root.addView(pinInput)
        statusText = TextView(this).apply {
            setTextColor(0xFFFF8A80.toInt())
            textSize = 12f
            setPadding(0, 12, 0, 0)
        }
        root.addView(statusText)
        unlockButton = Button(this).apply { text = "Unlock" }
        root.addView(unlockButton)
        setContentView(root)

        unlockButton.setOnClickListener { attemptUnlock() }
    }

    private fun applyBackoffIfAny() {
        val delay = store.pinRateLimiter().retryDelayMs(System.currentTimeMillis())
        if (delay > 0) {
            unlockButton.isEnabled = false
            statusText.text = "Too many attempts — wait ${delay / 1000 + 1}s"
            unlockButton.postDelayed({
                unlockButton.isEnabled = true
                statusText.text = ""
            }, delay)
        }
    }

    private var verifying = false

    private fun attemptUnlock() {
        if (verifying) return
        val pin = pinInput.text.toString()
        if (pin.isEmpty()) return
        val limiter = store.pinRateLimiter()
        if (limiter.retryDelayMs(System.currentTimeMillis()) > 0) return
        verifying = true
        unlockButton.isEnabled = false
        statusText.text = "Checking…"
        // PBKDF2-600k takes ~0.5–2s; never run it on the UI thread
        Thread {
            val ok = store.verifyPin(pin)
            runOnUiThread {
                verifying = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                unlockButton.isEnabled = true
                if (ok) {
                    limiter.recordSuccess()
                    store.recordUnlock()
                    finish()
                } else {
                    val now = System.currentTimeMillis()
                    limiter.recordFailure(now)
                    statusText.text = "Wrong PIN"
                    pinInput.setText("")
                    applyBackoffIfAny()
                }
            }
        }.start()
    }
}
