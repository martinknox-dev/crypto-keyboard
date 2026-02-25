package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.security.SecureRandom

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
     * Creates a 4-byte fingerprint of a key for identification
     * This is NOT the key — it's a hash used to identify which key to use
     */
    fun fingerprint(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(keyBytes)
        // Take first 4 bytes, convert to hex = 8 character ID
        return hash.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * Converts key bytes to a human-readable word phrase (BIP39-style)
     * for sharing between users
     */
    fun keyToWordPhrase(keyBytes: ByteArray): List<String> {
        // Convert 32 bytes (256 bits) to word indices
        // Each word encodes 11 bits, so 256 bits = ~24 words
        // We'll use a simpler approach: each byte maps to a word (32 words)
        val words = mutableListOf<String>()
        for (byte in keyBytes) {
            val index = byte.toInt() and 0xFF // 0-255
            words.add(WORD_LIST[index])
        }
        return words
    }

    /**
     * Converts a word phrase back to key bytes
     */
    fun wordPhraseToKey(words: List<String>): ByteArray? {
        if (words.size != 32) return null
        val bytes = ByteArray(32)
        for (i in words.indices) {
            val index = WORD_LIST.indexOf(words[i].lowercase().trim())
            if (index < 0) return null
            bytes[i] = index.toByte()
        }
        return bytes
    }

    /**
     * Generates a human-readable short phrase for display
     * Shows first 8 words for quick verification
     */
    fun shortPhrase(keyBytes: ByteArray): String {
        return keyToWordPhrase(keyBytes).take(8).joinToString(" ")
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
}