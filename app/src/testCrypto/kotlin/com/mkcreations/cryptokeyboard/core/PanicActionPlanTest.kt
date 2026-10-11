package com.mkcreations.cryptokeyboard.core

import com.mkcreations.cryptokeyboard.model.AppSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PanicActionPlanTest : FunSpec({

    test("default settings resolve to overlays, clear-cache+lock, switch-ime; no wipe") {
        PanicActionPlan.actions(AppSettings()) shouldBe setOf(
            PanicAction.REMOVE_OVERLAYS,
            PanicAction.CLEAR_CACHE_AND_LOCK,
            PanicAction.SWITCH_IME,
        )
    }

    test("each action toggle maps independently") {
        PanicActionPlan.actions(
            AppSettings(
                panicActionSwitchIme = false,
                panicActionRemoveOverlays = false,
                panicActionClearCache = false,
                panicActionWipeKeys = true,
            )
        ) shouldBe setOf(PanicAction.WIPE_KEYS)
    }

    test("all action toggles off resolves to an empty plan") {
        PanicActionPlan.actions(
            AppSettings(
                panicActionSwitchIme = false,
                panicActionRemoveOverlays = false,
                panicActionClearCache = false,
                panicActionWipeKeys = false,
            )
        ) shouldBe emptySet()
    }
})
