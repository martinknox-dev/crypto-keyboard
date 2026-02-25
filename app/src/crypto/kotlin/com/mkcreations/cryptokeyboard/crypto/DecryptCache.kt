package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for decrypted messages.
 * Avoids re-decrypting the same message when scrolling.
 * Auto-clears after timeout. Never persisted to disk.
 */
object DecryptCache {

    private data class CacheEntry(
        val secretMessage: String,
        val keyLabel: String,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private var cacheDurationMs: Long = 5 * 60 * 1000L // 5 minutes default

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
    fun isCached(originalMessage: String): Boolean {
        return get(originalMessage) != null
    }

    /**
     * Clears all cached data
     * Called on: Exchange Mode off, app switch, screen lock, panic
     */
    fun clearAll() {
        cache.clear()
    }

    /**
     * Removes expired entries
     * Called periodically by the accessibility service
     */
    fun cleanExpired() {
        val now = System.currentTimeMillis()
        val expired = cache.entries.filter { now - it.value.timestamp > cacheDurationMs }
        expired.forEach { cache.remove(it.key) }
    }

    /**
     * Current cache size (for monitoring)
     */
    fun size(): Int = cache.size

    /**
     * Hashes the original message text for use as cache key
     * Using SHA-256 so we don't store the original encrypted text as a key
     */
    private fun hashMessage(message: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(message.toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    data class CachedResult(
        val secretMessage: String,
        val keyLabel: String
    )
}