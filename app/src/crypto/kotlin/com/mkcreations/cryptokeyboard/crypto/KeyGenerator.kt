package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object KeyGenerator {

    private val secureRandom = SecureRandom()

    /**
     * Generates a cryptographically secure 256-bit key
     */
    fun generateKey(): ByteArray {
        val key = ByteArray(32) // 256 bits
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Creates a 4-byte fingerprint of a key for identification.
     * This is NOT the key — it's a hash used to identify which key to use.
     *
     * NOTE: The fingerprint is a *static* identifier. It is used for local key
     * storage and QR exchange, but it is no longer placed on the wire (see
     * [keyHint]) because a constant per-key identifier lets an observer
     * correlate all messages that use the same key.
     */
    fun fingerprint(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(keyBytes)
        // Take first 4 bytes, convert to hex = 8 character ID
        return hash.take(4).joinToString("") { "%02x".format(it) }
    }

    // Domain-separation label for the keyed key hint (protocol v2).
    private const val KEY_HINT_LABEL = "CryptoKeyboard/key-hint/v2"

    /**
     * Computes the 4-byte key hint for the v2 wire format.
     *
     * Unlike the static [fingerprint], the hint is a keyed HMAC truncated to
     * 4 bytes. An observer cannot precompute hints or correlate messages by a
     * constant identifier without knowing the key. The receiver recomputes
     * the hint for each of its own keys and matches in constant time.
     */
    fun keyHint(keyBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(KEY_HINT_LABEL.toByteArray(Charsets.UTF_8)).copyOf(4)
    }

    fun keyHintHex(keyBytes: ByteArray): String {
        return keyHint(keyBytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time comparison of a key's hint against a hint extracted from
     * a received message.
     */
    fun matchesKeyHint(keyBytes: ByteArray, hintHex: String): Boolean {
        val expected = hexToBytes(hintHex) ?: return false
        return MessageDigest.isEqual(keyHint(keyBytes), expected)
    }

    // --- Recovery phrases ---

    /** Number of words that carry key material. */
    const val PHRASE_WORD_COUNT = 32

    /** Total words in a phrase including the checksum word. */
    const val PHRASE_TOTAL_WORD_COUNT = 33

    /**
     * Converts key bytes to a human-readable word phrase for sharing between
     * users. The phrase is 33 words: 32 words encode the 256-bit key (one word
     * per byte) plus a 33rd *checksum word* (the first byte of SHA-256(key))
     * so that a single typo can be detected instead of silently producing a
     * wrong key.
     */
    fun keyToWordPhrase(keyBytes: ByteArray): List<String> {
        val words = mutableListOf<String>()
        for (byte in keyBytes) {
            val index = byte.toInt() and 0xFF // 0-255
            words.add(WORD_LIST[index])
        }
        val checksum = MessageDigest.getInstance("SHA-256").digest(keyBytes)[0]
        words.add(WORD_LIST[checksum.toInt() and 0xFF])
        return words
    }

    sealed class PhraseParseResult {
        data class Valid(val keyBytes: ByteArray) : PhraseParseResult()
        /** Legacy 32-word phrase without a checksum word — accepted for
         *  backward compatibility, but typos cannot be detected. */
        data class ValidLegacyNoChecksum(val keyBytes: ByteArray) : PhraseParseResult()
        object InvalidChecksum : PhraseParseResult()
        object InvalidWord : PhraseParseResult()
        object InvalidCount : PhraseParseResult()
    }

    /**
     * Parses a word phrase back into key bytes.
     *
     * Accepts 33 words (32 key words + checksum word, checksum verified) and
     * legacy 32-word phrases (no checksum). Returns a [PhraseParseResult]
     * describing exactly what was wrong so the UI can give useful feedback.
     */
    fun parseWordPhrase(words: List<String>): PhraseParseResult {
        val normalized = words
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }

        return when (normalized.size) {
            PHRASE_TOTAL_WORD_COUNT -> {
                val key = decodeWords(normalized.take(PHRASE_WORD_COUNT))
                    ?: return PhraseParseResult.InvalidWord
                val expectedChecksum =
                    MessageDigest.getInstance("SHA-256").digest(key)[0].toInt() and 0xFF
                val providedChecksum = wordToIndex(normalized[PHRASE_WORD_COUNT])
                    ?: return PhraseParseResult.InvalidWord
                if (providedChecksum != expectedChecksum) {
                    PhraseParseResult.InvalidChecksum
                } else {
                    PhraseParseResult.Valid(key)
                }
            }
            PHRASE_WORD_COUNT -> {
                val key = decodeWords(normalized)
                    ?: return PhraseParseResult.InvalidWord
                PhraseParseResult.ValidLegacyNoChecksum(key)
            }
            else -> PhraseParseResult.InvalidCount
        }
    }

    /**
     * Converts a word phrase back to key bytes.
     * Returns null if the phrase is invalid (wrong count, unknown words, or
     * a checksum mismatch). Use [parseWordPhrase] if you need to distinguish
     * failure reasons.
     */
    fun wordPhraseToKey(words: List<String>): ByteArray? {
        return when (val result = parseWordPhrase(words)) {
            is PhraseParseResult.Valid -> result.keyBytes
            is PhraseParseResult.ValidLegacyNoChecksum -> result.keyBytes
            else -> null
        }
    }

    /**
     * Generates a human-readable short phrase for display
     * Shows first 8 words for quick verification
     */
    fun shortPhrase(keyBytes: ByteArray): String {
        return keyToWordPhrase(keyBytes).take(8).joinToString(" ")
    }

    private fun decodeWords(words: List<String>): ByteArray? {
        if (words.size != PHRASE_WORD_COUNT) return null
        val bytes = ByteArray(PHRASE_WORD_COUNT)
        for (i in words.indices) {
            val index = wordToIndex(words[i]) ?: return null
            bytes[i] = index.toByte()
        }
        return bytes
    }

    private fun wordToIndex(word: String): Int? {
        return WORD_TO_INDEX[word]
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length != 8) return null
        return try {
            ByteArray(4) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) +
                        Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }

    // 256-word list (one word per possible byte value 0-255)
    // Using common, easy-to-spell English words
    private val WORD_LIST = listOf(
        "anchor", "apple", "arrow", "atlas", "azure",      // 0-4
        "badge", "basin", "beach", "bloom", "blaze",        // 5-9
        "brick", "brook", "brush", "cabin", "camel",        // 10-14
        "candy", "cargo", "cedar", "chain", "chalk",        // 15-19
        "charm", "chess", "chill", "cliff", "cloud",        // 20-24
        "cobra", "coral", "crane", "crisp", "crown",        // 25-29
        "crush", "curve", "dance", "delta", "depot",        // 30-34
        "diver", "dodge", "draft", "dream", "drift",        // 35-39
        "dusk", "eagle", "earth", "ember", "entry",         // 40-44
        "epoch", "equal", "event", "exact", "exile",        // 45-49
        "fable", "fairy", "feast", "fence", "field",        // 50-54
        "flame", "flash", "flock", "flood", "flute",        // 55-59
        "focus", "forge", "frost", "fruit", "gamer",        // 60-64
        "gauge", "ghost", "giant", "glare", "gleam",        // 65-69
        "globe", "gloom", "grace", "grain", "grape",        // 70-74
        "grasp", "gravel", "grove", "guard", "guide",       // 75-79
        "habit", "happy", "haven", "heart", "hedge",        // 80-84
        "heron", "honor", "house", "humor", "hyper",        // 85-89
        "ivory", "jewel", "joker", "judge", "juice",        // 90-94
        "karma", "kayak", "knack", "knife", "label",        // 95-99
        "lance", "latch", "layer", "lemon", "level",        // 100-104
        "light", "lilac", "linen", "liver", "llama",        // 105-109
        "lodge", "logic", "lunar", "mango", "manor",        // 110-114
        "maple", "march", "marsh", "medal", "melon",        // 115-119
        "mercy", "metal", "minor", "mirth", "mocha",        // 120-124
        "model", "money", "month", "moose", "morph",        // 125-129
        "mount", "mural", "music", "noble", "north",        // 130-134
        "novel", "nurse", "oasis", "ocean", "olive",        // 135-139
        "omega", "onion", "opera", "orbit", "organ",        // 140-144
        "outer", "owner", "oxide", "panda", "panel",        // 145-149
        "paper", "party", "patch", "pearl", "penny",        // 150-154
        "phase", "piano", "pixel", "place", "plank",        // 155-159
        "plant", "plaza", "plumb", "point", "polar",        // 160-164
        "pouch", "power", "press", "prism", "prize",        // 165-169
        "probe", "proud", "pulse", "pupil", "quake",        // 170-174
        "queen", "quest", "quick", "quiet", "quota",        // 175-179
        "radar", "raven", "razor", "rebel", "reign",        // 180-184
        "relax", "ridge", "rifle", "river", "robin",        // 185-189
        "robot", "rocky", "royal", "rugby", "ruler",        // 190-194
        "saint", "salad", "scale", "scene", "scout",        // 195-199
        "shade", "shape", "shark", "sheep", "shelf",        // 200-204
        "shell", "shift", "shine", "shore", "siege",        // 205-209
        "sight", "silk", "siren", "skill", "slate",         // 210-214
        "slide", "slope", "smile", "snake", "solar",        // 215-219
        "solid", "sonic", "space", "spade", "spark",        // 220-224
        "spear", "spice", "spine", "spoke", "spoon",        // 225-229
        "squad", "staff", "stage", "stamp", "stand",        // 230-234
        "steam", "steel", "stern", "stone", "storm",        // 235-239
        "stove", "straw", "style", "sugar", "surge",        // 240-244
        "swamp", "sweep", "swift", "sword", "syrup",        // 245-249
        "table", "tiger", "torch", "tower", "trail",        // 250-254
        "trend"                                              // 255
    )

    // Reverse lookup: word -> byte value (O(1) phrase parsing)
    private val WORD_TO_INDEX: Map<String, Int> =
        WORD_LIST.withIndex().associate { (index, word) -> word to index }
}
