package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.AppSettings

/**
 * Central holder of the in-memory session state that can suppress or
 * short-circuit message display: the [DecryptCache] (decrypted plaintexts) and
 * the [ReplayGuard] (seen-payload history).
 *
 * Clearing both together gives *reboot semantics* — every previously processed
 * message re-decrypts and re-locates on the next scan, exactly as if the app
 * had restarted (see SECURITY.md: a restart or cache clear forgets history).
 * The two stores share one expiry window, driven by
 * [AppSettings.cacheDurationMinutes], so a cache entry can never outlive its
 * replay gate (or vice versa).
 */
object CryptoSession {

    /**
     * Forgets all decrypted messages and replay history. Called on every
     * reboot-semantics trigger: exchange mode toggle (both directions), app
     * switch, idle auto-off, panic, and any change to the key set.
     */
    fun forgetAll() {
        DecryptCache.clearAll()
        ReplayGuard.shared.clear()
    }

    /**
     * Applies settings that govern session memory. Keeps both stores' TTLs on
     * the single [AppSettings.cacheDurationMinutes] value.
     */
    fun applySettings(settings: AppSettings) {
        setCacheDuration(settings.cacheDurationMinutes)
    }

    /**
     * Sets the shared expiry window (minutes) for both stores.
     */
    fun setCacheDuration(minutes: Int) {
        DecryptCache.setCacheDuration(minutes)
        ReplayGuard.shared.setTtlDuration(minutes)
    }
}
