package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore
import java.util.Base64

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

        // Determine cover message
        val finalCover = if (coverMessage.isNotBlank()) {
            coverMessage
        } else if (key.coverStyle == CoverStyle.NO_COVER) {
            ""
        } else {
            CoverGenerator.generate(key.coverStyle)
        }

        // Sanitize the cover: characters from the stego alphabet or the marker
        // set inside a cover message would corrupt the embedded payload.
        val cleanCover = InvisibleEncoder.sanitizeCover(finalCover)

        // Encrypt
        val encrypted = CryptoEngine.encrypt(secretMessage, key.keyBytes)
            ?: return EncryptResult.Error("Encryption failed")

        // Check encoding stats
        val stats = InvisibleEncoder.getEncodingStats(secretMessage, cleanCover)
        if (!stats.withinLimits) {
            return EncryptResult.Error("Message too long — try shorter text")
        }

        // Embed in cover
        val finalMessage = if (key.coverStyle == CoverStyle.NO_COVER && cleanCover.isEmpty()) {
            // No cover — just base64 the encrypted data
            Base64.getEncoder().encodeToString(encrypted)
        } else {
            InvisibleEncoder.embedInCover(encrypted, cleanCover)
        }

        return EncryptResult.Success(
            finalMessage = finalMessage,
            coverMessage = cleanCover,
            stats = stats
        )
    }

    /**
     * Checks if a message contains hidden data and attempts to decrypt it.
     *
     * Protocol v2 messages carry a keyed per-key hint; we recompute the hint
     * for every stored key and match in constant time. Legacy v1 messages
     * carry the static fingerprint and are matched by key id.
     *
     * @param message The raw message text from the screen
     * @param keyStore The key store to look up keys
     * @return DecryptResult with the secret message, or failure info
     */
    fun detectAndDecrypt(
        message: String,
        keyStore: SecureKeyStore
    ): DecryptResult {

        // Check if message contains invisible data
        if (!InvisibleEncoder.containsHiddenData(message)) {
            return DecryptResult.NotEncrypted
        }

        // Extract encrypted bytes
        val encryptedBytes = InvisibleEncoder.extractFromMessage(message)
            ?: return DecryptResult.CorruptedData

        // Validate format
        if (!CryptoEngine.isValidFormat(encryptedBytes)) {
            return DecryptResult.CorruptedData
        }

        // Get key selector (keyed hint for v2, static fingerprint for v1)
        val selector = CryptoEngine.extractKeySelector(encryptedBytes)
            ?: return DecryptResult.CorruptedData

        val version = CryptoEngine.extractVersion(encryptedBytes)
        val allKeys = keyStore.getAllKeys()

        // Find matching key
        val key = if (version == CryptoEngine.VERSION.toInt()) {
            // v2: selector is a keyed hint — match against our keys
            allKeys.firstOrNull { KeyGenerator.matchesKeyHint(it.keyBytes, selector) }
        } else {
            // v1 legacy: selector is the static fingerprint (stored as key id)
            allKeys.find { it.id == selector }
        } ?: return DecryptResult.NoMatchingKey(selector)

        // Decrypt
        val plaintext = CryptoEngine.decrypt(encryptedBytes, key.keyBytes)
            ?: return DecryptResult.DecryptionFailed

        // Extract cover message for display info
        val coverMessage = InvisibleEncoder.extractCoverMessage(message)

        // Session-scoped replay detection: the same ciphertext appearing in a
        // *different* message is a strong replay signal (GCM alone cannot
        // prevent verbatim re-sends of old ciphertexts).
        val replayVerdict = ReplayGuard.checkAndRecord(encryptedBytes, message)
        if (replayVerdict == ReplayVerdict.REPLAYED) {
            return DecryptResult.Replayed(
                secretMessage = plaintext,
                coverMessage = coverMessage,
                keyLabel = key.label,
                keyId = key.id
            )
        }

        return DecryptResult.Success(
            secretMessage = plaintext,
            coverMessage = coverMessage,
            keyLabel = key.label,
            keyId = key.id
        )
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

        /**
         * Decryption succeeded, but this exact ciphertext was already seen in
         * a different message this session — possible replay attack.
         */
        data class Replayed(
            val secretMessage: String,
            val coverMessage: String,
            val keyLabel: String,
            val keyId: String
        ) : DecryptResult()

        object NotEncrypted : DecryptResult()
        object CorruptedData : DecryptResult()
        data class NoMatchingKey(val fingerprint: String) : DecryptResult()
        object DecryptionFailed : DecryptResult()
    }
}
