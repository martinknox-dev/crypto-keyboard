package com.mkcreations.cryptokeyboard.core

import com.mkcreations.cryptokeyboard.model.AppSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PanicSettingsCodecTest : FunSpec({

    test("fresh install keeps AppSettings defaults (master off, shake on)") {
        val s = PanicSettingsCodec.decode(emptyMap(), AppSettings())
        s.panicEnabled shouldBe false
        s.panicTriggerShake shouldBe true
        s.panicTriggerFaceDown shouldBe false
        s.panicTriggerSpacebar shouldBe false
        s.panicTriggerVolumeSequence shouldBe false
        s.panicActionSwitchIme shouldBe true
        s.panicActionRemoveOverlays shouldBe true
        s.panicActionClearCache shouldBe true
        s.panicActionWipeKeys shouldBe false
    }

    test("legacy config migrates triggers and actions") {
        val legacy = mapOf<String, Any?>(
            "panic_enabled" to true,
            "panic_trigger" to "SCREEN_DOWN",
            "panic_switch_keyboard" to false,
            "panic_disable_exchange" to true,
            "panic_remove_overlays" to false,
            "panic_clear_cache" to false,
            "panic_lock_app" to true,
            "panic_clear_keys" to true,
        )
        val s = PanicSettingsCodec.decode(legacy, AppSettings())
        s.panicEnabled shouldBe true
        s.panicTriggerShake shouldBe false
        s.panicTriggerFaceDown shouldBe true
        s.panicTriggerSpacebar shouldBe false
        s.panicTriggerVolumeSequence shouldBe false
        s.panicActionSwitchIme shouldBe false
        s.panicActionRemoveOverlays shouldBe false
        // Combined cache+lock toggle: ON if either legacy toggle wanted the effect.
        s.panicActionClearCache shouldBe true
        s.panicActionWipeKeys shouldBe true
    }

    test("legacy SHAKE trigger maps to shake") {
        val s = PanicSettingsCodec.decode(
            mapOf("panic_enabled" to true, "panic_trigger" to "SHAKE"),
            AppSettings(),
        )
        s.panicTriggerShake shouldBe true
        s.panicTriggerFaceDown shouldBe false
        s.panicEnabled shouldBe true
    }

    test("legacy POWER_BUTTON has no new equivalent trigger") {
        val s = PanicSettingsCodec.decode(
            mapOf("panic_enabled" to true, "panic_trigger" to "POWER_BUTTON"),
            AppSettings(),
        )
        s.panicTriggerShake shouldBe false
        s.panicTriggerFaceDown shouldBe false
        s.panicTriggerSpacebar shouldBe false
        s.panicTriggerVolumeSequence shouldBe false
        s.panicEnabled shouldBe true
    }

    test("new format wins over stale legacy keys") {
        val s = PanicSettingsCodec.decode(
            mapOf(
                "panic_trigger_shake" to false,
                "panic_trigger_spacebar" to true,
                "panic_trigger" to "SHAKE", // stale legacy value must be ignored
            ),
            AppSettings(),
        )
        s.panicTriggerShake shouldBe false
        s.panicTriggerSpacebar shouldBe true
    }

    test("encode round-trips through decode") {
        val original = AppSettings(
            panicEnabled = true,
            panicTriggerShake = false,
            panicTriggerFaceDown = true,
            panicTriggerSpacebar = false,
            panicTriggerVolumeSequence = true,
            panicActionSwitchIme = false,
            panicActionRemoveOverlays = false,
            panicActionClearCache = false,
            panicActionWipeKeys = true,
        )
        val decoded = PanicSettingsCodec.decode(PanicSettingsCodec.encode(original), AppSettings())
        decoded shouldBe original
    }
})
