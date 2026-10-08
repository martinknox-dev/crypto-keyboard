package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * In-memory cache for decrypted messages.
 * Avoids re-decrypting the same message when scrolling.
 * Auto-clears after timeout. Never persisted to disk.
 *
 * The cache holds decrypted plaintext, so it is deliberately bounded (LRU):
 * it never grows past [maxEntries] entries, the oldest entries are evicted
 * first, and everything is wiped on lock/panic/app-switch.
 */
object DecryptCache {

    private const val DEFAULT_MAX_ENTRIES = 100

    private data class CacheEntry(
        val secretMessage: String,
        val keyLabel: String,
        val timestamp: Long
    )

    private var maxEntries = DEFAULT_MAX_ENTRIES
    private var cacheDurationMs: Long = 5 * 60 * 1000L // 5 minutes default

    // accessOrder = true → get() counts as "use", giving us LRU eviction.
    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    /**
     * Sets the maximum number of cached plaintexts (LRU bound).
     */
    fun setMaxEntries(max: Int) {
        maxEntries = max.coerceAtLeast(1)
    }

    /**
     * Sets cache duration in minutes
     */
    fun setCacheDuration(minutes: Int) {
        cacheDurationMs = minutes * 60 * 1000L
    }

    /**
     * Stores a decrypted message in cache
     * Key is a hash of the original encrypted message text
     */
    @Synchronized
    fun put(originalMessage: String, secretMessage: String, keyLabel: String) {
        val hash = hashMessage(originalMessage)
        cache[hash] = CacheEntry(
            secretMessage = secretMessage,
            keyLabel = keyLabel,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Retrieves a cached decryption result
     * Returns null if not cached or expired
     */
    @Synchronized
    fun get(originalMessage: String): CachedResult? {
        val hash = hashMessage(originalMessage)
        val entry = cache[hash] ?: return null

        // Check expiry
        if (System.currentTimeMillis() - entry.timestamp > cacheDurationMs) {
            cache.remove(hash)
            return null
        }

        return CachedResult(
            secretMessage = entry.secretMessage,
            keyLabel = entry.keyLabel
        )
    }

    /**
     * Checks if a message is already cached (avoids re-processing)
     */
    @Synchronized
    fun isCached(originalMessage: String): Boolean {
        return get(originalMessage) != null
    }

    /**
     * Clears all cached data
     * Called on: Exchange Mode off, app switch, screen lock, panic
     */
    @Synchronized
    fun clearAll() {
        cache.clear()
    }

    /**
     * Removes expired entries
     * Called periodically by the accessibility service
     */
    @Synchronized
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        val expired = cache.entries.filter { now - it.value.timestamp > cacheDurationMs }
        expired.forEach { cache.remove(it.key) }
    }

    /**
     * Current cache size (for monitoring)
     */
    @Synchronized
    fun size(): Int = cache.size

    /**
     * Hashes the original message text for use as cache key.
     * Full SHA-256 (not truncated) so cache keys cannot collide across
     * different messages.
     */
    private fun hashMessage(message: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(message.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    data class CachedResult(
        val secretMessage: String,
        val keyLabel: String
    )
}
