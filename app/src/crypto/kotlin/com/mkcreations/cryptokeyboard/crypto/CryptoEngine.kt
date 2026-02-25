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
    private const val VERSION: Byte = 1   // Protocol version

    private val secureRandom = SecureRandom()

    /**
     * Encrypts plaintext using AES-256-GCM
     *
     * Output format:
     * [1 byte version][4 bytes key fingerprint][12 bytes IV][N bytes ciphertext + GCM tag]
     *
     * The key fingerprint is NOT encrypted — it tells the receiver which key to use
     * Everything else is authenticated and encrypted
     */
    fun encrypt(plaintext: String, keyBytes: ByteArray): ByteArray? {
        return try {
            val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)

            // Generate random IV
            val iv = ByteArray(IV_SIZE)
            secureRandom.nextBytes(iv)

            // Get key fingerprint (first 4 bytes of SHA-256 hash)
            val fingerprint = KeyGenerator.fingerprint(keyBytes)
            val fingerprintBytes = hexToBytes(fingerprint)

            // Encrypt
            val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            val encrypted = cipher.doFinal(plaintextBytes)

            // Assemble: version + fingerprint + IV + encrypted(ciphertext + tag)
            val result = ByteArray(1 + 4 + IV_SIZE + encrypted.size)
            result[0] = VERSION
            System.arraycopy(fingerprintBytes, 0, result, 1, 4)
            System.arraycopy(iv, 0, result, 5, IV_SIZE)
            System.arraycopy(encrypted, 0, result, 5 + IV_SIZE, encrypted.size)

            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decrypts ciphertext using AES-256-GCM
     *
     * Returns plaintext string or null if decryption fails
     * Fails silently if wrong key, tampered data, or corrupted
     */
    fun decrypt(data: ByteArray, keyBytes: ByteArray): String? {
        return try {
            if (data.size < 1 + 4 + IV_SIZE + 16) return null // Minimum: header + at least GCM tag

            // Read version
            val version = data[0]
            if (version != VERSION) return null

            // Read IV
            val iv = ByteArray(IV_SIZE)
            System.arraycopy(data, 5, iv, 0, IV_SIZE)

            // Read encrypted data (ciphertext + GCM tag)
            val encryptedSize = data.size - 1 - 4 - IV_SIZE
            val encrypted = ByteArray(encryptedSize)
            System.arraycopy(data, 5 + IV_SIZE, encrypted, 0, encryptedSize)

            // Decrypt
            val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            val decrypted = cipher.doFinal(encrypted)

            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            // Decryption failed — wrong key, tampered data, or corrupted
            null
        }
    }

    /**
     * Extracts the key fingerprint from encrypted data
     * Used to determine which key to try for decryption
     */
    fun extractFingerprint(data: ByteArray): String? {
        if (data.size < 5) return null
        if (data[0] != VERSION) return null
        val fingerprintBytes = ByteArray(4)
        System.arraycopy(data, 1, fingerprintBytes, 0, 4)
        return bytesToHex(fingerprintBytes)
    }

    /**
     * Validates that data looks like our encrypted format
     */
    fun isValidFormat(data: ByteArray): Boolean {
        if (data.size < 1 + 4 + IV_SIZE + 16) return false
        return data[0] == VERSION
    }

    // --- Hex utilities ---

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}