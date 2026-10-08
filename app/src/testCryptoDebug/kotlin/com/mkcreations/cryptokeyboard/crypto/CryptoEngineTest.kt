package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoEngineTest : FunSpec({

    test("v2 encrypt/decrypt round trip") {
        val key = KeyGenerator.generateKey()
        val ciphertext = CryptoEngine.encrypt("Meeting at midnight. Bring documents.", key)
        ciphertext.shouldNotBeNull()
        CryptoEngine.decrypt(ciphertext, key) shouldBe "Meeting at midnight. Bring documents."
    }

    test("v2 wire format layout: version + key hint + IV + ciphertext+tag") {
        val key = KeyGenerator.generateKey()
        val plaintext = "hello"
        val ciphertext = CryptoEngine.encrypt(plaintext, key).shouldNotBeNull()
        ciphertext.size shouldBe 1 + 4 + 12 + plaintext.toByteArray(Charsets.UTF_8).size + 16
        ciphertext[0] shouldBe CryptoEngine.VERSION
        // The selector must be the keyed hint, NOT the static fingerprint
        ciphertext.copyOfRange(1, 5).toHex() shouldBe KeyGenerator.keyHint(key).toHex()
        ciphertext.copyOfRange(1, 5).toHex() shouldBe KeyGenerator.keyHintHex(key)
        KeyGenerator.fingerprint(key) shouldNotBe ciphertext.copyOfRange(1, 5).toHex()
    }

    test("random IV: same plaintext produces different ciphertexts") {
        val key = KeyGenerator.generateKey()
        val a = CryptoEngine.encrypt("same message", key).shouldNotBeNull()
        val b = CryptoEngine.encrypt("same message", key).shouldNotBeNull()
        (a.contentEquals(b)) shouldBe false
    }

    test("decrypt with wrong key fails") {
        val key = KeyGenerator.generateKey()
        val ciphertext = CryptoEngine.encrypt("secret", key).shouldNotBeNull()
        CryptoEngine.decrypt(ciphertext, KeyGenerator.generateKey()).shouldBeNull()
    }

    test("tampering with the key hint is detected (AAD)") {
        val key = KeyGenerator.generateKey()
        val ciphertext = CryptoEngine.encrypt("secret", key).shouldNotBeNull()
        val tampered = ciphertext.copyOf()
        tampered[1] = (tampered[1].toInt() xor 0xFF).toByte()
        CryptoEngine.decrypt(tampered, key).shouldBeNull()
    }

    test("tampering with the version byte is detected") {
        val key = KeyGenerator.generateKey()
        val ciphertext = CryptoEngine.encrypt("secret", key).shouldNotBeNull()
        val tampered = ciphertext.copyOf()
        tampered[0] = CryptoEngine.VERSION_V1 // flip v2 -> v1
        CryptoEngine.decrypt(tampered, key).shouldBeNull()
    }

    test("tampering with the ciphertext is detected (GCM tag)") {
        val key = KeyGenerator.generateKey()
        val ciphertext = CryptoEngine.encrypt("secret", key).shouldNotBeNull()
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        CryptoEngine.decrypt(tampered, key).shouldBeNull()
    }

    test("v1 legacy messages (static fingerprint, no AAD) still decrypt") {
        val key = KeyGenerator.generateKey()
        val legacy = encryptV1ForTest("legacy hello", key)
        legacy[0] shouldBe CryptoEngine.VERSION_V1
        CryptoEngine.decrypt(legacy, key) shouldBe "legacy hello"
    }

    test("v1 legacy fingerprint is extractable for key lookup") {
        val key = KeyGenerator.generateKey()
        val legacy = encryptV1ForTest("legacy hello", key)
        CryptoEngine.extractFingerprint(legacy) shouldBe KeyGenerator.fingerprint(key)
    }

    test("unknown version is rejected") {
        val key = KeyGenerator.generateKey()
        val ciphertext = CryptoEngine.encrypt("secret", key).shouldNotBeNull()
        val tampered = ciphertext.copyOf()
        tampered[0] = 99
        CryptoEngine.decrypt(tampered, key).shouldBeNull()
        CryptoEngine.isValidFormat(tampered) shouldBe false
    }

    test("too-short data is rejected") {
        CryptoEngine.decrypt(ByteArray(10), KeyGenerator.generateKey()).shouldBeNull()
        CryptoEngine.isValidFormat(ByteArray(10)) shouldBe false
        CryptoEngine.extractKeySelector(ByteArray(3)).shouldBeNull()
        CryptoEngine.extractVersion(ByteArray(0)) shouldBe -1
    }

    test("extractKeySelector returns hint for v2 and fingerprint for v1") {
        val key = KeyGenerator.generateKey()
        val v2 = CryptoEngine.encrypt("x", key).shouldNotBeNull()
        CryptoEngine.extractKeySelector(v2) shouldBe KeyGenerator.keyHintHex(key)
        CryptoEngine.extractVersion(v2) shouldBe CryptoEngine.VERSION.toInt()

        val v1 = encryptV1ForTest("x", key)
        CryptoEngine.extractKeySelector(v1) shouldBe KeyGenerator.fingerprint(key)
        CryptoEngine.extractVersion(v1) shouldBe CryptoEngine.VERSION_V1.toInt()
    }

    test("empty and unicode plaintext round trip") {
        val key = KeyGenerator.generateKey()
        val plaintext = "🔒 ünïcödé 秘密 🗝️"
        val ciphertext = CryptoEngine.encrypt(plaintext, key).shouldNotBeNull()
        CryptoEngine.decrypt(ciphertext, key) shouldBe plaintext
    }

    test("encrypt returns null-safe on huge input without crashing") {
        val key = KeyGenerator.generateKey()
        val ciphertext = CryptoEngine.encrypt("x".repeat(100_000), key)
        ciphertext.shouldNotBeNull()
        CryptoEngine.decrypt(ciphertext, key) shouldBe "x".repeat(100_000)
    }
})

// --- helpers ---

/** Builds a legacy v1 message exactly like the pre-hardening app did. */
private fun encryptV1ForTest(plaintext: String, keyBytes: ByteArray): ByteArray {
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val fingerprintBytes = hexToBytes(KeyGenerator.fingerprint(keyBytes))
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(keyBytes, "AES"),
        GCMParameterSpec(128, iv)
    )
    val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return byteArrayOf(CryptoEngine.VERSION_V1) + fingerprintBytes + iv + encrypted
}

private fun hexToBytes(hex: String): ByteArray {
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
