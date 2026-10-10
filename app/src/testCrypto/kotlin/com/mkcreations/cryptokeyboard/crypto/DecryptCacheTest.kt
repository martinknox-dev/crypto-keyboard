package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class DecryptCacheTest : FunSpec({

    test("entries beyond cap evict the least recently used") {
        val store = DecryptCacheStore(maxEntries = 2)
        store.put("m1", "s1", "k")
        store.put("m2", "s2", "k")
        store.get("m1") // access m1 so m2 becomes LRU
        store.put("m3", "s3", "k")
        store.get("m2").shouldBeNull()
        store.get("m1")!!.secretMessage shouldBe "s1"
        store.get("m3")!!.secretMessage shouldBe "s3"
    }

    test("entries expire after duration") {
        var now = 0L
        val store = DecryptCacheStore(maxEntries = 10, durationMs = 1_000L, clock = { now })
        store.put("m", "s", "k")
        now = 999L
        store.get("m")!!.secretMessage shouldBe "s"
        now = 1_001L
        store.get("m").shouldBeNull()
    }

    test("cleanExpired removes stale entries only") {
        var now = 0L
        val store = DecryptCacheStore(maxEntries = 10, durationMs = 1_000L, clock = { now })
        store.put("old", "a", "k")
        now = 2_000L
        store.put("new", "b", "k")
        store.cleanExpired()
        store.size() shouldBe 1
        store.get("new")!!.secretMessage shouldBe "b"
    }

    test("clearAll empties the cache") {
        val store = DecryptCacheStore()
        store.put("m", "s", "k")
        store.clearAll()
        store.size() shouldBe 0
    }
})
