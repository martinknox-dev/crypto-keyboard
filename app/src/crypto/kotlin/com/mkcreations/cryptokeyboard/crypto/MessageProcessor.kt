package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore

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

        // Encrypt
        val encrypted = CryptoEngine.encrypt(secretMessage, key.keyBytes)
            ?: return EncryptResult.Error("Encryption failed")

        // Check encoding stats
        val stats = InvisibleEncoder.getEncodingStats(secretMessage, finalCover)
        if (!stats.withinLimits) {
            return EncryptResult.Error("Message too long — try shorter text")
        }

        // Embed in cover
        val finalMessage = if (key.coverStyle == CoverStyle.NO_COVER && finalCover.isEmpty()) {
            // No cover — just base64 the encrypted data
            android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
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

        // Get key fingerprint
        val fingerprint = CryptoEngine.extractFingerprint(encryptedBytes)
            ?: return DecryptResult.CorruptedData

        // Find matching key
        val key = keyStore.getKey(fingerprint)
            ?: return DecryptResult.NoMatchingKey(fingerprint)

        // Decrypt
        val plaintext = CryptoEngine.decrypt(encryptedBytes, key.keyBytes)
            ?: return DecryptResult.DecryptionFailed

        // Extract cover message for display info
        val coverMessage = InvisibleEncoder.extractCoverMessage(message)

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

        object NotEncrypted : DecryptResult()
        object CorruptedData : DecryptResult()
        data class NoMatchingKey(val fingerprint: String) : DecryptResult()
        object DecryptionFailed : DecryptResult()
    }
}