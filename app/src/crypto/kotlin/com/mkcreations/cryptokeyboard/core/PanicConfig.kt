package com.mkcreations.cryptokeyboard.core

import com.mkcreations.cryptokeyboard.model.AppSettings

/** Every switch shown on the Panic settings screen. */
enum class PanicToggle {
    MASTER,
    TRIGGER_SHAKE,
    TRIGGER_FACE_DOWN,
    TRIGGER_SPACEBAR,
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
            s.panicTriggerSpacebar

    /** The system only fires when the master switch is on AND a trigger is on. */
    fun isArmed(s: AppSettings): Boolean = s.panicEnabled && anyTrigger(s)

    /**
     * While the master switch is off, everything is locked except the master
     * itself — every trigger and every action, including switch-IME, is only
     * editable once panic is armed.
     */
    fun isToggleEnabled(s: AppSettings, toggle: PanicToggle): Boolean = when (toggle) {
        PanicToggle.MASTER -> true
        else -> s.panicEnabled
    }

    fun usesAccelerometer(s: AppSettings): Boolean =
        isArmed(s) && (s.panicTriggerShake || s.panicTriggerFaceDown)

    fun usesProximity(s: AppSettings): Boolean =
        isArmed(s) && s.panicTriggerFaceDown
}
