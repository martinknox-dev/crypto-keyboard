package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength

class KeyGeneratorTest : FunSpec({

    test("generateKey produces 32 random bytes (256 bits)") {
        val key = KeyGenerator.generateKey()
        key.size shouldBe 32
        // Two keys must (overwhelmingly) differ
        key.contentEquals(KeyGenerator.generateKey()) shouldBe false
    }

    test("fingerprint is 8 lowercase hex chars and stable") {
        val key = KeyGenerator.generateKey()
        val fp = KeyGenerator.fingerprint(key)
        fp.shouldHaveLength(8)
        fp shouldBe fp.lowercase()
        fp shouldBe KeyGenerator.fingerprint(key)
        // Different keys -> different fingerprints
        KeyGenerator.fingerprint(KeyGenerator.generateKey()) shouldNotBe fp
    }

    test("key hint is 4 bytes, stable, and differs across keys") {
        val key = KeyGenerator.generateKey()
        val hint = KeyGenerator.keyHint(key)
        hint.size shouldBe 4
        hint.contentEquals(KeyGenerator.keyHint(key)).shouldBeTrue()
        KeyGenerator.keyHint(KeyGenerator.generateKey()).contentEquals(hint).shouldBeFalse()
    }

    test("key hint is keyed (not a plain hash of the key)") {
        // If the hint were a plain hash, an observer could precompute it for
        // candidate keys. It must be an HMAC under the key.
        val key = KeyGenerator.generateKey()
        val plainPrefix = java.security.MessageDigest.getInstance("SHA-256").digest(key).copyOf(4)
        KeyGenerator.keyHint(key).contentEquals(plainPrefix).shouldBeFalse()
    }

    test("keyHintHex is 8 lowercase hex chars") {
        KeyGenerator.keyHintHex(KeyGenerator.generateKey()).shouldHaveLength(8)
    }

    test("matchesKeyHint matches own hint and rejects others") {
        val key = KeyGenerator.generateKey()
        val other = KeyGenerator.generateKey()
        KeyGenerator.matchesKeyHint(key, KeyGenerator.keyHintHex(key)).shouldBeTrue()
        KeyGenerator.matchesKeyHint(other, KeyGenerator.keyHintHex(key)).shouldBeFalse()
        // Malformed hint hex is rejected, not crashed on
        KeyGenerator.matchesKeyHint(key, "zzzz").shouldBeFalse()
        KeyGenerator.matchesKeyHint(key, "abcd").shouldBeFalse()
    }

    test("word phrase round trip: 33 words incl. checksum") {
        val key = KeyGenerator.generateKey()
        val phrase = KeyGenerator.keyToWordPhrase(key)
        phrase.size shouldBe KeyGenerator.PHRASE_TOTAL_WORD_COUNT

        val result = KeyGenerator.parseWordPhrase(phrase)
        result.shouldBeValidWith(key)
        KeyGenerator.wordPhraseToKey(phrase)!!.contentEquals(key).shouldBeTrue()
    }

    test("a single mistyped word is detected by the checksum word") {
        val key = KeyGenerator.generateKey()
        val phrase = KeyGenerator.keyToWordPhrase(key).toMutableList()

        // Swap word #6 for a different valid word ("anchor" <-> "apple")
        phrase[5] = if (phrase[5] == "anchor") "apple" else "anchor"

        KeyGenerator.parseWordPhrase(phrase) shouldBe KeyGenerator.PhraseParseResult.InvalidChecksum
        KeyGenerator.wordPhraseToKey(phrase).shouldBeNull()
    }

    test("legacy 32-word phrases are still accepted (no checksum)") {
        val key = KeyGenerator.generateKey()
        val legacy = KeyGenerator.keyToWordPhrase(key).take(KeyGenerator.PHRASE_WORD_COUNT)
        legacy.size shouldBe 32

        val result = KeyGenerator.parseWordPhrase(legacy)
        (result is KeyGenerator.PhraseParseResult.ValidLegacyNoChecksum).shouldBeTrue()
        (result as KeyGenerator.PhraseParseResult.ValidLegacyNoChecksum).keyBytes.contentEquals(key).shouldBeTrue()

        // wordPhraseToKey keeps working for the legacy length too
        KeyGenerator.wordPhraseToKey(legacy)!!.contentEquals(key).shouldBeTrue()
    }

    test("invalid phrases are rejected with precise reasons") {
        KeyGenerator.parseWordPhrase(emptyList()) shouldBe KeyGenerator.PhraseParseResult.InvalidCount
        KeyGenerator.parseWordPhrase(listOf("anchor")) shouldBe KeyGenerator.PhraseParseResult.InvalidCount

        val key = KeyGenerator.generateKey()
        val phrase = KeyGenerator.keyToWordPhrase(key)
        // 34 words -> wrong count
        KeyGenerator.parseWordPhrase(phrase + "anchor") shouldBe KeyGenerator.PhraseParseResult.InvalidCount
        // Unknown word
        val unknownWord = phrase.toMutableList().apply { set(3, "notaword") }
        KeyGenerator.parseWordPhrase(unknownWord) shouldBe KeyGenerator.PhraseParseResult.InvalidWord

        KeyGenerator.wordPhraseToKey(listOf("notaword")).shouldBeNull()
        KeyGenerator.wordPhraseToKey(emptyList()).shouldBeNull()
    }

    test("phrase parsing is case- and whitespace-insensitive") {
        val key = KeyGenerator.generateKey()
        val phrase = KeyGenerator.keyToWordPhrase(key)
        val messy = phrase.map { "  ${it.uppercase()}  " }
        KeyGenerator.parseWordPhrase(messy).shouldBeValidWith(key)
    }

    test("shortPhrase shows the first 8 words") {
        val key = KeyGenerator.generateKey()
        val short = KeyGenerator.shortPhrase(key)
        short.split(" ").size shouldBe 8
        short shouldBe KeyGenerator.keyToWordPhrase(key).take(8).joinToString(" ")
    }
})

private infix fun KeyGenerator.PhraseParseResult?.shouldBeValidWith(key: ByteArray) {
    val result = this
    if (result !is KeyGenerator.PhraseParseResult.Valid) {
        throw AssertionError("Expected Valid, got $result")
    }
    if (!result.keyBytes.contentEquals(key)) {
        throw AssertionError("Parsed key does not match original key")
    }
}
