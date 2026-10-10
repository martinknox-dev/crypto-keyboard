package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
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

    sealed interface ExportOutcome {
        data class Success(val json: String) : ExportOutcome
        data class Failure(val reason: String, val cause: Throwable? = null) : ExportOutcome
    }

    sealed interface ImportOutcome {
        data class Success(val key: CipherKey) : ImportOutcome
        data class Failure(val reason: String, val cause: Throwable? = null) : ImportOutcome
    }

    /**
     * Exports a key to an encrypted JSON string
     * Protected with user-provided password
     */
    fun exportKey(key: CipherKey, password: String): ExportOutcome {
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
            val exportJson = JSONObject().apply {
                put("version", 1)
                put("type", "cipher_key_export")
                put("salt", Base64.getEncoder().encodeToString(salt))
                put("iv", Base64.getEncoder().encodeToString(iv))
                put("data", Base64.getEncoder().encodeToString(encrypted))
            }

            ExportOutcome.Success(exportJson.toString(2)) // Pretty print
        } catch (e: Exception) {
            ExportOutcome.Failure("Export failed: ${e.javaClass.simpleName}", e)
        }
    }

    /**
     * Imports a key from an encrypted export string
     */
    fun importKey(exportString: String, password: String): ImportOutcome {
        return try {
            val exportJson = JSONObject(exportString)

            // Validate
            if (exportJson.optInt("version") != 1) {
                return ImportOutcome.Failure("Unsupported export format")
            }
            if (exportJson.optString("type") != "cipher_key_export") {
                return ImportOutcome.Failure("Unsupported export format")
            }

            // Extract components
            val salt = Base64.getDecoder().decode(exportJson.getString("salt"))
            val iv = Base64.getDecoder().decode(exportJson.getString("iv"))
            val encrypted = Base64.getDecoder().decode(exportJson.getString("data"))

            // Derive key from password
            val derivedKey = deriveKey(password, salt)

            // Decrypt
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(derivedKey, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decrypted = try {
                cipher.doFinal(encrypted)
            } catch (e: AEADBadTagException) {
                return ImportOutcome.Failure("Wrong password or corrupted file", e)
            }

            // Parse key data
            val keyData = JSONObject(String(decrypted, Charsets.UTF_8))

            ImportOutcome.Success(
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
            )
        } catch (e: Exception) {
            ImportOutcome.Failure("Malformed export data", e)
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
