package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exports and imports keys as password-protected files
 */
object KeyExporter {

    /**
     * OWASP-recommended iteration count for PBKDF2-HMAC-SHA256 (2023).
     * Exports are files, so the user can tolerate the extra delay.
     */
    private const val PBKDF2_ITERATIONS = 600_000

    /** Iteration count used by exports written before the hardening pass. */
    private const val LEGACY_PBKDF2_ITERATIONS = 100_000

    private const val SALT_SIZE = 16
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 12

    // Sanity bounds for iteration counts read from imported files.
    private const val MIN_ITERATIONS = 1
    private const val MAX_ITERATIONS = 10_000_000

    /**
     * Exports a key to an encrypted JSON string
     * Protected with user-provided password
     */
    fun exportKey(key: CipherKey, password: String): String? {
        return try {
            // Generate salt
            val salt = ByteArray(SALT_SIZE)
            SecureRandom().nextBytes(salt)

            // Derive encryption key from password
            val derivedKey = deriveKey(password, salt, PBKDF2_ITERATIONS)

            // Prepare key data as JSON
            val keyData = JSONObject().apply {
                put("id", key.id)
                put("label", key.label)
                put("keyBytes", Base64.getEncoder().encodeToString(key.keyBytes))
                put("createdAt", key.createdAt)
                put("coverStyle", key.coverStyle.name)
            }

            // Encrypt key data
            val iv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(derivedKey, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(keyData.toString().toByteArray(Charsets.UTF_8))

            // Build export JSON
            // "iter" is stored so future format changes stay importable.
            val exportJson = JSONObject().apply {
                put("version", 1)
                put("type", "cipher_key_export")
                put("iter", PBKDF2_ITERATIONS)
                put("salt", Base64.getEncoder().encodeToString(salt))
                put("iv", Base64.getEncoder().encodeToString(iv))
                put("data", Base64.getEncoder().encodeToString(encrypted))
            }

            exportJson.toString(2) // Pretty print

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Imports a key from an encrypted export string
     */
    fun importKey(exportString: String, password: String): CipherKey? {
        return try {
            val exportJson = JSONObject(exportString)

            // Validate
            if (exportJson.optInt("version") != 1) return null
            if (exportJson.optString("type") != "cipher_key_export") return null

            // Extract components
            val salt = Base64.getDecoder().decode(exportJson.getString("salt"))
            val iv = Base64.getDecoder().decode(exportJson.getString("iv"))
            val encrypted = Base64.getDecoder().decode(exportJson.getString("data"))

            // Iteration count is stored per-file; default to the legacy value
            // for exports written before the field existed.
            val iterations = if (exportJson.has("iter")) {
                exportJson.getInt("iter")
            } else {
                LEGACY_PBKDF2_ITERATIONS
            }
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null

            // Derive key from password
            val derivedKey = deriveKey(password, salt, iterations)

            // Decrypt
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(derivedKey, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(encrypted)

            // Parse key data
            val keyData = JSONObject(String(decrypted, Charsets.UTF_8))

            CipherKey(
                id = keyData.getString("id"),
                label = keyData.getString("label"),
                keyBytes = Base64.getDecoder().decode(keyData.getString("keyBytes")),
                createdAt = keyData.getLong("createdAt"),
                lastUsedAt = 0,
                coverStyle = try {
                    CoverStyle.valueOf(keyData.optString("coverStyle", "RANDOM_CASUAL"))
                } catch (e: Exception) {
                    CoverStyle.RANDOM_CASUAL
                },
                compatibleChars = emptyList()
            )

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Derives a 256-bit key from password using PBKDF2
     */
    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            iterations,
            KEY_SIZE
        )
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            // Wipe the password copy held by the key spec
            spec.clearPassword()
        }
    }
}
