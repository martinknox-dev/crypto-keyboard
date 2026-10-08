package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class DecryptCacheTest : FunSpec({

    beforeTest {
        DecryptCache.clearAll()
        DecryptCache.setCacheDuration(5)
        DecryptCache.setMaxEntries(100)
    }

    afterTest {
        DecryptCache.clearAll()
        DecryptCache.setCacheDuration(5)
        DecryptCache.setMaxEntries(100)
    }

    test("put/get round trip") {
        DecryptCache.put("original message", "secret text", "Alice")
        val cached = DecryptCache.get("original message")
        cached.shouldNotBeNull()
        cached!!.secretMessage shouldBe "secret text"
        cached.keyLabel shouldBe "Alice"
        DecryptCache.isCached("original message").shouldBeTrue()
    }

    test("different messages are cached independently") {
        DecryptCache.put("message A", "secret A", "Alice")
        DecryptCache.put("message B", "secret B", "Bob")
        DecryptCache.get("message A")!!.secretMessage shouldBe "secret A"
        DecryptCache.get("message B")!!.secretMessage shouldBe "secret B"
        DecryptCache.get("message C").shouldBeNull()
    }

    test("entries expire after the configured duration") {
        DecryptCache.setCacheDuration(0) // 0 minutes -> expires immediately
        DecryptCache.put("original", "secret", "Alice")
        Thread.sleep(5) // ensure the clock ticks past the timestamp
        DecryptCache.get("original").shouldBeNull()
        DecryptCache.isCached("original").shouldBeFalse()
    }

    test("cache is bounded: LRU eviction kicks in past max entries") {
        DecryptCache.setMaxEntries(3)
        repeat(5) { i ->
            DecryptCache.put("message $i", "secret $i", "Alice")
        }
        DecryptCache.size() shouldBeExactly 3
        // Oldest entries evicted
        DecryptCache.get("message 0").shouldBeNull()
        DecryptCache.get("message 1").shouldBeNull()
        // Newest entries retained
        DecryptCache.get("message 4").shouldNotBeNull()
        DecryptCache.get("message 3").shouldNotBeNull()
    }

    test("get() refreshes LRU order (accessed entries survive eviction)") {
        DecryptCache.setMaxEntries(2)
        DecryptCache.put("a", "1", "K")
        DecryptCache.put("b", "2", "K")
        DecryptCache.get("a") // touch "a" -> "b" is now oldest
        DecryptCache.put("c", "3", "K")
        DecryptCache.get("a").shouldNotBeNull()
        DecryptCache.get("b").shouldBeNull()
        DecryptCache.get("c").shouldNotBeNull()
    }

    test("clearAll wipes everything") {
        DecryptCache.put("a", "1", "K")
        DecryptCache.put("b", "2", "K")
        DecryptCache.clearAll()
        DecryptCache.size() shouldBeExactly 0
        DecryptCache.get("a").shouldBeNull()
    }

    test("cleanExpired removes only expired entries") {
        DecryptCache.setCacheDuration(0)
        DecryptCache.put("old", "1", "K")
        Thread.sleep(5)
        DecryptCache.setCacheDuration(5)
        DecryptCache.put("fresh", "2", "K")
        DecryptCache.cleanExpired()
        DecryptCache.get("old").shouldBeNull()
        DecryptCache.get("fresh").shouldNotBeNull()
    }
})
