package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHasher {

    /**
     * OWASP-recommended iteration count for PBKDF2-HMAC-SHA256 (2023).
     * PBKDF2 is not memory-hard like Argon2id, but at 600k iterations it is
     * expensive enough to make offline brute-force of a stolen hash impractical
     * for all but the weakest PINs.
     */
    const val PBKDF2_ITERATIONS = 600_000

    private const val SALT_SIZE = 16
    private const val KEY_SIZE = 256
    private const val FORMAT_VERSION = "v2"

    // Iterations used by the legacy (pre-hardening) "salt:hash" format.
    private const val LEGACY_ITERATIONS = 10_000

    // Sanity bounds for iteration counts read from stored/imported data.
    private const val MIN_ITERATIONS = 1
    private const val MAX_ITERATIONS = 10_000_000

    private val secureRandom = SecureRandom()

    /**
     * Hashes a PIN with a random salt using PBKDF2-HMAC-SHA256.
     *
     * Stored format: "v2:base64(salt):base64(hash):iterations"
     *
     * The previous format ("base64(salt):base64(hash)" with 10k rounds of
     * plain SHA-256) is still verified by [verifyPin] so existing
     * installations keep working; callers should re-hash on next unlock
     * (see [isLegacyFormat]).
     */
    fun hashPin(pin: String): String {
        val salt = ByteArray(SALT_SIZE)
        secureRandom.nextBytes(salt)
        val hash = pbkdf2(pin, salt, PBKDF2_ITERATIONS)
        return "$FORMAT_VERSION:${b64Encode(salt)}:${b64Encode(hash)}:$PBKDF2_ITERATIONS"
    }

    /**
     * Verifies a PIN against a stored hash (current or legacy format).
     * Uses a constant-time comparison. Malformed stored hashes return false
     * instead of throwing.
     */
    fun verifyPin(pin: String, storedHash: String): Boolean {
        return try {
            val parts = storedHash.split(":")
            when (parts.size) {
                4 -> {
                    if (parts[0] != FORMAT_VERSION) return@try false
                    val salt = b64Decode(parts[1])
                    val expected = b64Decode(parts[2])
                    val iterations = parts[3].toIntOrNull() ?: return@try false
                    if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return@try false
                    val actual = pbkdf2(pin, salt, iterations)
                    MessageDigest.isEqual(actual, expected)
                }
                2 -> {
                    // Legacy format — verify with the old weak KDF so existing
                    // installations keep working. The caller should re-hash.
                    val salt = b64Decode(parts[0])
                    val expected = b64Decode(parts[1])
                    val actual = legacySha256(pin, salt)
                    MessageDigest.isEqual(actual, expected)
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True if the stored hash uses the legacy (pre-PBKDF2) format and should
     * be re-hashed with [hashPin] on next successful unlock.
     */
    fun isLegacyFormat(storedHash: String): Boolean {
        return storedHash.split(":").size == 2
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_SIZE)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun legacySha256(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        var hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        for (i in 0 until LEGACY_ITERATIONS) {
            digest.reset()
            digest.update(salt)
            hash = digest.digest(hash)
        }
        return hash
    }

    private fun b64Encode(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun b64Decode(value: String): ByteArray {
        return Base64.getDecoder().decode(value)
    }
}
