package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PinRateLimiterTest : FunSpec({

    class MemState : PinRateLimiter.State {
        var attemptCount = 0
        var lockout = 0L
        override fun attempts() = attemptCount
        override fun setAttempts(n: Int) { attemptCount = n }
        override fun lockoutUntil() = lockout
        override fun setLockoutUntil(t: Long) { lockout = t }
    }

    test("first four failures do not delay") {
        val state = MemState()
        val limiter = PinRateLimiter(state)
        repeat(4) {
            limiter.recordFailure(now = 0L)
            limiter.retryDelayMs(now = 0L) shouldBe 0L
        }
    }

    test("fifth failure starts 5s backoff") {
        val state = MemState()
        val limiter = PinRateLimiter(state)
        repeat(5) { limiter.recordFailure(now = 1_000L) }
        limiter.retryDelayMs(now = 1_000L) shouldBe 5_000L
        limiter.retryDelayMs(now = 3_000L) shouldBe 3_000L // remaining
        limiter.retryDelayMs(now = 7_000L) shouldBe 0L     // expired
    }

    test("backoff doubles then caps at 5 minutes") {
        val state = MemState()
        val limiter = PinRateLimiter(state)
        repeat(5) { limiter.recordFailure(now = 0L) }  // 5s
        limiter.recordFailure(now = 0L)                 // 6th -> 10s
        limiter.retryDelayMs(now = 0L) shouldBe 10_000L
        repeat(20) { limiter.recordFailure(now = 0L) }  // keep doubling
        limiter.retryDelayMs(now = 0L) shouldBe 300_000L
    }

    test("successful unlock resets backoff") {
        val state = MemState()
        val limiter = PinRateLimiter(state)
        repeat(7) { limiter.recordFailure(now = 0L) }
        limiter.recordSuccess()
        state.attemptCount shouldBe 0
        limiter.retryDelayMs(now = 0L) shouldBe 0L
    }
})
