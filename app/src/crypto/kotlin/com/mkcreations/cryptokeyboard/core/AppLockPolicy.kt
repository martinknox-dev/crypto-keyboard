package com.mkcreations.cryptokeyboard.core

/** Pure app-lock decision so it can be unit-tested without Android. */
object AppLockPolicy {
    fun isLocked(
        appLockEnabled: Boolean,
        pinConfigured: Boolean,
        lastUnlockAt: Long,
        timeoutSeconds: Int,
        nowMs: Long
    ): Boolean {
        if (!appLockEnabled || !pinConfigured) return false
        if (timeoutSeconds <= 0) return false // never relock-loop on a zero timeout
        return nowMs - lastUnlockAt > timeoutSeconds * 1000L
    }
}
