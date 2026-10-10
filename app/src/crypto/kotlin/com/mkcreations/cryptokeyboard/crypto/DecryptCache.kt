package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest

/**
 * Bounded, time-expiring store for decrypted messages.
 * Capped at [maxEntries] with least-recently-used eviction; entries
 * auto-expire after [durationMs]. Injectable clock for tests.
 */
class DecryptCacheStore(
    private val maxEntries: Int = 500,
    durationMs: Long = 5 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class Entry(
        val secretMessage: String,
        val keyLabel: String,
        val timestamp: Long
    )

    private var duration: Long = durationMs

    // accessOrder=true gives LRU behavior on get
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun setDuration(minutes: Int) {
        duration = minutes * 60 * 1000L
    }

    @Synchronized
    fun put(originalMessage: String, secretMessage: String, keyLabel: String) {
        cache[hashMessage(originalMessage)] = Entry(
            secretMessage = secretMessage,
            keyLabel = keyLabel,
            timestamp = clock()
        )
        while (cache.size > maxEntries) {
            val eldest = cache.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    @Synchronized
    fun get(originalMessage: String): Entry? {
        val hash = hashMessage(originalMessage)
        val entry = cache[hash] ?: return null
        if (clock() - entry.timestamp > duration) {
            cache.remove(hash)
            return null
        }
        return entry
    }

    @Synchronized
    fun isCached(originalMessage: String): Boolean = get(originalMessage) != null

    @Synchronized
    fun clearAll() {
        cache.clear()
    }

    @Synchronized
    fun cleanExpired() {
        val now = clock()
        val expired = cache.entries.filter { now - it.value.timestamp > duration }
        expired.forEach { cache.remove(it.key) }
    }

    @Synchronized
    fun size(): Int = cache.size

    /**
     * Hashes the original message text for use as cache key.
     * Using SHA-256 so we don't store the original encrypted text as a key.
     */
    private fun hashMessage(message: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(message.toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }
}

/**
 * In-memory cache for decrypted messages.
 * Avoids re-decrypting the same message when scrolling.
 * Auto-clears after timeout. Never persisted to disk.
 * Capped at 500 entries, LRU eviction.
 */
object DecryptCache {

    private val store = DecryptCacheStore(maxEntries = 500)

    /**
     * Sets cache duration in minutes
     */
    fun setCacheDuration(minutes: Int) {
        store.setDuration(minutes)
    }

    /**
     * Stores a decrypted message in cache
     * Key is a hash of the original encrypted message text
     */
    fun put(originalMessage: String, secretMessage: String, keyLabel: String) {
        store.put(originalMessage, secretMessage, keyLabel)
    }

    /**
     * Retrieves a cached decryption result
     * Returns null if not cached or expired
     */
    fun get(originalMessage: String): CachedResult? {
        val entry = store.get(originalMessage) ?: return null
        return CachedResult(
            secretMessage = entry.secretMessage,
            keyLabel = entry.keyLabel
        )
    }

    /**
     * Checks if a message is already cached (avoids re-processing)
     */
    fun isCached(originalMessage: String): Boolean {
        return store.isCached(originalMessage)
    }

    /**
     * Clears all cached data
     * Called on: Exchange Mode off, app switch, screen lock, panic
     */
    fun clearAll() {
        store.clearAll()
    }

    /**
     * Removes expired entries
     * Called periodically by the accessibility service
     */
    fun cleanExpired() {
        store.cleanExpired()
    }

    /**
     * Current cache size (for monitoring)
     */
    fun size(): Int = store.size()

    data class CachedResult(
        val secretMessage: String,
        val keyLabel: String
    )
}
