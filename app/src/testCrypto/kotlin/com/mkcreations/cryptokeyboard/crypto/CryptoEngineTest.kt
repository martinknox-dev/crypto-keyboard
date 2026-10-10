package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoEngineTest : FunSpec({

    val key = ByteArray(32) { it.toByte() }

    fun legacyEncryptV1(plaintext: String, keyBytes: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val fp = KeyGenerator.fingerprint(keyBytes).chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + 4 + 12 + enc.size)
        out[0] = 1
        fp.copyInto(out, 1)
        iv.copyInto(out, 5)
        enc.copyInto(out, 17)
        return out
    }

    test("v2 roundtrip") {
        val payload = (CryptoEngine.encrypt("hello world", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        payload[0] shouldBe 2
        payload.size shouldBe 13 + "hello world".toByteArray().size + 16
        val out = CryptoEngine.decryptV2(payload, key)
        out.shouldBeInstanceOf<CryptoEngine.DecryptOutcome.Success>()
        out.plaintext shouldBe "hello world"
    }

    test("v2 encrypt rejects wrong key size") {
        CryptoEngine.encrypt("x", ByteArray(16))
            .shouldBeInstanceOf<CryptoEngine.EncryptOutcome.Failure>()
            .reason shouldBe CryptoEngine.EncryptFailure.INVALID_KEY_SIZE
    }

    test("v2 tampered IV is rejected by AAD/tag") {
        val payload = (CryptoEngine.encrypt("secret", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        payload[1] = (payload[1].toInt() xor 0x01).toByte()
        CryptoEngine.decryptV2(payload, key)
            .shouldBeInstanceOf<CryptoEngine.DecryptOutcome.AuthenticationFailed>()
    }

    test("v2 tampered ciphertext is rejected") {
        val payload = (CryptoEngine.encrypt("secret", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        CryptoEngine.decryptV2(payload, key)
            .shouldBeInstanceOf<CryptoEngine.DecryptOutcome.AuthenticationFailed>()
    }

    test("v2 wrong key fails authentication") {
        val other = ByteArray(32) { (it + 1).toByte() }
        val payload = (CryptoEngine.encrypt("secret", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        CryptoEngine.decryptV2(payload, other)
            .shouldBeInstanceOf<CryptoEngine.DecryptOutcome.AuthenticationFailed>()
    }

    test("v1 legacy roundtrip") {
        val payload = legacyEncryptV1("old friend says hi", key)
        CryptoEngine.isValidFormat(payload) shouldBe true
        CryptoEngine.extractFingerprint(payload) shouldBe KeyGenerator.fingerprint(key)
        val out = CryptoEngine.decryptV1(payload, key)
        out.shouldBeInstanceOf<CryptoEngine.DecryptOutcome.Success>()
        out.plaintext shouldBe "old friend says hi"
    }

    test("v1 wrong key fails authentication") {
        val other = ByteArray(32) { (it + 7).toByte() }
        val payload = legacyEncryptV1("old", key)
        CryptoEngine.decryptV1(payload, other)
            .shouldBeInstanceOf<CryptoEngine.DecryptOutcome.AuthenticationFailed>()
    }

    test("garbage is not a valid format") {
        CryptoEngine.isValidFormat(ByteArray(5) { 0x42 }) shouldBe false
        CryptoEngine.isValidFormat(ByteArray(40) { 0x42 }) shouldBe false // version 0x42 unknown
        CryptoEngine.extractFingerprint(ByteArray(40) { 0x42 }) shouldBe null
    }
})
