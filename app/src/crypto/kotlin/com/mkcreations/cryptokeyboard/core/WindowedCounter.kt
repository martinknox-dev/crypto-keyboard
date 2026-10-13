package com.mkcreations.cryptokeyboard.core

/**
 * Counts events that occur close together in time and fires once
 * [requiredCount] of them land inside [windowMs], then resets.
 *
 * Used for the spacebar triple-tap (3 within 0.7 s) panic trigger.
 */
class WindowedCounter(
    private val requiredCount: Int,
    private val windowMs: Long,
) {
    private val times = ArrayDeque<Long>()

    /** Records one press at [nowMs]; true when the burst threshold is hit. */
    fun record(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) {
            times.removeFirst()
        }
        times.addLast(nowMs)
        if (times.size >= requiredCount) {
            times.clear()
            return true
        }
        return false
    }

    fun reset() = times.clear()
}
