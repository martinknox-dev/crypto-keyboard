package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.util.LinkedHashMap

enum class ReplayVerdict {
    /** Ciphertext seen for the first time. */
    FIRST_SEEN,

    /** Same ciphertext inside the same message — e.g. the same on-screen
     *  bubble re-scanned after a cache expiry. Not a replay. */
    SAME_MESSAGE,

    /** Same ciphertext inside a *different* message (different cover text or
     *  surroundings) — a strong replay signal. */
    REPLAYED
}

/**
 * Session-scoped replay detection.
 *
 * AES-GCM authenticates integrity but cannot stop an attacker from
 * re-sending an old ciphertext verbatim (a "replay"). This guard remembers
 * recently seen ciphertexts and flags when the *same ciphertext* appears
 * inside a *different* message — which is what an attacker re-sending an
 * old payload looks like. The same ciphertext re-scanned within the same
 * message (e.g. the same on-screen bubble) is NOT flagged.
 *
 * Honest limitation: this is a best-effort heuristic, not cryptographic
 * replay protection. True replay resistance would require sequence numbers
 * and receiver-side state in the authenticated plaintext.
 *
 * The guard is a bounded LRU — it never grows past [maxEntries] and is
 * never persisted to disk.
 */
object ReplayGuard {

    private const val DEFAULT_MAX_ENTRIES = 512

    private var maxEntries = DEFAULT_MAX_ENTRIES

    private data class Entry(
        val messageHash: String,
        val timestamp: Long
    )

    // accessOrder = true → get() counts as "use", giving us LRU eviction.
    private val seen = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > maxEntries
        }
    }

    fun setMaxEntries(max: Int) {
        maxEntries = max.coerceAtLeast(1)
    }

    /**
     * Records a ciphertext and reports whether it looks like a replay.
     *
     * @param encryptedData The raw encrypted bytes extracted from the message.
     * @param fullMessage   The full on-screen/clipboard message text, used to
     *                      distinguish "same bubble re-scanned" from "same
     *                      ciphertext re-sent in a new message".
     */
    @Synchronized
    fun checkAndRecord(encryptedData: ByteArray, fullMessage: String): ReplayVerdict {
        val ciphertextHash = sha256Hex(encryptedData)
        val messageHash = sha256Hex(fullMessage.toByteArray(Charsets.UTF_8))

        val existing = seen[ciphertextHash]
        return if (existing == null) {
            seen[ciphertextHash] = Entry(messageHash, System.currentTimeMillis())
            ReplayVerdict.FIRST_SEEN
        } else if (existing.messageHash == messageHash) {
            ReplayVerdict.SAME_MESSAGE
        } else {
            // Same ciphertext, different surrounding message → replay signal.
            // Update the entry so a third occurrence compares against the
            // most recent context.
            seen[ciphertextHash] = Entry(messageHash, System.currentTimeMillis())
            ReplayVerdict.REPLAYED
        }
    }

    @Synchronized
    fun clear() {
        seen.clear()
    }

    @Synchronized
    fun size(): Int = seen.size

    private fun sha256Hex(data: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
