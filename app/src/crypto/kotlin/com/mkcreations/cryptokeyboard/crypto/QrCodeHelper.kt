package com.mkcreations.cryptokeyboard.crypto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * Handles QR code generation and parsing for key exchange.
 *
 * QR payload format (JSON):
 * {
 *   "v": 1,
 *   "t": "ck",          // "ck" = cipher key
 *   "l": "Alice",       // label
 *   "k": "<base64key>", // key bytes
 *   "f": "a1b2c3d4"    // fingerprint
 * }
 */
object QrCodeHelper {

    private const val QR_VERSION = 1
    private const val QR_TYPE = "ck"

    /**
     * Generates a QR code bitmap from a CipherKey.
     *
     * @param key The key to encode
     * @param size QR code size in pixels
     * @return Bitmap of the QR code, or null on failure
     */
    fun generateQrBitmap(key: CipherKey, size: Int = 800): Bitmap? {
        return try {
            val payload = createPayload(key)
            val writer = QRCodeWriter()
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, size, size, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(
                        x, y,
                        if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                    )
                }
            }

            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Parses a QR code payload string back into a CipherKey.
     *
     * @param payload The raw string from QR scan
     * @return CipherKey or null if invalid
     */
    fun parseQrPayload(payload: String): CipherKey? {
        return try {
            val json = JSONObject(payload)

            // Validate
            if (json.optInt("v") != QR_VERSION) return null
            if (json.optString("t") != QR_TYPE) return null

            val label = json.getString("l")
            val keyBase64 = json.getString("k")
            val fingerprint = json.getString("f")

            val keyBytes = Base64.getDecoder().decode(keyBase64)
            if (keyBytes.size != 32) return null

            // Verify fingerprint matches (constant-time comparison)
            val computedFingerprint = KeyGenerator.fingerprint(keyBytes)
            if (!MessageDigest.isEqual(
                    computedFingerprint.toByteArray(Charsets.UTF_8),
                    fingerprint.toByteArray(Charsets.UTF_8)
                )
            ) return null

            CipherKey(
                id = fingerprint,
                label = label,
                keyBytes = keyBytes,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = 0L,
                coverStyle = CoverStyle.RANDOM_CASUAL,
                compatibleChars = emptyList()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Validates if a string looks like our QR payload
     */
    fun isValidPayload(payload: String): Boolean {
        return try {
            val json = JSONObject(payload)
            json.optInt("v") == QR_VERSION && json.optString("t") == QR_TYPE
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates the JSON payload string for a key
     */
    private fun createPayload(key: CipherKey): String {
        val json = JSONObject().apply {
            put("v", QR_VERSION)
            put("t", QR_TYPE)
            put("l", key.label)
            // WARNING: the raw key is in this payload — anyone who scans the
            // QR code can decrypt messages. Always verify the fingerprint
            // out-of-band after scanning.
            put("k", Base64.getEncoder().encodeToString(key.keyBytes))
            put("f", key.id)
        }
        return json.toString()
    }

    /**
     * Returns estimated QR data size for UI warnings
     */
    fun estimatePayloadSize(key: CipherKey): Int {
        return createPayload(key).length
    }
}