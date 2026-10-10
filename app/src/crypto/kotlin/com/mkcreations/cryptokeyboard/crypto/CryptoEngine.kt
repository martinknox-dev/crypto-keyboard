package com.mkcreations.cryptokeyboard.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM message encryption.
 *
 * v2 wire format: [1 byte version=2][12 byte IV][ciphertext + 16 byte tag]
 * AAD = the 13 header bytes (version + IV), so header tampering fails auth.
 *
 * v1 wire format (legacy receive only): [1 byte version=1][4 byte fingerprint]
 * [12 byte IV][ciphertext + tag], no AAD. The fingerprint identifies the key.
 */
object CryptoEngine {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 16
    private const val KEY_SIZE = 32

    const val VERSION_V1: Byte = 1
    const val VERSION_V2: Byte = 2
    private const val V1_HEADER_SIZE = 1 + 4 + IV_SIZE   // version + fingerprint + IV
    private const val V2_HEADER_SIZE = 1 + IV_SIZE       // version + IV

    private val secureRandom = SecureRandom()

    sealed interface EncryptOutcome {
        data class Success(val payload: ByteArray) : EncryptOutcome {
            override fun equals(other: Any?): Boolean =
                other is Success && payload.contentEquals(other.payload)
            override fun hashCode(): Int = payload.contentHashCode()
        }
        data class Failure(val reason: EncryptFailure, val cause: Throwable? = null) : EncryptOutcome
    }

    enum class EncryptFailure { INVALID_KEY_SIZE, CIPHER_ERROR }

    sealed interface DecryptOutcome {
        data class Success(val plaintext: String) : DecryptOutcome
        object NotOurFormat : DecryptOutcome
        object AuthenticationFailed : DecryptOutcome
        data class Failure(val reason: String, val cause: Throwable? = null) : DecryptOutcome
    }

    fun encrypt(plaintext: String, keyBytes: ByteArray): EncryptOutcome {
        if (keyBytes.size != KEY_SIZE) {
            return EncryptOutcome.Failure(EncryptFailure.INVALID_KEY_SIZE)
        }
        return try {
            val iv = ByteArray(IV_SIZE).also { secureRandom.nextBytes(it) }
            val header = ByteArray(V2_HEADER_SIZE)
            header[0] = VERSION_V2
            iv.copyInto(header, 1)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val payload = ByteArray(V2_HEADER_SIZE + encrypted.size)
            header.copyInto(payload, 0)
            encrypted.copyInto(payload, V2_HEADER_SIZE)
            EncryptOutcome.Success(payload)
        } catch (e: Exception) {
            EncryptOutcome.Failure(EncryptFailure.CIPHER_ERROR, e)
        }
    }

    fun decryptV2(data: ByteArray, keyBytes: ByteArray): DecryptOutcome {
        if (data.size < V2_HEADER_SIZE + TAG_SIZE) return DecryptOutcome.NotOurFormat
        if (data[0] != VERSION_V2) return DecryptOutcome.NotOurFormat
        return try {
            val header = data.copyOfRange(0, V2_HEADER_SIZE)
            val iv = header.copyOfRange(1, V2_HEADER_SIZE)
            val ciphertext = data.copyOfRange(V2_HEADER_SIZE, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(header)
            DecryptOutcome.Success(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            DecryptOutcome.AuthenticationFailed
        } catch (e: Exception) {
            DecryptOutcome.Failure("v2 decryption error", e)
        }
    }

    fun decryptV1(data: ByteArray, keyBytes: ByteArray): DecryptOutcome {
        if (data.size < V1_HEADER_SIZE + TAG_SIZE) return DecryptOutcome.NotOurFormat
        if (data[0] != VERSION_V1) return DecryptOutcome.NotOurFormat
        return try {
            val iv = data.copyOfRange(5, 5 + IV_SIZE)
            val ciphertext = data.copyOfRange(V1_HEADER_SIZE, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            DecryptOutcome.Success(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            DecryptOutcome.AuthenticationFailed
        } catch (e: Exception) {
            DecryptOutcome.Failure("v1 decryption error", e)
        }
    }

    /** Only meaningful for v1 payloads; null for v2 or unknown data. */
    fun extractFingerprint(data: ByteArray): String? {
        if (data.size < 5 || data[0] != VERSION_V1) return null
        return (1..4).joinToString("") { "%02x".format(data[it]) }
    }

    fun isValidFormat(data: ByteArray): Boolean = when {
        data.isEmpty() -> false
        data[0] == VERSION_V2 && data.size >= V2_HEADER_SIZE + TAG_SIZE -> true
        data[0] == VERSION_V1 && data.size >= V1_HEADER_SIZE + TAG_SIZE -> true
        else -> false
    }
}
