package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.AppSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CryptoSessionTest : FunSpec({

    test("forgetAll clears both the decrypt cache and the replay guard") {
        CryptoSession.forgetAll() // start from a clean slate
        DecryptCache.put("msg", "secret", "key")
        ReplayGuard.shared.markSeen("msg".toByteArray())

        CryptoSession.forgetAll()

        DecryptCache.get("msg").shouldBeNull()
        ReplayGuard.shared.isReplay("msg".toByteArray()) shouldBe false
    }

    test("forgetAll makes a previously seen message decryptable again") {
        CryptoSession.forgetAll()
        DecryptCache.put("msg", "secret", "key")
        ReplayGuard.shared.markSeen("msg".toByteArray())

        CryptoSession.forgetAll()

        // Both suppression paths are gone: the next scan re-decryptes from scratch.
        DecryptCache.isCached("msg") shouldBe false
        ReplayGuard.shared.isReplay("msg".toByteArray()) shouldBe false
        DecryptCache.size() shouldBe 0
        ReplayGuard.shared.size() shouldBe 0
    }

    test("applySettings drives both stores' expiry from cacheDurationMinutes") {
        try {
            CryptoSession.applySettings(AppSettings(cacheDurationMinutes = 0))
            DecryptCache.put("m", "s", "k")
            ReplayGuard.shared.markSeen("m".toByteArray())
            Thread.sleep(10)
            // ReplayGuard must honour the same zero-minute window as DecryptCache.
            DecryptCache.get("m").shouldBeNull()
            ReplayGuard.shared.isReplay("m".toByteArray()) shouldBe false
        } finally {
            CryptoSession.applySettings(AppSettings()) // restore the 5-minute default
            CryptoSession.forgetAll()
        }
    }
})
