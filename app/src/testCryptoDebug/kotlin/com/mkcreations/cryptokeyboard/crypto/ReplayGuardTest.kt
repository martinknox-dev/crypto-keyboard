package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.security.SecureRandom

class ReplayGuardTest : FunSpec({

    val random = SecureRandom()

    beforeTest {
        ReplayGuard.clear()
        ReplayGuard.setMaxEntries(512)
    }

    afterTest {
        ReplayGuard.clear()
        ReplayGuard.setMaxEntries(512)
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    test("first time a ciphertext is seen -> FIRST_SEEN") {
        ReplayGuard.checkAndRecord(randomBytes(48), "cover A") shouldBe ReplayVerdict.FIRST_SEEN
    }

    test("same ciphertext in the same message -> SAME_MESSAGE (not a replay)") {
        val ct = randomBytes(48)
        ReplayGuard.checkAndRecord(ct, "cover A") shouldBe ReplayVerdict.FIRST_SEEN
        ReplayGuard.checkAndRecord(ct, "cover A") shouldBe ReplayVerdict.SAME_MESSAGE
        ReplayGuard.checkAndRecord(ct, "cover A") shouldBe ReplayVerdict.SAME_MESSAGE
    }

    test("same ciphertext in a different message -> REPLAYED") {
        val ct = randomBytes(48)
        ReplayGuard.checkAndRecord(ct, "cover A") shouldBe ReplayVerdict.FIRST_SEEN
        ReplayGuard.checkAndRecord(ct, "cover B") shouldBe ReplayVerdict.REPLAYED
    }

    test("different ciphertexts are tracked independently") {
        ReplayGuard.checkAndRecord(randomBytes(48), "cover A") shouldBe ReplayVerdict.FIRST_SEEN
        ReplayGuard.checkAndRecord(randomBytes(48), "cover A") shouldBe ReplayVerdict.FIRST_SEEN
    }

    test("guard is bounded (LRU)") {
        ReplayGuard.setMaxEntries(4)
        repeat(10) { i ->
            ReplayGuard.checkAndRecord(randomBytes(48), "msg $i")
        }
        ReplayGuard.size() shouldBeExactly 4
    }

    test("clear() resets the guard") {
        val ct = randomBytes(48)
        ReplayGuard.checkAndRecord(ct, "cover A")
        ReplayGuard.clear()
        // After clearing, the same ciphertext is "first seen" again
        ReplayGuard.checkAndRecord(ct, "cover A") shouldBe ReplayVerdict.FIRST_SEEN
    }
})
