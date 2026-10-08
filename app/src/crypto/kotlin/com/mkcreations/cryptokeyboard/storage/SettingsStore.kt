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
            lockTimeoutSeconds = prefs.getInt("lock_timeout", 0),
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
        if (!settings.appLockEnabled) return false
        val lastUnlock = prefs.getLong("last_unlock_time", 0)
        val elapsed = System.currentTimeMillis() - lastUnlock
        val timeoutMs = settings.lockTimeoutSeconds * 1000L
        return elapsed > timeoutMs
    }

    fun recordUnlock() {
        prefs.edit().putLong("last_unlock_time", System.currentTimeMillis()).apply()
    }

    // --- PIN Lockout (anti brute-force) ---

    /**
     * Records a failed PIN attempt and returns the timestamp until which the
     * PIN entry should be locked out. Backoff is exponential: 5s, 10s, 20s…
     * capped at 5 minutes.
     */
    fun recordPinFailure(): Long {
        val attempts = prefs.getInt("pin_failed_attempts", 0) + 1
        val shift = (attempts - 1).coerceIn(0, 6)
        val delayMs = (5_000L shl shift).coerceAtMost(300_000L)
        val lockoutUntil = System.currentTimeMillis() + delayMs
        prefs.edit()
            .putInt("pin_failed_attempts", attempts)
            .putLong("pin_lockout_until", lockoutUntil)
            .apply()
        return lockoutUntil
    }

    fun resetPinFailures() {
        prefs.edit()
            .remove("pin_failed_attempts")
            .remove("pin_lockout_until")
            .apply()
    }

    fun isPinLockedOut(): Boolean = getPinLockoutRemainingMs() > 0

    fun getPinLockoutRemainingMs(): Long {
        val until = prefs.getLong("pin_lockout_until", 0)
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    /**
     * Stores a new PIN hash and enables the app lock.
     */
    fun setPinHash(hash: String) {
        val settings = loadSettings()
        saveSettings(
            settings.copy(
                appLockEnabled = true,
                lockMethod = LockMethod.PIN,
                pinHash = hash
            )
        )
        resetPinFailures()
    }

    /**
     * Disables the app lock and wipes the stored PIN hash.
     */
    fun clearAppLock() {
        val settings = loadSettings()
        saveSettings(
            settings.copy(
                appLockEnabled = false,
                lockMethod = LockMethod.NONE,
                pinHash = ""
            )
        )
        resetPinFailures()
        prefs.edit().remove("last_unlock_time").apply()
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