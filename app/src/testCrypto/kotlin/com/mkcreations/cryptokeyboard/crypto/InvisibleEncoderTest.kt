package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class InvisibleEncoderTest : FunSpec({

    test("sanitizeCover strips every encoding and marker character") {
        val dirty = "ok\u200B\u200C\u200D\u00AD\u2060\u2068\u2069\uFEFF done"
        InvisibleEncoder.sanitizeCover(dirty) shouldBe "ok done"
    }

    test("sanitizeCover preserves ordinary text and emoji") {
        val cover = "sounds good \uD83D\uDC4D — café 100%"
        InvisibleEncoder.sanitizeCover(cover) shouldBe cover
    }

    test("embedded payload with dirty cover still decrypts") {
        val key = ByteArray(32) { it.toByte() }
        val payload = (CryptoEngine.encrypt("secret", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        val dirtyCover = "ok\u200B please"
        val message = InvisibleEncoder.embedInCover(payload, dirtyCover)
        // Sanitization happened at embed time: extract must reproduce bytes exactly
        val extracted = InvisibleEncoder.extractFromMessage(message)
        extracted.shouldBeInstanceOf<InvisibleEncoder.ExtractOutcome.Success>()
        extracted.data.contentEquals(payload) shouldBe true
        InvisibleEncoder.extractCoverMessage(message) shouldBe "ok please"
    }

    test("message without signature yields NotPresent") {
        InvisibleEncoder.extractFromMessage("hello world")
            .shouldBeInstanceOf<InvisibleEncoder.ExtractOutcome.NotPresent>()
    }

    test("odd number of invisible chars yields Malformed") {
        val sig = "\u2069\u2068\u2069\u2068"
        val end = "\u2068\u2069\u2068\u2069"
        InvisibleEncoder.extractFromMessage("a$sig\u200B\u200C\u200D$end")
            .shouldBeInstanceOf<InvisibleEncoder.ExtractOutcome.Malformed>()
    }

    test("stats use the v2 header size") {
        val stats = InvisibleEncoder.getEncodingStats("ab", "ok")
        stats.estimatedEncryptedBytes shouldBe 1 + 12 + 2 + 16
    }
})
