package com.mkcreations.cryptokeyboard.core

import com.mkcreations.cryptokeyboard.model.AppSettings

/** Every switch shown on the Panic settings screen. */
enum class PanicToggle {
    MASTER,
    TRIGGER_SHAKE,
    TRIGGER_FACE_DOWN,
    TRIGGER_SPACEBAR,
    TRIGGER_VOLUME,
    ACTION_SWITCH_IME,
    ACTION_REMOVE_OVERLAYS,
    ACTION_CLEAR_CACHE,
    ACTION_WIPE_KEYS,
}

/**
 * Pure decision rules for the panic system: arming, which screen toggles are
 * interactive, and which hardware sensors a configuration requires.
 */
object PanicConfig {

    fun anyTrigger(s: AppSettings): Boolean =
        s.panicTriggerShake || s.panicTriggerFaceDown ||
            s.panicTriggerSpacebar || s.panicTriggerVolumeSequence

    /** The system only fires when the master switch is on AND a trigger is on. */
    fun isArmed(s: AppSettings): Boolean = s.panicEnabled && anyTrigger(s)

    /**
     * While the master switch is off, everything is locked except the master
     * itself and the switch-IME action (user decision: the keyboard-switch
     * option stays usable and defaults to on).
     */
    fun isToggleEnabled(s: AppSettings, toggle: PanicToggle): Boolean = when (toggle) {
        PanicToggle.MASTER, PanicToggle.ACTION_SWITCH_IME -> true
        else -> s.panicEnabled
    }

    fun usesAccelerometer(s: AppSettings): Boolean =
        isArmed(s) && (s.panicTriggerShake || s.panicTriggerFaceDown)

    fun usesProximity(s: AppSettings): Boolean =
        isArmed(s) && s.panicTriggerFaceDown
}
