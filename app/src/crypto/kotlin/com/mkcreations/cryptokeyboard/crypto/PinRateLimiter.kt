package com.mkcreations.cryptokeyboard.crypto

/**
 * Exponential backoff for PIN attempts: the first [maxAttempts] failures
 * incur no delay; from then on the delay starts at [baseDelayMs] and doubles
 * per additional failure, capped at [maxDelayMs]. State is external (so it
 * can be persisted) via [State].
 */
class PinRateLimiter(
    private val state: State,
    private val maxAttempts: Int = 5,
    private val baseDelayMs: Long = 5_000,
    private val maxDelayMs: Long = 300_000
) {

    interface State {
        fun attempts(): Int
        fun setAttempts(n: Int)
        fun lockoutUntil(): Long
        fun setLockoutUntil(t: Long)
    }

    /** Remaining lockout in ms; 0 means attempts are allowed now. */
    fun retryDelayMs(now: Long): Long {
        if (state.attempts() < maxAttempts) return 0L
        return (state.lockoutUntil() - now).coerceAtLeast(0L)
    }

    fun recordFailure(now: Long) {
        val n = state.attempts() + 1
        state.setAttempts(n)
        if (n >= maxAttempts) {
            val exponent = (n - maxAttempts).coerceAtMost(30)
            val delay = (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
            state.setLockoutUntil(now + delay)
        }
    }

    fun recordSuccess() {
        state.setAttempts(0)
        state.setLockoutUntil(0L)
    }
}
