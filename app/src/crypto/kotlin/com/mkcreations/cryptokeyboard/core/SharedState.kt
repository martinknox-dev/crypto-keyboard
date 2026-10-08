package com.mkcreations.cryptokeyboard.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight shared state between keyboard service, accessibility service, and UI.
 * Uses regular SharedPreferences (not encrypted) for speed —
 * only stores flags, not sensitive data.
 */
object SharedState {

    private const val PREFS_NAME = "cipher_shared_state"
    private const val KEY_EXCHANGE_MODE = "exchange_mode_active"
    private const val KEY_ACTIVE_KEY_ID = "active_key_id"
    private const val KEY_KEYBOARD_VISIBLE = "keyboard_visible"
    private const val KEY_CURRENT_APP = "current_app_package"
    private const val KEY_LAST_ACTIVITY = "last_activity_timestamp"
    private const val KEY_CRYPTO_MODE = "crypto_mode_active"
    private const val KEY_ACCESSIBILITY_CONNECTED = "accessibility_connected"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
        }
    }

    private fun ensureInit(context: Context? = null) {
        if (prefs == null && context != null) {
            init(context)
        }
    }

    // --- Crypto Mode (keyboard swipe toggle) ---

    var isCryptoModeActive: Boolean
        get() = prefs?.getBoolean(KEY_CRYPTO_MODE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_CRYPTO_MODE, value)?.apply()
        }

    // --- Exchange Mode (accessibility overlay scanning) ---

    var isExchangeModeActive: Boolean
        get() = prefs?.getBoolean(KEY_EXCHANGE_MODE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_EXCHANGE_MODE, value)?.apply()
        }

    // --- Active Key ---

    var activeKeyId: String?
        get() = prefs?.getString(KEY_ACTIVE_KEY_ID, null)
        set(value) {
            prefs?.edit()?.putString(KEY_ACTIVE_KEY_ID, value)?.apply()
        }

    // --- Keyboard Visibility ---

    var isKeyboardVisible: Boolean
        get() = prefs?.getBoolean(KEY_KEYBOARD_VISIBLE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_KEYBOARD_VISIBLE, value)?.apply()
        }

    // --- Current Foreground App ---

    var currentAppPackage: String?
        get() = prefs?.getString(KEY_CURRENT_APP, null)
        set(value) {
            prefs?.edit()?.putString(KEY_CURRENT_APP, value)?.apply()
        }

    // NOTE: Draft secret/cover messages are deliberately NOT persisted here.
    // This file is plain (unencrypted) SharedPreferences; writing plaintext
    // secrets to it would leak them to anyone with device access. Drafts live
    // only in Compose state (memory) for the lifetime of the keyboard.

    // --- Accessibility Service Connection ---

    var isAccessibilityConnected: Boolean
        get() = prefs?.getBoolean(KEY_ACCESSIBILITY_CONNECTED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_ACCESSIBILITY_CONNECTED, value)?.apply()
        }

    // --- Activity Tracking (for idle timeout) ---

    fun recordActivity() {
        prefs?.edit()?.putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())?.apply()
    }

    fun getLastActivityTime(): Long {
        return prefs?.getLong(KEY_LAST_ACTIVITY, 0) ?: 0
    }

    fun isIdle(timeoutMinutes: Int): Boolean {
        val elapsed = System.currentTimeMillis() - getLastActivityTime()
        return elapsed > timeoutMinutes * 60 * 1000L
    }

    // --- Reset Everything ---

    fun resetAll() {
        prefs?.edit()?.clear()?.apply()
    }
}