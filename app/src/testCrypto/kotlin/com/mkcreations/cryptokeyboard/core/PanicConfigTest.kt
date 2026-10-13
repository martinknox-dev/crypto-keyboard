package com.mkcreations.cryptokeyboard.core

import com.mkcreations.cryptokeyboard.model.AppSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PanicConfigTest : FunSpec({

    val shakeDisarmed = AppSettings(panicTriggerShake = true) // master off
    val masterNoTriggers = AppSettings(panicEnabled = true, panicTriggerShake = false)
    val armedShake = AppSettings(panicEnabled = true, panicTriggerShake = true)

    test("master off keeps system disarmed even with triggers enabled") {
        PanicConfig.isArmed(shakeDisarmed) shouldBe false
    }

    test("master on with no trigger enabled is disarmed") {
        PanicConfig.isArmed(masterNoTriggers) shouldBe false
    }

    test("master on with at least one trigger is armed") {
        PanicConfig.isArmed(armedShake) shouldBe true
    }

    test("when master off only the master toggle is interactive") {
        val off = AppSettings()
        PanicConfig.isToggleEnabled(off, PanicToggle.MASTER) shouldBe true
        PanicConfig.isToggleEnabled(off, PanicToggle.TRIGGER_SHAKE) shouldBe false
        PanicConfig.isToggleEnabled(off, PanicToggle.TRIGGER_FACE_DOWN) shouldBe false
        PanicConfig.isToggleEnabled(off, PanicToggle.TRIGGER_SPACEBAR) shouldBe false
        PanicConfig.isToggleEnabled(off, PanicToggle.ACTION_SWITCH_IME) shouldBe false
        PanicConfig.isToggleEnabled(off, PanicToggle.ACTION_REMOVE_OVERLAYS) shouldBe false
        PanicConfig.isToggleEnabled(off, PanicToggle.ACTION_CLEAR_CACHE) shouldBe false
        PanicConfig.isToggleEnabled(off, PanicToggle.ACTION_WIPE_KEYS) shouldBe false
    }

    test("all toggles interactive when master on") {
        val on = AppSettings(panicEnabled = true)
        PanicToggle.entries.forEach { toggle ->
            PanicConfig.isToggleEnabled(on, toggle) shouldBe true
        }
    }

    test("accelerometer registers only when armed with shake or face-down") {
        PanicConfig.usesAccelerometer(armedShake) shouldBe true
        PanicConfig.usesAccelerometer(
            AppSettings(panicEnabled = true, panicTriggerShake = false, panicTriggerFaceDown = true)
        ) shouldBe true
        PanicConfig.usesAccelerometer(shakeDisarmed) shouldBe false
        PanicConfig.usesAccelerometer(
            AppSettings(panicEnabled = true, panicTriggerShake = false, panicTriggerSpacebar = true)
        ) shouldBe false
    }

    test("proximity registers only when armed with face-down") {
        PanicConfig.usesProximity(
            AppSettings(panicEnabled = true, panicTriggerShake = false, panicTriggerFaceDown = true)
        ) shouldBe true
        PanicConfig.usesProximity(armedShake) shouldBe false
        PanicConfig.usesProximity(
            AppSettings(panicTriggerFaceDown = true) // master off
        ) shouldBe false
    }
})
