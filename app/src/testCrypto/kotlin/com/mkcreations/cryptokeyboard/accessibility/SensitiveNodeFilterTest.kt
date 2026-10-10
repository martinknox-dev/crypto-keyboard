package com.mkcreations.cryptokeyboard.accessibility

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SensitiveNodeFilterTest : FunSpec({

    test("password-flagged nodes are always skipped") {
        SensitiveNodeFilter.shouldSkip(isPassword = true, className = "android.widget.TextView", hint = null) shouldBe true
    }

    test("password hints are skipped") {
        SensitiveNodeFilter.shouldSkip(false, "android.widget.EditText", "Password") shouldBe true
        SensitiveNodeFilter.shouldSkip(false, "android.widget.EditText", "enter passcode") shouldBe true
        SensitiveNodeFilter.shouldSkip(false, "android.widget.EditText", "PIN") shouldBe true
    }

    test("ordinary nodes are scanned") {
        SensitiveNodeFilter.shouldSkip(false, "android.widget.TextView", null) shouldBe false
        SensitiveNodeFilter.shouldSkip(false, "android.widget.EditText", "Message") shouldBe false
        SensitiveNodeFilter.shouldSkip(false, null, null) shouldBe false
    }
})
