package com.mkcreations.cryptokeyboard.core

import com.mkcreations.cryptokeyboard.model.AppSettings

/** The panic effects that can run, in execution order. */
enum class PanicAction {
    REMOVE_OVERLAYS, CLEAR_CACHE_AND_LOCK, SWITCH_IME, WIPE_KEYS
}

/**
 * Pure mapping from action toggles to the effects a panic run executes.
 * The arming gate (master switch + at least one trigger) lives in
 * [PanicConfig.isArmed] and is checked by the caller before running the plan.
 */
object PanicActionPlan {
    fun actions(settings: AppSettings): Set<PanicAction> = buildSet {
        if (settings.panicActionRemoveOverlays) add(PanicAction.REMOVE_OVERLAYS)
        if (settings.panicActionClearCache) add(PanicAction.CLEAR_CACHE_AND_LOCK)
        if (settings.panicActionSwitchIme) add(PanicAction.SWITCH_IME)
        if (settings.panicActionWipeKeys) add(PanicAction.WIPE_KEYS)
    }
}
