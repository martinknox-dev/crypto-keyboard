package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * End-to-end pipeline test: encrypt -> embed in cover -> extract from
 * message -> key selection by keyed hint -> decrypt -> replay detection.
 *
 * This mirrors MessageProcessor.detectAndDecrypt's algorithm without needing
 * Android's SecureKeyStore (which is not available on the JVM).
 */
class EndToEndPipelineTest : FunSpec({

    data class StoredKey(val id: String, val label: String, val keyBytes: ByteArray)

    beforeTest { ReplayGuard.clear() }
    afterTest { ReplayGuard.clear() }

    fun storedKey(label: String): StoredKey {
        val kb = KeyGenerator.generateKey()
        return StoredKey(KeyGenerator.fingerprint(kb), label, kb)
    }

    /** Mirrors MessageProcessor.detectAndDecrypt for v2 messages. */
    fun detectAndDecrypt(message: String, keys: List<StoredKey>): String? {
        if (!InvisibleEncoder.containsHiddenData(message)) return null
        val encrypted = InvisibleEncoder.extractFromMessage(message) ?: return null
        if (!CryptoEngine.isValidFormat(encrypted)) return null
        val selector = CryptoEngine.extractKeySelector(encrypted) ?: return null
        val key = keys.firstOrNull { KeyGenerator.matchesKeyHint(it.keyBytes, selector) }
            ?: return null
        return CryptoEngine.decrypt(encrypted, key.keyBytes)
    }

    test("full pipeline: secret survives the round trip and the cover stays visible") {
        val alice = storedKey("Alice")
        val secret = "Meet at the old bridge at 23:00. Bring the package."
        val cover = "ok 👍"

        // --- sender side ---
        val encrypted = CryptoEngine.encrypt(secret, alice.keyBytes).shouldNotBeNull()
        val message = InvisibleEncoder.embedInCover(encrypted, cover)

        // The wire message must look like the cover to a casual observer
        InvisibleEncoder.extractCoverMessage(message) shouldBe cover

        // --- receiver side ---
        val decrypted = detectAndDecrypt(message, listOf(alice))
        decrypted shouldBe secret
    }

    test("key selection works with multiple keys (hint matching)") {
        val alice = storedKey("Alice")
        val bob = storedKey("Bob")
        val carol = storedKey("Carol")
        val keys = listOf(alice, bob, carol)

        val encrypted = CryptoEngine.encrypt("for bob only", bob.keyBytes).shouldNotBeNull()
        val message = InvisibleEncoder.embedInCover(encrypted, "hey")

        detectAndDecrypt(message, keys) shouldBe "for bob only"
    }

    test("messages from unknown keys cannot be decrypted") {
        val alice = storedKey("Alice")
        val mallory = storedKey("Mallory")

        val encrypted = CryptoEngine.encrypt("secret", mallory.keyBytes).shouldNotBeNull()
        val message = InvisibleEncoder.embedInCover(encrypted, "hey")

        // Alice does not hold Mallory's key
        detectAndDecrypt(message, listOf(alice)).shouldBeNull()
    }

    test("wire format no longer carries the static key fingerprint") {
        val alice = storedKey("Alice")
        val encrypted = CryptoEngine.encrypt("secret", alice.keyBytes).shouldNotBeNull()
        val selector = CryptoEngine.extractKeySelector(encrypted).shouldNotBeNull()

        // The selector must be the keyed hint, not the static fingerprint —
        // otherwise an observer could cluster all of Alice's messages.
        selector shouldBe KeyGenerator.keyHintHex(alice.keyBytes)
        selector shouldNotBe alice.id
    }

    test("every message from the same key has the same hint but different ciphertext") {
        // Hint is constant per key (needed for key selection)...
        val alice = storedKey("Alice")
        val m1 = CryptoEngine.encrypt("one", alice.keyBytes).shouldNotBeNull()
        val m2 = CryptoEngine.encrypt("two", alice.keyBytes).shouldNotBeNull()
        CryptoEngine.extractKeySelector(m1) shouldBe CryptoEngine.extractKeySelector(m2)
        // ...but the ciphertexts differ (random IV)
        m1.contentEquals(m2).shouldBeFalse()
    }

    test("replay detection: resending an old ciphertext in a new cover is flagged") {
        val alice = storedKey("Alice")
        val encrypted = CryptoEngine.encrypt("attack at dawn", alice.keyBytes).shouldNotBeNull()

        val original = InvisibleEncoder.embedInCover(encrypted, "ok")
        ReplayGuard.checkAndRecord(encrypted, original) shouldBe ReplayVerdict.FIRST_SEEN

        // Same ciphertext re-sent wrapped in a different cover message
        val replayed = InvisibleEncoder.embedInCover(encrypted, "sounds good")
        ReplayGuard.checkAndRecord(encrypted, replayed) shouldBe ReplayVerdict.REPLAYED
    }

    test("re-scanning the same on-screen message is not flagged as replay") {
        val alice = storedKey("Alice")
        val encrypted = CryptoEngine.encrypt("attack at dawn", alice.keyBytes).shouldNotBeNull()

        val message = InvisibleEncoder.embedInCover(encrypted, "ok")
        ReplayGuard.checkAndRecord(encrypted, message) shouldBe ReplayVerdict.FIRST_SEEN
        // Accessibility service re-scans the same bubble after cache expiry
        ReplayGuard.checkAndRecord(encrypted, message) shouldBe ReplayVerdict.SAME_MESSAGE
    }

    test("cover sanitization protects the pipeline when the cover contains stego chars") {
        val alice = storedKey("Alice")
        val secret = "hidden"
        val encrypted = CryptoEngine.encrypt(secret, alice.keyBytes).shouldNotBeNull()

        // Cover contains a zero-width space and a soft hyphen (e.g. pasted text)
        val dirtyCover = "ok" + "\u200B" + "\u00AD" + "!"
        val message = InvisibleEncoder.embedInCover(encrypted, dirtyCover)

        detectAndDecrypt(message, listOf(alice)) shouldBe secret
        InvisibleEncoder.extractCoverMessage(message) shouldBe "ok!"
    }

    test("empty secret message embeds nothing") {
        // encryptAndEmbed rejects blank secrets at the MessageProcessor level;
        // here we assert the building blocks behave sanely for empty input.
        val alice = storedKey("Alice")
        val encrypted = CryptoEngine.encrypt("", alice.keyBytes).shouldNotBeNull()
        val message = InvisibleEncoder.embedInCover(encrypted, "ok")
        detectAndDecrypt(message, listOf(alice)) shouldBe ""
    }
})

private infix fun String?.shouldNotBe(other: String?) {
    if (this == other) throw AssertionError("Expected different values, both were: $this")
}
