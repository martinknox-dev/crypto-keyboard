package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class PinHasherTest : FunSpec({

    test("hashPin/verifyPin round trip") {
        val stored = PinHasher.hashPin("4821")
        PinHasher.verifyPin("4821", stored).shouldBeTrue()
    }

    test("wrong PIN is rejected") {
        val stored = PinHasher.hashPin("4821")
        PinHasher.verifyPin("4822", stored).shouldBeFalse()
        PinHasher.verifyPin("", stored).shouldBeFalse()
        PinHasher.verifyPin("48210", stored).shouldBeFalse()
    }

    test("hash format is versioned and carries the iteration count") {
        val stored = PinHasher.hashPin("1234")
        stored.shouldStartWith("v2:")
        stored.shouldContain(":${PinHasher.PBKDF2_ITERATIONS}")
        PinHasher.isLegacyFormat(stored).shouldBeFalse()
    }

    test("salts are random: same PIN produces different hashes") {
        PinHasher.hashPin("1234") shouldNotBe PinHasher.hashPin("1234")
    }

    test("legacy (pre-hardening) hashes still verify") {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val legacy = legacyHash("1234", salt)
        PinHasher.isLegacyFormat(legacy).shouldBeTrue()
        PinHasher.verifyPin("1234", legacy).shouldBeTrue()
        PinHasher.verifyPin("1235", legacy).shouldBeFalse()
    }

    test("malformed stored hashes are rejected without crashing") {
        PinHasher.verifyPin("1234", "garbage").shouldBeFalse()
        PinHasher.verifyPin("1234", "").shouldBeFalse()
        PinHasher.verifyPin("1234", "v2:AAAA:AAAA").shouldBeFalse()
        PinHasher.verifyPin("1234", "v2:AAAA:AAAA:0").shouldBeFalse()
        PinHasher.verifyPin("1234", "v2:AAAA:AAAA:99999999999").shouldBeFalse()
        PinHasher.verifyPin("1234", "v2:!!!:AAAA:1000").shouldBeFalse()
    }

    test("iteration count is bounded to prevent DoS via crafted hashes") {
        // A stored hash claiming absurd iterations must not hang verification
        val salt = Base64.getEncoder().encodeToString(ByteArray(16))
        val hash = Base64.getEncoder().encodeToString(ByteArray(32))
        PinHasher.verifyPin("1234", "v2:$salt:$hash:10000000").shouldBeFalse() // at cap, but wrong hash
        PinHasher.verifyPin("1234", "v2:$salt:$hash:10000001").shouldBeFalse() // above cap -> rejected early
    }
})

private infix fun String?.shouldNotBe(other: String?) {
    if (this == other) throw AssertionError("Expected different strings, both were: $this")
}

/** Reproduces the legacy pre-hardening hash: salted SHA-256 iterated 10k times. */
private fun legacyHash(pin: String, salt: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(salt)
    var hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
    repeat(10_000) {
        digest.reset()
        digest.update(salt)
        hash = digest.digest(hash)
    }
    return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash)
}
