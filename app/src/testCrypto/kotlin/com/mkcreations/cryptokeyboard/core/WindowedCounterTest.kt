package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WindowedCounterTest : FunSpec({

    test("three presses within 1.5s fire on the third") {
        val counter = WindowedCounter(requiredCount = 3, windowMs = 1500L)
        counter.record(0L) shouldBe false
        counter.record(700L) shouldBe false
        counter.record(1400L) shouldBe true
    }

    test("a press outside the window restarts the count") {
        val counter = WindowedCounter(requiredCount = 3, windowMs = 1500L)
        counter.record(0L) shouldBe false
        counter.record(1600L) shouldBe false   // 0 expired; count restarts
        counter.record(1700L) shouldBe false
        counter.record(1800L) shouldBe true    // 1600/1700/1800 within window
    }

    test("counter resets after firing") {
        val counter = WindowedCounter(requiredCount = 3, windowMs = 1500L)
        counter.record(0L)
        counter.record(700L)
        counter.record(1400L) shouldBe true
        counter.record(1450L) shouldBe false    // sequence was cleared by the fire
    }

    test("two presses never fire") {
        val counter = WindowedCounter(requiredCount = 3, windowMs = 1500L)
        counter.record(0L) shouldBe false
        counter.record(100L) shouldBe false
    }
})
