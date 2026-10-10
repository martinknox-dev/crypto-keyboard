package com.mkcreations.cryptokeyboard.crypto

/**
 * Encodes binary data as invisible Unicode characters
 * and embeds them inside a visible cover message.
 *
 * Uses zero-width and invisible Unicode characters that
 * are preserved by most messengers.
 *
 * Encoding: Each byte is split into 2 nibbles (4 bits each)
 * Each nibble maps to one of 16 invisible characters
 * This means each byte = 2 invisible characters
 */
object InvisibleEncoder {

    // 16 invisible Unicode characters — one per nibble value (0-15)
    private val INVISIBLE_CHARS = charArrayOf(
        '\u200B',   // 0  - Zero Width Space
        '\u200C',   // 1  - Zero Width Non-Joiner
        '\u200D',   // 2  - Zero Width Joiner
        '\u2060',   // 3  - Word Joiner
        '\u2061',   // 4  - Function Application
        '\u2062',   // 5  - Invisible Times
        '\u2063',   // 6  - Invisible Separator
        '\u2064',   // 7  - Invisible Plus
        '\u206A',   // 8  - Inhibit Symmetric Swapping
        '\u206B',   // 9  - Activate Symmetric Swapping
        '\u206C',   // 10 - Inhibit Arabic Form Shaping
        '\u206D',   // 11 - Activate Arabic Form Shaping
        '\u206E',   // 12 - National Digit Shapes
        '\u206F',   // 13 - Nominal Digit Shapes
        '\uFEFF',   // 14 - Zero Width No-Break Space (BOM)
        '\u00AD'    // 15 - Soft Hyphen
    )

    // Reverse lookup: char -> nibble value
    private val CHAR_TO_NIBBLE = HashMap<Char, Int>().apply {
        INVISIBLE_CHARS.forEachIndexed { index, char ->
            put(char, index)
        }
    }

    // Signature uses UNIQUE characters not in the encoding set
    // Using specific sequences of rare Unicode chars as delimiters
    private val SIGNATURE_STR = "\u2069\u2068\u2069\u2068"  // Pop/First Directional Isolate pattern
    private val END_MARKER_STR = "\u2068\u2069\u2068\u2069"  // Reversed pattern

    /** All characters that would corrupt payload extraction (encoding alphabet + markers). */
    private val FORBIDDEN_COVER_CHARS: Set<Char> =
        INVISIBLE_CHARS.toSet() + '\u2068' + '\u2069'

    /**
     * Strips every character the encoding uses (or that marks payload
     * boundaries) from user-provided cover text, so pasted zero-width
     * characters cannot corrupt extraction of the payload.
     */
    fun sanitizeCover(text: String): String =
        text.filter { it !in FORBIDDEN_COVER_CHARS }

    /** Typed result of extracting a payload from a message. */
    sealed interface ExtractOutcome {
        data class Success(val data: ByteArray) : ExtractOutcome {
            override fun equals(other: Any?): Boolean =
                other is Success && data.contentEquals(other.data)
            override fun hashCode(): Int = data.contentHashCode()
        }
        object NotPresent : ExtractOutcome
        object Malformed : ExtractOutcome
    }

    /**
     * Encodes encrypted bytes into invisible characters
     * and embeds them into a cover message.
     */
    fun embedInCover(encryptedData: ByteArray, coverMessage: String): String {
        val cover = sanitizeCover(coverMessage)
        try {
            val invisibleData = bytesToInvisible(encryptedData)

            if (cover.isEmpty()) {
                return SIGNATURE_STR + invisibleData + END_MARKER_STR
            }

            val coverChars = cover.toList()
            val result = StringBuilder()
            val invisibleChars = invisibleData.toList()

            // Strategy: first char + signature + distribute data + end marker
            result.append(coverChars[0])
            result.append(SIGNATURE_STR)

            if (coverChars.size > 1) {
                val chunkSize = (invisibleChars.size / (coverChars.size - 1)) + 1
                val chunks = invisibleChars.chunked(chunkSize)

                for (i in 1 until coverChars.size) {
                    val chunkIndex = i - 1
                    if (chunkIndex < chunks.size) {
                        chunks[chunkIndex].forEach { result.append(it) }
                    }
                    result.append(coverChars[i])
                }

                // Remaining chunks after last visible char
                for (i in (coverChars.size - 1) until chunks.size) {
                    chunks[i].forEach { result.append(it) }
                }
            } else {
                // Only one visible char — put all invisible data after it
                invisibleChars.forEach { result.append(it) }
            }

            result.append(END_MARKER_STR)
            return result.toString()

        } catch (e: Exception) {
            // Fallback: just concatenate
            return cover + SIGNATURE_STR + bytesToInvisible(encryptedData) + END_MARKER_STR
        }
    }

    /**
     * Detects if a message contains hidden data
     */
    fun containsHiddenData(message: String): Boolean {
        return try {
            if (message.length < 10) return false
            val sigIndex = message.indexOf(SIGNATURE_STR)
            if (sigIndex < 0) return false
            val endIndex = message.indexOf(END_MARKER_STR, sigIndex + SIGNATURE_STR.length)
            endIndex > sigIndex
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extracts hidden encrypted bytes from a message.
     * Returns [ExtractOutcome.NotPresent] when no signature exists,
     * [ExtractOutcome.Malformed] when a signature exists but the payload
     * cannot be reconstructed (truncated or corrupted cover text).
     */
    fun extractFromMessage(message: String): ExtractOutcome {
        return try {
            val sigIndex = message.indexOf(SIGNATURE_STR)
            if (sigIndex < 0) return ExtractOutcome.NotPresent

            val dataStart = sigIndex + SIGNATURE_STR.length
            val endIndex = message.indexOf(END_MARKER_STR, dataStart)
            if (endIndex < 0 || endIndex <= dataStart) return ExtractOutcome.Malformed

            val dataPortion = message.substring(dataStart, endIndex)

            // Filter only our encoding characters (remove visible cover chars)
            val invisibleOnly = dataPortion.filter { it in CHAR_TO_NIBBLE }

            if (invisibleOnly.isEmpty()) return ExtractOutcome.Malformed
            if (invisibleOnly.length % 2 != 0) return ExtractOutcome.Malformed

            val bytes = invisibleToBytes(invisibleOnly)
                ?: return ExtractOutcome.Malformed
            ExtractOutcome.Success(bytes)
        } catch (e: Exception) {
            ExtractOutcome.Malformed
        }
    }

    /**
     * Extracts the visible cover message (strips all invisible and marker chars)
     */
    fun extractCoverMessage(message: String): String {
        return try {
            val result = StringBuilder()
            var i = 0
            while (i < message.length) {
                val char = message[i]
                // Skip signature and end marker sequences
                if (i + SIGNATURE_STR.length <= message.length &&
                    message.substring(i, i + SIGNATURE_STR.length) == SIGNATURE_STR) {
                    i += SIGNATURE_STR.length
                    continue
                }
                if (i + END_MARKER_STR.length <= message.length &&
                    message.substring(i, i + END_MARKER_STR.length) == END_MARKER_STR) {
                    i += END_MARKER_STR.length
                    continue
                }
                // Skip invisible encoding chars
                if (char in CHAR_TO_NIBBLE) {
                    i++
                    continue
                }
                // Skip marker chars individually
                if (char == '\u2068' || char == '\u2069') {
                    i++
                    continue
                }
                result.append(char)
                i++
            }
            result.toString()
        } catch (e: Exception) {
            message.filter { it !in CHAR_TO_NIBBLE && it != '\u2068' && it != '\u2069' }
        }
    }

    /**
     * Converts bytes to invisible character string
     * Each byte → 2 invisible chars (high nibble, low nibble)
     */
    private fun bytesToInvisible(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (byte in data) {
            val value = byte.toInt() and 0xFF
            val highNibble = (value shr 4) and 0x0F
            val lowNibble = value and 0x0F
            sb.append(INVISIBLE_CHARS[highNibble])
            sb.append(INVISIBLE_CHARS[lowNibble])
        }
        return sb.toString()
    }

    /**
     * Converts invisible character string back to bytes
     */
    private fun invisibleToBytes(invisible: String): ByteArray? {
        return try {
            if (invisible.length % 2 != 0) return null

            val bytes = ByteArray(invisible.length / 2)
            for (i in bytes.indices) {
                val highChar = invisible[i * 2]
                val lowChar = invisible[i * 2 + 1]

                val highNibble = CHAR_TO_NIBBLE[highChar] ?: return null
                val lowNibble = CHAR_TO_NIBBLE[lowChar] ?: return null

                bytes[i] = ((highNibble shl 4) or lowNibble).toByte()
            }
            bytes
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns stats about the encoding for UI display
     */
    fun getEncodingStats(secretMessage: String, coverMessage: String): EncodingStats {
        val estimatedEncryptedSize = 1 + 12 + secretMessage.toByteArray().size + 16
        val invisibleCharCount = estimatedEncryptedSize * 2
        val markerChars = SIGNATURE_STR.length + END_MARKER_STR.length
        val totalChars = coverMessage.length + invisibleCharCount + markerChars

        return EncodingStats(
            secretLength = secretMessage.length,
            coverLength = coverMessage.length,
            estimatedEncryptedBytes = estimatedEncryptedSize,
            invisibleCharCount = invisibleCharCount,
            totalMessageLength = totalChars,
            withinLimits = totalChars < 4000
        )
    }

    data class EncodingStats(
        val secretLength: Int,
        val coverLength: Int,
        val estimatedEncryptedBytes: Int,
        val invisibleCharCount: Int,
        val totalMessageLength: Int,
        val withinLimits: Boolean
    )
}