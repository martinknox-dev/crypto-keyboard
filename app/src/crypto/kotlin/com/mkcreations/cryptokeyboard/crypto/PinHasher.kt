package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN hashing: PBKDF2-HMAC-SHA256, 600k iterations, 16-byte random salt.
 * Stored format: pbkdf2$600000$<base64 salt>$<base64 hash>
 * Verification compares in constant time via [MessageDigest.isEqual].
 */
object PinHasher {

    private const val ALGO = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun hashPin(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val hashB64 = Base64.getEncoder().encodeToString(hash)
        return listOf("pbkdf2", ITERATIONS.toString(), saltB64, hashB64)
            .joinToString(separator = "$")
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        val parts = storedHash.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations < ITERATIONS) return false
        val salt = try {
            Base64.getDecoder().decode(parts[2])
        } catch (e: IllegalArgumentException) {
            return false
        }
        val expected = try {
            Base64.getDecoder().decode(parts[3])
        } catch (e: IllegalArgumentException) {
            return false
        }
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(ALGO)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
