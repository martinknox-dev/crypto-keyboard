package com.mkcreations.cryptokeyboard.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mkcreations.cryptokeyboard.model.*

class SettingsStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "cipher_settings_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        @Volatile
        private var instance: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().apply {
            // Security
            putBoolean("app_lock_enabled", settings.appLockEnabled)
            putString("lock_method", settings.lockMethod.name)
            putInt("lock_timeout", settings.lockTimeoutSeconds)
            putString("pin_hash", settings.pinHash)

            // Disguise
            putBoolean("disguise_enabled", settings.disguiseEnabled)
            putString("disguise_icon", settings.disguiseIcon.name)
            putString("disguise_app_name", settings.disguiseAppName)
            putBoolean("fake_calculator", settings.fakeCalculatorEnabled)

            // Messaging
            putString("default_cover_style", settings.defaultCoverStyle.name)
            putString("custom_cover_message", settings.customCoverMessage)
            putBoolean("auto_enable_exchange", settings.autoEnableExchange)
            putBoolean("smart_key_selection", settings.smartKeySelection)

            // Panic
            putBoolean("panic_enabled", settings.panicEnabled)
            putString("panic_trigger", settings.panicTrigger.name)
            putBoolean("panic_switch_keyboard", settings.panicSwitchKeyboard)
            putBoolean("panic_disable_exchange", settings.panicDisableExchange)
            putBoolean("panic_remove_overlays", settings.panicRemoveOverlays)
            putBoolean("panic_clear_cache", settings.panicClearCache)
            putBoolean("panic_lock_app", settings.panicLockApp)
            putBoolean("panic_clear_keys", settings.panicClearKeys)

            // Battery
            putInt("idle_timeout", settings.idleTimeoutMinutes)
            putInt("max_overlays", settings.maxOverlays)
            putInt("cache_duration", settings.cacheDurationMinutes)
            putString("animation_level", settings.animationLevel.name)

            apply()
        }
    }

    fun loadSettings(): AppSettings {
        return AppSettings(
            // Security
            appLockEnabled = prefs.getBoolean("app_lock_enabled", false),
            lockMethod = enumValueOrDefault("lock_method", LockMethod.NONE),
            lockTimeoutSeconds = prefs.getInt("lock_timeout", 300),
            pinHash = prefs.getString("pin_hash", "") ?: "",

            // Disguise
            disguiseEnabled = prefs.getBoolean("disguise_enabled", false),
            disguiseIcon = enumValueOrDefault("disguise_icon", DisguiseIcon.DEFAULT),
            disguiseAppName = prefs.getString("disguise_app_name", "Cipher") ?: "Cipher",
            fakeCalculatorEnabled = prefs.getBoolean("fake_calculator", false),

            // Messaging
            defaultCoverStyle = enumValueOrDefault("default_cover_style", CoverStyle.RANDOM_CASUAL),
            customCoverMessage = prefs.getString("custom_cover_message", "") ?: "",
            autoEnableExchange = prefs.getBoolean("auto_enable_exchange", false),
            smartKeySelection = prefs.getBoolean("smart_key_selection", true),

            // Panic
            panicEnabled = prefs.getBoolean("panic_enabled", false),
            panicTrigger = enumValueOrDefault("panic_trigger", PanicTrigger.SHAKE),
            panicSwitchKeyboard = prefs.getBoolean("panic_switch_keyboard", true),
            panicDisableExchange = prefs.getBoolean("panic_disable_exchange", true),
            panicRemoveOverlays = prefs.getBoolean("panic_remove_overlays", true),
            panicClearCache = prefs.getBoolean("panic_clear_cache", true),
            panicLockApp = prefs.getBoolean("panic_lock_app", false),
            panicClearKeys = prefs.getBoolean("panic_clear_keys", false),

            // Battery
            idleTimeoutMinutes = prefs.getInt("idle_timeout", 3),
            maxOverlays = prefs.getInt("max_overlays", 15),
            cacheDurationMinutes = prefs.getInt("cache_duration", 5),
            animationLevel = enumValueOrDefault("animation_level", AnimationLevel.FULL)
        )
    }

    fun isFirstLaunch(): Boolean {
        val first = prefs.getBoolean("first_launch", true)
        if (first) {
            prefs.edit().putBoolean("first_launch", false).apply()
        }
        return first
    }

    fun isAppLocked(): Boolean {
        val settings = loadSettings()
        val lastUnlock = prefs.getLong("last_unlock_time", 0)
        return com.mkcreations.cryptokeyboard.core.AppLockPolicy.isLocked(
            appLockEnabled = settings.appLockEnabled,
            pinConfigured = settings.pinHash.isNotEmpty(),
            lastUnlockAt = lastUnlock,
            timeoutSeconds = settings.lockTimeoutSeconds,
            nowMs = System.currentTimeMillis()
        )
    }

    fun recordUnlock() {
        prefs.edit().putLong("last_unlock_time", System.currentTimeMillis()).apply()
    }

    /** Sets the app-lock PIN, enabling the lock. Resets any backoff from the
     *  previous PIN and normalizes a non-positive stored timeout to 300 s. */
    fun setPin(pin: String) {
        val editor = prefs.edit()
            .putString("pin_hash", com.mkcreations.cryptokeyboard.crypto.PinHasher.hashPin(pin))
            .putBoolean("app_lock_enabled", true)
            .putLong("last_unlock_time", System.currentTimeMillis())
            .putInt("pin_failed_attempts", 0)
            .putLong("pin_lockout_until", 0L)
        if (prefs.getInt("lock_timeout", 300) <= 0) {
            editor.putInt("lock_timeout", 300)
        }
        editor.apply()
    }

    /** Removes the PIN and disables the lock (never lock without a PIN). */
    fun clearPin() {
        prefs.edit()
            .putString("pin_hash", "")
            .putBoolean("app_lock_enabled", false)
            .putInt("pin_failed_attempts", 0)
            .putLong("pin_lockout_until", 0L)
            .apply()
    }

    fun verifyPin(pin: String): Boolean =
        com.mkcreations.cryptokeyboard.crypto.PinHasher.verifyPin(pin, loadSettings().pinHash)

    /** Rate limiter for PIN attempts; state persisted in encrypted prefs. */
    fun pinRateLimiter(): com.mkcreations.cryptokeyboard.crypto.PinRateLimiter {
        val state = object : com.mkcreations.cryptokeyboard.crypto.PinRateLimiter.State {
            override fun attempts() = prefs.getInt("pin_failed_attempts", 0)
            override fun setAttempts(n: Int) { prefs.edit().putInt("pin_failed_attempts", n).apply() }
            override fun lockoutUntil() = prefs.getLong("pin_lockout_until", 0L)
            override fun setLockoutUntil(t: Long) { prefs.edit().putLong("pin_lockout_until", t).apply() }
        }
        return com.mkcreations.cryptokeyboard.crypto.PinRateLimiter(state)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(key: String, default: T): T {
        val name = prefs.getString(key, null) ?: return default
        return try {
            enumValueOf<T>(name)
        } catch (e: Exception) {
            default
        }
    }
}