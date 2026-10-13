package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.mkcreations.cryptokeyboard.core.DeviceTier.Tier

class DeviceTierTest : FunSpec({

    val GIB = 1024L * 1024 * 1024

    test("isLowRamDevice always classifies LOW regardless of RAM") {
        DeviceTier.classify(isLowRamDevice = true, totalMemBytes = 8 * GIB) shouldBe Tier.LOW
    }

    test("RAM under 4 GiB classifies LOW") {
        DeviceTier.classify(isLowRamDevice = false, totalMemBytes = 3 * GIB) shouldBe Tier.LOW
    }

    test("RAM at exactly 4 GiB classifies HIGH (threshold is exclusive)") {
        DeviceTier.classify(isLowRamDevice = false, totalMemBytes = 4 * GIB) shouldBe Tier.HIGH
    }

    test("RAM over 4 GiB classifies HIGH") {
        DeviceTier.classify(isLowRamDevice = false, totalMemBytes = 8 * GIB) shouldBe Tier.HIGH
    }

    test("LOW debounce is longer than HIGH debounce (fewer scans)") {
        DeviceTier.debounceMs(Tier.LOW) shouldBe 800L
        DeviceTier.debounceMs(Tier.HIGH) shouldBe 400L
    }

    test("LOW scan depth and node caps are lower than HIGH") {
        (DeviceTier.maxScanDepth(Tier.LOW) < DeviceTier.maxScanDepth(Tier.HIGH)) shouldBe true
        (DeviceTier.maxNodes(Tier.LOW) < DeviceTier.maxNodes(Tier.HIGH)) shouldBe true
        (DeviceTier.maxQuickCheckDepth(Tier.LOW) < DeviceTier.maxQuickCheckDepth(Tier.HIGH)) shouldBe true
    }

    test("HIGH caps match the current hardcoded defaults") {
        DeviceTier.maxScanDepth(Tier.HIGH) shouldBe 20
        DeviceTier.maxNodes(Tier.HIGH) shouldBe 15
        DeviceTier.maxQuickCheckDepth(Tier.HIGH) shouldBe 10
    }
})
