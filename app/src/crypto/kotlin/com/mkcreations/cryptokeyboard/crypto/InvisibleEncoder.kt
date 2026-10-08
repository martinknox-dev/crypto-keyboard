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

    /**
     * Soft limit for the total encoded message length (visible cover +
     * invisible payload + markers). Most messengers truncate or reject very
     * long messages; some platforms (e.g. SMS in UCS-2 mode) have far lower
     * per-segment limits, so treat this as a warning threshold, not a
     * guarantee.
     */
    const val MAX_TOTAL_CHARS = 4000

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

    /**
     * Strips characters that would corrupt the stego payload from a cover
     * message: the 16 encoding characters and the marker characters. Without
     * this, a cover message containing e.g. a pasted zero-width space or a
     * soft hyphen would silently corrupt the embedded data.
     */
    fun sanitizeCover(cover: String): String {
        return cover.filter { it !in CHAR_TO_NIBBLE && it != '\u2068' && it != '\u2069' }
    }

    /**
     * True if the text contains characters from the stego alphabet or the
     * marker set (i.e. it cannot be used as a cover message verbatim).
     */
    fun containsStegoChars(text: String): Boolean {
        return text.any { it in CHAR_TO_NIBBLE || it == '\u2068' || it == '\u2069' }
    }

    /**
     * Encodes encrypted bytes into invisible characters
     * and embeds them into a cover message.
     *
     * The cover message is sanitized first (see [sanitizeCover]).
     */
    fun embedInCover(encryptedData: ByteArray, coverMessage: String): String {
        try {
            val invisibleData = bytesToInvisible(encryptedData)

            val cleanCover = sanitizeCover(coverMessage)
            if (cleanCover.isEmpty()) {
                return SIGNATURE_STR + invisibleData + END_MARKER_STR
            }

            val coverChars = cleanCover.toList()
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
            // Fallback: just concatenate (sanitized cover)
            return sanitizeCover(coverMessage) + SIGNATURE_STR + bytesToInvisible(encryptedData) + END_MARKER_STR
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
     * Extracts hidden encrypted bytes from a message
     * Returns null if no hidden data found or data is corrupted
     */
    fun extractFromMessage(message: String): ByteArray? {
        return try {
            val sigIndex = message.indexOf(SIGNATURE_STR)
            if (sigIndex < 0) return null

            val dataStart = sigIndex + SIGNATURE_STR.length
            val endIndex = message.indexOf(END_MARKER_STR, dataStart)
            if (endIndex < 0 || endIndex <= dataStart) return null

            val dataPortion = message.substring(dataStart, endIndex)

            // Filter only our encoding characters (remove visible cover chars)
            val invisibleOnly = dataPortion.filter { it in CHAR_TO_NIBBLE }

            if (invisibleOnly.isEmpty()) return null
            if (invisibleOnly.length % 2 != 0) return null

            invisibleToBytes(invisibleOnly)
        } catch (e: Exception) {
            null
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
        val estimatedEncryptedSize = 1 + 4 + 12 + secretMessage.toByteArray().size + 16
        val invisibleCharCount = estimatedEncryptedSize * 2
        val markerChars = SIGNATURE_STR.length + END_MARKER_STR.length
        val totalChars = coverMessage.length + invisibleCharCount + markerChars

        return EncodingStats(
            secretLength = secretMessage.length,
            coverLength = coverMessage.length,
            estimatedEncryptedBytes = estimatedEncryptedSize,
            invisibleCharCount = invisibleCharCount,
            totalMessageLength = totalChars,
            withinLimits = totalChars < MAX_TOTAL_CHARS
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