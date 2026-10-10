package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class PinHasherTest : FunSpec({

    test("hash uses PBKDF2 format with 600k iterations") {
        val prefix = "pbkdf2" + '$' + "600000" + '$'
        PinHasher.hashPin("1234") shouldStartWith prefix
    }

    test("verify accepts correct PIN") {
        PinHasher.verifyPin("4321", PinHasher.hashPin("4321")) shouldBe true
    }

    test("verify rejects wrong PIN") {
        PinHasher.verifyPin("9999", PinHasher.hashPin("4321")) shouldBe false
    }

    test("verify rejects malformed stored value") {
        PinHasher.verifyPin("x", "not-a-hash") shouldBe false
        PinHasher.verifyPin("x", "") shouldBe false
        val badFormat = "pbkdf2" + '$' + "abc" + '$' + "c2FsdA==" + '$' + "aGFzaA=="
        PinHasher.verifyPin("x", badFormat) shouldBe false // iterations not a number
    }

    test("salts are unique per hash") {
        (PinHasher.hashPin("same") != PinHasher.hashPin("same")) shouldBe true
    }
})
