package com.mkcreations.cryptokeyboard.core

import com.mkcreations.cryptokeyboard.model.AppSettings

/**
 * Pure encode/decode for the panic preference block, including one-time
 * migration from the legacy single-trigger schema.
 *
 * Legacy schema (read-only): `panic_trigger` held one of SHAKE / SCREEN_DOWN /
 * POWER_BUTTON / VOLUME_UP, plus separate action flags
 * `panic_switch_keyboard`, `panic_disable_exchange`, `panic_remove_overlays`,
 * `panic_clear_cache`, `panic_lock_app`, `panic_clear_keys`.
 *
 * New schema: independent trigger booleans + combined action booleans, all
 * prefixed `panic_trigger_` / `panic_action_`. The old `panic_enabled` key
 * survives as the master switch (default off).
 */
object PanicSettingsCodec {

    // New-format keys
    const val KEY_MASTER = "panic_enabled"
    const val KEY_SHAKE = "panic_trigger_shake"
    const val KEY_FACE_DOWN = "panic_trigger_face_down"
    const val KEY_SPACEBAR = "panic_trigger_spacebar"
    const val KEY_VOLUME = "panic_trigger_volume_sequence"
    const val KEY_SWITCH_IME = "panic_action_switch_ime"
    const val KEY_REMOVE_OVERLAYS = "panic_action_remove_overlays"
    const val KEY_CLEAR_CACHE = "panic_action_clear_cache"
    const val KEY_WIPE_KEYS = "panic_action_wipe_keys"

    // Legacy keys (only consulted while the new format is absent)
    private const val KEY_LEGACY_TRIGGER = "panic_trigger"
    private const val KEY_LEGACY_SWITCH = "panic_switch_keyboard"
    private const val KEY_LEGACY_REMOVE_OVERLAYS = "panic_remove_overlays"
    private const val KEY_LEGACY_CLEAR_CACHE = "panic_clear_cache"
    private const val KEY_LEGACY_LOCK = "panic_lock_app"
    private const val KEY_LEGACY_KEYS = "panic_clear_keys"

    /**
     * Decodes the panic block from raw preferences into [base], preserving
     * [base]'s non-panic fields. Fresh installs keep [base]'s defaults
     * (master off, shake on); legacy installs are migrated deterministically.
     */
    fun decode(prefs: Map<String, Any?>, base: AppSettings = AppSettings()): AppSettings {
        if (prefs.containsKey(KEY_SHAKE)) {
            // New format: trust the new keys, fall back to base defaults.
            return base.copy(
                panicEnabled = prefs.boolean(KEY_MASTER, base.panicEnabled),
                panicTriggerShake = prefs.boolean(KEY_SHAKE, base.panicTriggerShake),
                panicTriggerFaceDown = prefs.boolean(KEY_FACE_DOWN, base.panicTriggerFaceDown),
                panicTriggerSpacebar = prefs.boolean(KEY_SPACEBAR, base.panicTriggerSpacebar),
                panicTriggerVolumeSequence = prefs.boolean(KEY_VOLUME, base.panicTriggerVolumeSequence),
                panicActionSwitchIme = prefs.boolean(KEY_SWITCH_IME, base.panicActionSwitchIme),
                panicActionRemoveOverlays = prefs.boolean(KEY_REMOVE_OVERLAYS, base.panicActionRemoveOverlays),
                panicActionClearCache = prefs.boolean(KEY_CLEAR_CACHE, base.panicActionClearCache),
                panicActionWipeKeys = prefs.boolean(KEY_WIPE_KEYS, base.panicActionWipeKeys),
            )
        }

        val isLegacy = prefs.containsKey(KEY_LEGACY_TRIGGER) || prefs.containsKey(KEY_LEGACY_SWITCH)
        if (!isLegacy) return base // fresh install: keep AppSettings defaults

        // Legacy schema: one trigger enum maps onto the independent booleans.
        // POWER_BUTTON / VOLUME_UP were never implemented as detectors, so
        // they migrate to "no trigger selected" (user re-picks one).
        val legacyTrigger = prefs[KEY_LEGACY_TRIGGER] as? String ?: "SHAKE"
        return base.copy(
            panicEnabled = prefs.boolean(KEY_MASTER, base.panicEnabled),
            panicTriggerShake = legacyTrigger == "SHAKE",
            panicTriggerFaceDown = legacyTrigger == "SCREEN_DOWN",
            panicTriggerSpacebar = false,
            panicTriggerVolumeSequence = false,
            panicActionSwitchIme = prefs.boolean(KEY_LEGACY_SWITCH, base.panicActionSwitchIme),
            panicActionRemoveOverlays = prefs.boolean(KEY_LEGACY_REMOVE_OVERLAYS, base.panicActionRemoveOverlays),
            // Combined cache+lock toggle: ON if either legacy toggle wanted it.
            panicActionClearCache = prefs.boolean(KEY_LEGACY_CLEAR_CACHE, base.panicActionClearCache) ||
                prefs.boolean(KEY_LEGACY_LOCK, false),
            panicActionWipeKeys = prefs.boolean(KEY_LEGACY_KEYS, base.panicActionWipeKeys),
        )
    }

    /** The panic preference entries to persist for [settings]. */
    fun encode(settings: AppSettings): Map<String, Boolean> = mapOf(
        KEY_MASTER to settings.panicEnabled,
        KEY_SHAKE to settings.panicTriggerShake,
        KEY_FACE_DOWN to settings.panicTriggerFaceDown,
        KEY_SPACEBAR to settings.panicTriggerSpacebar,
        KEY_VOLUME to settings.panicTriggerVolumeSequence,
        KEY_SWITCH_IME to settings.panicActionSwitchIme,
        KEY_REMOVE_OVERLAYS to settings.panicActionRemoveOverlays,
        KEY_CLEAR_CACHE to settings.panicActionClearCache,
        KEY_WIPE_KEYS to settings.panicActionWipeKeys,
    )

    private fun Map<String, Any?>.boolean(key: String, default: Boolean): Boolean =
        this[key] as? Boolean ?: default
}
