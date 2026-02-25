package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

object PinHasher {

    /**
     * Hashes a PIN with a random salt using SHA-256
     * Returns: "salt:hash" format
     */
    fun hashPin(pin: String): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hash = computeHash(pin, salt)
        val hashBase64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        return "$saltBase64:$hashBase64"
    }

    /**
     * Verifies a PIN against a stored hash
     */
    fun verifyPin(pin: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 2) return false
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val expectedHash = Base64.decode(parts[1], Base64.NO_WRAP)
        val actualHash = computeHash(pin, salt)
        return actualHash.contentEquals(expectedHash)
    }

    private fun computeHash(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        // Multiple rounds to slow down brute force
        var hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        for (i in 0 until 10000) {
            digest.reset()
            digest.update(salt)
            hash = digest.digest(hash)
        }
        return hash
    }
}