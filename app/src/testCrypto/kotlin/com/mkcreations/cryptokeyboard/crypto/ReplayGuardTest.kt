package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReplayGuardTest : FunSpec({

    test("first sight is not a replay, second is") {
        val guard = ReplayGuard()
        val payload = "payload-bytes".toByteArray()
        guard.isReplay(payload) shouldBe false
        guard.markSeen(payload)
        guard.isReplay(payload) shouldBe true
    }

    test("different payloads are independent") {
        val guard = ReplayGuard()
        guard.markSeen("a".toByteArray())
        guard.isReplay("b".toByteArray()) shouldBe false
    }

    test("LRU cap evicts least recently used beyond maxEntries") {
        val guard = ReplayGuard(maxEntries = 3)
        guard.markSeen("1".toByteArray())
        guard.markSeen("2".toByteArray())
        guard.markSeen("3".toByteArray())
        guard.markSeen("4".toByteArray()) // evicts "1"
        guard.isReplay("1".toByteArray()) shouldBe false
        guard.isReplay("4".toByteArray()) shouldBe true
    }

    test("entries expire after TTL") {
        var now = 0L
        val guard = ReplayGuard(ttlMs = 1_000L, clock = { now })
        guard.markSeen("old".toByteArray())
        now = 999L
        guard.isReplay("old".toByteArray()) shouldBe true
        now = 1_001L
        guard.isReplay("old".toByteArray()) shouldBe false
    }

    test("setTtlDuration adjusts the expiry window") {
        var now = 0L
        val guard = ReplayGuard(ttlMs = 30L * 24 * 60 * 60 * 1000, clock = { now })
        guard.markSeen("x".toByteArray())
        guard.setTtlDuration(minutes = 5)
        now = 5 * 60 * 1000L // exactly at the new boundary: still a replay
        guard.isReplay("x".toByteArray()) shouldBe true
        now = 5 * 60 * 1000L + 1 // one millisecond past: entry expired
        guard.isReplay("x".toByteArray()) shouldBe false
    }

    test("clear resets state") {
        val guard = ReplayGuard()
        guard.markSeen("x".toByteArray())
        guard.clear()
        guard.isReplay("x".toByteArray()) shouldBe false
    }
})
