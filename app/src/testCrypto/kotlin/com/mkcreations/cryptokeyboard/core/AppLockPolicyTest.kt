package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppLockPolicyTest : FunSpec({

    test("never locked when disabled") {
        AppLockPolicy.isLocked(
            appLockEnabled = false, pinConfigured = true,
            lastUnlockAt = 0L, timeoutSeconds = 300, nowMs = 10_000L
        ) shouldBe false
    }

    test("never locked when no PIN configured (prevents lockout)") {
        AppLockPolicy.isLocked(
            appLockEnabled = true, pinConfigured = false,
            lastUnlockAt = 0L, timeoutSeconds = 300, nowMs = 10_000L
        ) shouldBe false
    }

    test("locked after timeout elapsed") {
        AppLockPolicy.isLocked(true, true, lastUnlockAt = 0L, timeoutSeconds = 300, nowMs = 301_000L) shouldBe true
        AppLockPolicy.isLocked(true, true, lastUnlockAt = 0L, timeoutSeconds = 300, nowMs = 299_000L) shouldBe false
    }

    test("recordUnlock clears the lock until timeout passes") {
        AppLockPolicy.isLocked(true, true, lastUnlockAt = 1_000L, timeoutSeconds = 300, nowMs = 1_001L) shouldBe false
    }

    test("zero or negative timeout does not instantly relock") {
        AppLockPolicy.isLocked(true, true, lastUnlockAt = 1_000L, timeoutSeconds = 0, nowMs = 1_001L) shouldBe false
    }
})
