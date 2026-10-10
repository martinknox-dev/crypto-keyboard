package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.collections.shouldHaveSize

class KeyGeneratorTest : FunSpec({

    val key = ByteArray(32) { (it * 5).toByte() }

    test("phrase is 33 words and roundtrips") {
        val words = KeyGenerator.keyToWordPhrase(key)
        words shouldHaveSize 33
        KeyGenerator.wordPhraseToKey(words)!!.contentEquals(key) shouldBe true
    }

    test("checksum word matches checksumWord()") {
        val words = KeyGenerator.keyToWordPhrase(key)
        words[32] shouldBe KeyGenerator.checksumWord(key)
    }

    test("single data word typo is detected") {
        val words = KeyGenerator.keyToWordPhrase(key).toMutableList()
        words[0] = if (words[0] == "anchor") "apple" else "anchor"
        KeyGenerator.wordPhraseToKey(words).shouldBeNull()
    }

    test("tampered checksum word is detected") {
        val words = KeyGenerator.keyToWordPhrase(key).toMutableList()
        words[32] = if (words[32] == "anchor") "apple" else "anchor"
        KeyGenerator.wordPhraseToKey(words).shouldBeNull()
    }

    test("legacy 32-word phrase still imports") {
        val words = KeyGenerator.keyToWordPhrase(key).take(32)
        KeyGenerator.wordPhraseToKey(words)!!.contentEquals(key) shouldBe true
    }

    test("wrong length is rejected") {
        KeyGenerator.wordPhraseToKey(KeyGenerator.keyToWordPhrase(key).take(31)).shouldBeNull()
        KeyGenerator.wordPhraseToKey(KeyGenerator.keyToWordPhrase(key) + "anchor" + "apple").shouldBeNull()
    }

    test("unknown word is rejected") {
        val words = KeyGenerator.keyToWordPhrase(key).toMutableList()
        words[5] = "notawordinthelist"
        KeyGenerator.wordPhraseToKey(words).shouldBeNull()
    }
})
