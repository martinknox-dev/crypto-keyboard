package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest

/**
 * Best-effort, per-device deduplication of verbatim message re-sends.
 * Bounded LRU with TTL. In-memory only: a restart or cache clear forgets
 * history (documented in SECURITY.md). This is NOT a session ratchet.
 */
class ReplayGuard(
    private val maxEntries: Int = 2_000,
    private val ttlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val seenAt: Long)

    // accessOrder=true gives LRU behavior on get
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun isReplay(payload: ByteArray): Boolean {
        val hash = hash(payload)
        val entry = entries[hash] ?: return false
        if (clock() - entry.seenAt > ttlMs) {
            entries.remove(hash)
            return false
        }
        return true
    }

    @Synchronized
    fun markSeen(payload: ByteArray) {
        val hash = hash(payload)
        entries[hash] = Entry(clock())
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    private fun hash(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }

    companion object {
        val shared = ReplayGuard()
    }
}
