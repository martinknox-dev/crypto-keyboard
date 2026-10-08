package com.mkcreations.cryptokeyboard.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12        // 96 bits - standard for GCM

    /** Legacy format: static key fingerprint in the clear, no AAD. */
    const val VERSION_V1: Byte = 1

    /**
     * Current format: keyed per-key hint (HMAC-based, so messages cannot be
     * correlated by a constant identifier) and the header (version + hint) is
     * bound into the GCM authentication tag as AAD, so header tampering is
     * detected on decryption.
     */
    const val VERSION: Byte = 2

    private const val HEADER_SIZE = 1 + 4 + IV_SIZE // version + selector + IV
    private const val TAG_SIZE = 16                  // GCM authentication tag

    private val secureRandom = SecureRandom()

    /**
     * Encrypts plaintext using AES-256-GCM (protocol v2).
     *
     * Output format:
     * [1 byte version][4 bytes key hint][12 bytes IV][N bytes ciphertext + GCM tag]
     *
     * The key hint is NOT the static key fingerprint — it is a keyed HMAC
     * ([KeyGenerator.keyHint]) that only the holder of the key can recompute,
     * so observers cannot cluster messages by a constant per-key identifier.
     * The version + hint bytes are passed as GCM AAD: they are authenticated
     * even though they are not encrypted.
     */
    fun encrypt(plaintext: String, keyBytes: ByteArray): ByteArray? {
        return try {
            val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)

            // Generate random IV
            val iv = ByteArray(IV_SIZE)
            secureRandom.nextBytes(iv)

            // Keyed per-key hint (v2) — replaces the static fingerprint on the wire
            val hint = KeyGenerator.keyHint(keyBytes)

            // Encrypt
            val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            // AAD binds the header into the authentication tag
            cipher.updateAAD(byteArrayOf(VERSION) + hint)
            val encrypted = cipher.doFinal(plaintextBytes)

            // Assemble: version + hint + IV + encrypted(ciphertext + tag)
            val result = ByteArray(HEADER_SIZE + encrypted.size)
            result[0] = VERSION
            System.arraycopy(hint, 0, result, 1, 4)
            System.arraycopy(iv, 0, result, 5, IV_SIZE)
            System.arraycopy(encrypted, 0, result, 5 + IV_SIZE, encrypted.size)

            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decrypts ciphertext using AES-256-GCM.
     *
     * Supports both protocol versions:
     * - v2 (current): verifies the AAD-bound header, then decrypts.
     * - v1 (legacy): static fingerprint header, no AAD. Kept so messages sent
     *   by older versions remain readable.
     *
     * Returns plaintext string or null if decryption fails.
     * Fails silently if wrong key, tampered data, or corrupted.
     */
    fun decrypt(data: ByteArray, keyBytes: ByteArray): String? {
        return try {
            if (data.size < HEADER_SIZE + TAG_SIZE) return null // Minimum: header + GCM tag

            // Read version
            val version = data[0]
            if (version != VERSION && version != VERSION_V1) return null

            // Read IV
            val iv = ByteArray(IV_SIZE)
            System.arraycopy(data, 5, iv, 0, IV_SIZE)

            // Read encrypted data (ciphertext + GCM tag)
            val encryptedSize = data.size - HEADER_SIZE
            val encrypted = ByteArray(encryptedSize)
            System.arraycopy(data, 5 + IV_SIZE, encrypted, 0, encryptedSize)

            // Decrypt
            val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            if (version == VERSION) {
                // v2: the header is authenticated — any tampering with the
                // version or key hint makes doFinal() throw AEADBadTagException.
                cipher.updateAAD(byteArrayOf(VERSION) + data.copyOfRange(1, 5))
            }
            val decrypted = cipher.doFinal(encrypted)

            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            // Decryption failed — wrong key, tampered data, or corrupted
            null
        }
    }

    /**
     * Returns the protocol version byte of a message, or -1 if empty.
     */
    fun extractVersion(data: ByteArray): Int {
        if (data.isEmpty()) return -1
        return data[0].toInt() and 0xFF
    }

    /**
     * Extracts the 4-byte key selector from encrypted data (hex encoded).
     *
     * For v2 messages this is the keyed key hint; for v1 messages it is the
     * static key fingerprint. The receiver uses it to pick which key to try.
     */
    fun extractKeySelector(data: ByteArray): String? {
        if (data.size < 5) return null
        return bytesToHex(data.copyOfRange(1, 5))
    }

    /**
     * Extracts the key fingerprint from a legacy (v1) encrypted message.
     * Used to determine which key to try for decryption.
     */
    fun extractFingerprint(data: ByteArray): String? {
        if (data.size < 5) return null
        if (data[0] != VERSION_V1) return null
        return bytesToHex(data.copyOfRange(1, 5))
    }

    /**
     * Validates that data looks like our encrypted format (v1 or v2).
     */
    fun isValidFormat(data: ByteArray): Boolean {
        if (data.size < HEADER_SIZE + TAG_SIZE) return false
        return data[0] == VERSION || data[0] == VERSION_V1
    }

    // --- Hex utilities ---

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
