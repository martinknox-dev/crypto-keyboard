package com.mkcreations.cryptokeyboard.ime

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.mkcreations.cryptokeyboard.core.SharedState
import com.mkcreations.cryptokeyboard.crypto.MessageProcessor
import com.mkcreations.cryptokeyboard.model.CoverStyle
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore

/**
 * Handles the encrypt-and-send flow.
 * Called from the crypto bar when user taps "Send Encrypted".
 *
 * This class does NOT touch FlorisBoard internals.
 * It takes a callback to commit text via the IME.
 */
class CryptoInputManager(private val context: Context) {

    companion object {
        private const val TAG = "CryptoInputManager"
    }

    /**
     * Encrypts the secret message, embeds it in the cover, and returns the final text.
     * The caller (keyboard) is responsible for committing it to the input field.
     *
     * @param secretMessage The hidden message
     * @param coverMessage The visible cover text (empty = auto-generate)
     * @return The final message string to commit, or null on failure
     */
    fun encryptMessage(secretMessage: String, coverMessage: String): EncryptionResult {
        if (secretMessage.isBlank()) {
            return EncryptionResult.Error("Secret message is empty")
        }

        val keyStore: SecureKeyStore
        try {
            keyStore = SecureKeyStore.getInstance(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get key store", e)
            return EncryptionResult.Error("Failed to access key store")
        }

        // Get active key
        val activeKeyId = SharedState.activeKeyId ?: keyStore.getActiveKeyId()
        if (activeKeyId == null) {
            return EncryptionResult.Error("No key selected. Go to Settings → Crypto Engine → Manage Keys")
        }

        val key = keyStore.getKey(activeKeyId)
        if (key == null) {
            return EncryptionResult.Error("Selected key not found. It may have been deleted.")
        }

        // Encrypt and embed
        return when (val result = MessageProcessor.encryptAndEmbed(secretMessage, coverMessage, key)) {
            is MessageProcessor.EncryptResult.Success -> {
                // Update last used
                try {
                    keyStore.updateLastUsed(key.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update last used", e)
                }
                SharedState.recordActivity()
                EncryptionResult.Success(
                    finalMessage = result.finalMessage,
                    coverMessage = result.coverMessage,
                    keyLabel = key.label
                )
            }
            is MessageProcessor.EncryptResult.Error -> {
                EncryptionResult.Error(result.message)
            }
        }
    }

    /**
     * Tries to decrypt a message using all available keys.
     */
    fun tryDecrypt(message: String): DecryptionResult {
        val keyStore: SecureKeyStore
        try {
            keyStore = SecureKeyStore.getInstance(context)
        } catch (e: Exception) {
            return DecryptionResult.Error("Failed to access key store")
        }

        return when (val result = MessageProcessor.detectAndDecrypt(message, keyStore)) {
            is MessageProcessor.DecryptResult.Success -> {
                DecryptionResult.Success(
                    secretMessage = result.secretMessage,
                    coverMessage = result.coverMessage,
                    keyLabel = result.keyLabel
                )
            }
            is MessageProcessor.DecryptResult.NotEncrypted -> {
                DecryptionResult.NotEncrypted
            }
            is MessageProcessor.DecryptResult.CorruptedData -> {
                DecryptionResult.Error("Message data is corrupted")
            }
            is MessageProcessor.DecryptResult.NoMatchingKey -> {
                DecryptionResult.Error("No matching key found (fingerprint: ${result.fingerprint})")
            }
            is MessageProcessor.DecryptResult.DecryptionFailed -> {
                DecryptionResult.Error("Decryption failed — wrong key or tampered data")
            }
        }
    }

    /**
     * Gets info about the currently active key
     */
    fun getActiveKeyInfo(): KeyInfo? {
        return try {
            val keyStore = SecureKeyStore.getInstance(context)
            val activeId = SharedState.activeKeyId ?: keyStore.getActiveKeyId() ?: return null
            val key = keyStore.getKey(activeId) ?: return null
            KeyInfo(
                id = key.id,
                label = key.label,
                coverStyle = key.coverStyle
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active key info", e)
            null
        }
    }

    /**
     * Gets list of all available keys for the selector
     */
    fun getAllKeyInfos(): List<KeyInfo> {
        return try {
            val keyStore = SecureKeyStore.getInstance(context)
            keyStore.getAllKeys().map { key ->
                KeyInfo(
                    id = key.id,
                    label = key.label,
                    coverStyle = key.coverStyle
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all key infos", e)
            emptyList()
        }
    }

    /**
     * Sets the active key
     */
    fun setActiveKey(keyId: String) {
        try {
            val keyStore = SecureKeyStore.getInstance(context)
            keyStore.setActiveKeyId(keyId)
            SharedState.activeKeyId = keyId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set active key", e)
        }
    }

    // --- Result Types ---

    sealed class EncryptionResult {
        data class Success(
            val finalMessage: String,
            val coverMessage: String,
            val keyLabel: String
        ) : EncryptionResult()
        data class Error(val message: String) : EncryptionResult()
    }

    sealed class DecryptionResult {
        data class Success(
            val secretMessage: String,
            val coverMessage: String,
            val keyLabel: String
        ) : DecryptionResult()
        object NotEncrypted : DecryptionResult()
        data class Error(val message: String) : DecryptionResult()
    }

    data class KeyInfo(
        val id: String,
        val label: String,
        val coverStyle: CoverStyle
    )
}