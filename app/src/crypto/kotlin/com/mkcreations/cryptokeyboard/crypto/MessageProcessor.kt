package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle

/**
 * High-level message processing — the main interface used by
 * the keyboard and accessibility service.
 */
object MessageProcessor {

    /**
     * Encrypts a secret message, embeds it in a cover message,
     * and returns the final text ready to paste into a messenger.
     *
     * @param secretMessage The real message to hide
     * @param coverMessage The visible cover text (can be empty for auto-generate)
     * @param key The cipher key to use
     * @return The combined message with hidden data, or null on failure
     */
    fun encryptAndEmbed(
        secretMessage: String,
        coverMessage: String,
        key: CipherKey
    ): EncryptResult {

        if (secretMessage.isBlank()) {
            return EncryptResult.Error("Secret message is empty")
        }

        // Determine cover message (sanitized so zero-width characters in
        // pasted cover text cannot corrupt payload extraction)
        val finalCover = InvisibleEncoder.sanitizeCover(
            if (coverMessage.isNotBlank()) {
                coverMessage
            } else if (key.coverStyle == CoverStyle.NO_COVER) {
                ""
            } else {
                CoverGenerator.generate(key.coverStyle)
            }
        )

        // Encrypt (protocol v2: AAD-bound header, no fingerprint on the wire)
        val encrypted = when (val out = CryptoEngine.encrypt(secretMessage, key.keyBytes)) {
            is CryptoEngine.EncryptOutcome.Success -> out.payload
            is CryptoEngine.EncryptOutcome.Failure -> return EncryptResult.Error(
                when (out.reason) {
                    CryptoEngine.EncryptFailure.INVALID_KEY_SIZE -> "Key has invalid size"
                    CryptoEngine.EncryptFailure.CIPHER_ERROR -> "Encryption failed"
                }
            )
        }

        // Check encoding stats
        val stats = InvisibleEncoder.getEncodingStats(secretMessage, finalCover)
        if (!stats.withinLimits) {
            return EncryptResult.Error("Message too long — try shorter text")
        }

        // Embed in cover
        val finalMessage = if (key.coverStyle == CoverStyle.NO_COVER && finalCover.isEmpty()) {
            // No cover — just base64 the encrypted data
            java.util.Base64.getEncoder().encodeToString(encrypted)
        } else {
            InvisibleEncoder.embedInCover(encrypted, finalCover)
        }

        return EncryptResult.Success(
            finalMessage = finalMessage,
            coverMessage = finalCover,
            stats = stats
        )
    }

    /**
     * Checks if a message contains hidden data and attempts to decrypt it.
     *
     * @param message The raw message text from the screen
     * @param keys All stored contact keys; v2 payloads are trial-decrypted
     *             against each (only the right key passes GCM verification),
     *             v1 payloads are routed by their embedded fingerprint
     * @return DecryptResult with the secret message, or failure info
     */
    fun detectAndDecrypt(
        message: String,
        keys: List<CipherKey>
    ): DecryptResult {

        // Check if message contains invisible data
        if (!InvisibleEncoder.containsHiddenData(message)) {
            // NO_COVER path: message may be a raw base64 payload
            val raw = try {
                java.util.Base64.getDecoder().decode(message.trim())
            } catch (e: IllegalArgumentException) {
                return DecryptResult.NotEncrypted
            }
            if (!CryptoEngine.isValidFormat(raw)) return DecryptResult.NotEncrypted
            return decryptAndTrack(raw, keys, coverMessage = "")
        }

        // Extract encrypted bytes
        val encryptedBytes = when (val ex = InvisibleEncoder.extractFromMessage(message)) {
            is InvisibleEncoder.ExtractOutcome.Success -> ex.data
            InvisibleEncoder.ExtractOutcome.NotPresent,
            InvisibleEncoder.ExtractOutcome.Malformed -> return DecryptResult.CorruptedData
        }

        // Validate format
        if (!CryptoEngine.isValidFormat(encryptedBytes)) {
            return DecryptResult.CorruptedData
        }

        return decryptAndTrack(encryptedBytes, keys, InvisibleEncoder.extractCoverMessage(message))
    }

    /**
     * Rejects verbatim re-sends before attempting decryption, and records
     * payloads only after they decrypt successfully.
     */
    private fun decryptAndTrack(
        payload: ByteArray,
        keys: List<CipherKey>,
        coverMessage: String
    ): DecryptResult {
        if (ReplayGuard.shared.isReplay(payload)) return DecryptResult.Replayed
        return when (val result = decryptPayload(payload, keys)) {
            is DecryptResult.Success -> {
                ReplayGuard.shared.markSeen(payload)
                result.copy(coverMessage = coverMessage)
            }
            else -> result
        }
    }

    /**
     * Extracts the payload (hidden-data path, or raw base64 for defensive
     * NO_COVER callers — the accessibility scanner only ever hands us
     * hidden-data messages) and checks it against the replay guard without
     * marking it seen. Checked after the decrypt cache so scroll-back
     * re-display keeps working; replays never reach decryption.
     */
    fun isKnownReplay(message: String): Boolean {
        val payload = if (InvisibleEncoder.containsHiddenData(message)) {
            when (val ex = InvisibleEncoder.extractFromMessage(message)) {
                is InvisibleEncoder.ExtractOutcome.Success -> ex.data
                InvisibleEncoder.ExtractOutcome.NotPresent,
                InvisibleEncoder.ExtractOutcome.Malformed -> return false
            }
        } else {
            try {
                java.util.Base64.getDecoder().decode(message.trim())
            } catch (e: IllegalArgumentException) {
                return false
            }
        }
        return ReplayGuard.shared.isReplay(payload)
    }

    /**
     * Decrypts a validated payload: v2 by trial decryption over all keys,
     * v1 by fingerprint lookup. Caller must have checked [CryptoEngine.isValidFormat].
     */
    private fun decryptPayload(payload: ByteArray, keys: List<CipherKey>): DecryptResult {
        return when (payload[0]) {
            CryptoEngine.VERSION_V2 -> {
                for (candidate in keys) {
                    when (val out = CryptoEngine.decryptV2(payload, candidate.keyBytes)) {
                        is CryptoEngine.DecryptOutcome.Success ->
                            return DecryptResult.Success(out.plaintext, "", candidate.label, candidate.id)
                        else -> continue
                    }
                }
                DecryptResult.DecryptionFailed
            }
            CryptoEngine.VERSION_V1 -> {
                val fingerprint = CryptoEngine.extractFingerprint(payload)
                    ?: return DecryptResult.CorruptedData
                val key = keys.find { it.id == fingerprint }
                    ?: return DecryptResult.NoMatchingKey(fingerprint)
                when (val out = CryptoEngine.decryptV1(payload, key.keyBytes)) {
                    is CryptoEngine.DecryptOutcome.Success ->
                        DecryptResult.Success(out.plaintext, "", key.label, key.id)
                    else -> DecryptResult.DecryptionFailed
                }
            }
            else -> DecryptResult.CorruptedData
        }
    }

    // --- Result Types ---

    sealed class EncryptResult {
        data class Success(
            val finalMessage: String,
            val coverMessage: String,
            val stats: InvisibleEncoder.EncodingStats
        ) : EncryptResult()

        data class Error(val message: String) : EncryptResult()
    }

    sealed class DecryptResult {
        data class Success(
            val secretMessage: String,
            val coverMessage: String,
            val keyLabel: String,
            val keyId: String
        ) : DecryptResult()

        object NotEncrypted : DecryptResult()
        object CorruptedData : DecryptResult()
        object Replayed : DecryptResult()
        data class NoMatchingKey(val fingerprint: String) : DecryptResult()
        object DecryptionFailed : DecryptResult()
    }
}
