package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FaceDownDetectorTest : FunSpec({

    test("tilt into face-down while proximity is covered fires") {
        val d = FaceDownDetector()
        d.onProximityNear(nearNow = true, nowMs = 0L) shouldBe false   // not tilted yet
        d.onTilt(faceDownNow = true, nowMs = 100L) shouldBe true
    }

    test("proximity cover while already tilted fires (order independent)") {
        val d = FaceDownDetector()
        d.onTilt(faceDownNow = true, nowMs = 0L) shouldBe false        // not covered yet
        d.onProximityNear(nearNow = true, nowMs = 100L) shouldBe true
    }

    test("tilt alone does not fire") {
        val d = FaceDownDetector()
        d.onTilt(faceDownNow = true, nowMs = 0L) shouldBe false
        d.onTilt(faceDownNow = false, nowMs = 500L) shouldBe false
    }

    test("proximity alone does not fire") {
        val d = FaceDownDetector()
        d.onProximityNear(nearNow = true, nowMs = 0L) shouldBe false
        d.onProximityNear(nearNow = false, nowMs = 500L) shouldBe false
    }

    test("does not re-fire from sensor flicker during cooldown") {
        val d = FaceDownDetector()
        d.onProximityNear(true, 0L)
        d.onTilt(true, 100L) shouldBe true
        // Proximity flickers far/near while pressed against a surface
        d.onProximityNear(false, 200L) shouldBe false
        d.onProximityNear(true, 210L) shouldBe false                   // within cooldown
    }

    test("re-fires after full release and cooldown") {
        val d = FaceDownDetector()
        d.onProximityNear(true, 0L)
        d.onTilt(true, 100L) shouldBe true
        // Release both, then flip again well after the cooldown
        d.onTilt(false, 1_000L)
        d.onProximityNear(false, 1_000L)
        d.onProximityNear(true, 6_000L) shouldBe false                 // still not tilted
        d.onTilt(true, 6_010L) shouldBe true
    }

    test("late proximity flicker after cooldown cannot re-fire without full release") {
        val d = FaceDownDetector()
        d.onProximityNear(true, 0L)
        d.onTilt(true, 100L) shouldBe true
        // Device lies still face-down; proximity flickers again long after cooldown.
        d.onProximityNear(false, 10_000L) shouldBe false
        d.onProximityNear(true, 10_010L) shouldBe false                // must NOT fire again
        // Only a full release (both states cleared) re-arms the detector.
        d.onTilt(false, 10_020L) shouldBe false
        d.onProximityNear(false, 10_030L) shouldBe false
        d.onProximityNear(true, 16_000L) shouldBe false                // not tilted yet
        d.onTilt(true, 16_010L) shouldBe true                          // deliberate flip fires
    }
})
