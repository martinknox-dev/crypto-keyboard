package com.mkcreations.cryptokeyboard.crypto

import android.util.Base64
import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exports and imports keys as password-protected files
 */
object KeyExporter {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val SALT_SIZE = 16
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 12

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
            val derivedKey = deriveKey(password, salt)

            // Prepare key data as JSON
            val keyData = JSONObject().apply {
                put("id", key.id)
                put("label", key.label)
                put("keyBytes", Base64.encodeToString(key.keyBytes, Base64.NO_WRAP))
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
            val exportJson = JSONObject().apply {
                put("version", 1)
                put("type", "cipher_key_export")
                put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
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
            val salt = Base64.decode(exportJson.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(exportJson.getString("iv"), Base64.NO_WRAP)
            val encrypted = Base64.decode(exportJson.getString("data"), Base64.NO_WRAP)

            // Derive key from password
            val derivedKey = deriveKey(password, salt)

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
                keyBytes = Base64.decode(keyData.getString("keyBytes"), Base64.NO_WRAP),
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
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_SIZE
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}