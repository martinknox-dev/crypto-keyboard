package com.mkcreations.cryptokeyboard.crypto

object CompatibilityTester {

    val ALL_TEST_CHARS = listOf(
        '\u200B' to "Zero Width Space",
        '\u200C' to "Zero Width Non-Joiner",
        '\u200D' to "Zero Width Joiner",
        '\u2060' to "Word Joiner",
        '\u2061' to "Function Application",
        '\u2062' to "Invisible Times",
        '\u2063' to "Invisible Separator",
        '\u2064' to "Invisible Plus",
        '\u206A' to "Inhibit Symmetric Swap",
        '\u206B' to "Activate Symmetric Swap",
        '\u206C' to "Inhibit Arabic Form",
        '\u206D' to "Activate Arabic Form",
        '\u206E' to "National Digit Shapes",
        '\u206F' to "Nominal Digit Shapes",
        '\uFEFF' to "Zero Width No-Break Space",
        '\u00AD' to "Soft Hyphen",
        '\u2068' to "First Strong Isolate",
        '\u2069' to "Pop Directional Isolate",
        '\u2066' to "Left-to-Right Isolate",
        '\u2067' to "Right-to-Left Isolate",
        '\u200E' to "Left-to-Right Mark",
        '\u200F' to "Right-to-Left Mark",
        '\u034F' to "Combining Grapheme Joiner",
        '\u061C' to "Arabic Letter Mark",
        '\u2800' to "Braille Pattern Blank",
        '\u115F' to "Hangul Choseong Filler",
        '\u1160' to "Hangul Jungseong Filler",
        '\u17B4' to "Khmer Vowel Inherent Aq",
        '\u17B5' to "Khmer Vowel Inherent Aa",
        '\u3164' to "Hangul Filler",
        '\uFFA0' to "Halfwidth Hangul Filler"
    )

    fun generateTestMessage(): String {
        val sb = StringBuilder()
        sb.append("T")
        for (i in ALL_TEST_CHARS.indices) {
            val (char, _) = ALL_TEST_CHARS[i]
            sb.append(char)
            sb.append(('a' + (i % 26)))
        }
        sb.append("E")
        return sb.toString()
    }

    fun generateSimpleTestMessage(): String {
        val sb = StringBuilder()
        sb.append("Te")
        for ((char, _) in ALL_TEST_CHARS) {
            sb.append(char)
        }
        sb.append("st")
        return sb.toString()
    }

    fun analyzeTestResult(receivedMessage: String): CompatibilityResult {
        val survived = mutableListOf<CharResult>()
        val stripped = mutableListOf<CharResult>()

        for ((char, name) in ALL_TEST_CHARS) {
            if (receivedMessage.contains(char)) {
                survived.add(CharResult(char, name, true))
            } else {
                stripped.add(CharResult(char, name, false))
            }
        }

        return CompatibilityResult(
            survived = survived,
            stripped = stripped,
            survivedCodePoints = survived.map { it.char.code },
            isUsable = survived.size >= 2,
            recommendation = when {
                survived.size >= 16 -> "Excellent — full invisible encoding works"
                survived.size >= 8 -> "Good — encoding works with slightly longer messages"
                survived.size >= 4 -> "Moderate — encoding works but messages will be longer"
                survived.size >= 2 -> "Limited — basic encoding possible but messages will be much longer"
                else -> "Poor — invisible encoding won't work. Use No Cover mode."
            }
        )
    }

    fun rawDump(message: String): String {
        val sb = StringBuilder()
        sb.appendLine("Raw character dump (${message.length} chars):")
        sb.appendLine()

        for (i in message.indices) {
            val char = message[i]
            val codePoint = char.code
            val hex = "U+%04X".format(codePoint)
            val isPrintable = !Character.isISOControl(char) &&
                    char.category != CharCategory.FORMAT &&
                    char.category != CharCategory.PRIVATE_USE &&
                    char.category != CharCategory.SURROGATE &&
                    char.category != CharCategory.UNASSIGNED &&
                    codePoint > 0x20

            val display = if (isPrintable && codePoint < 0x2000) {
                "'$char'"
            } else {
                "[INV]"
            }

            sb.appendLine("  [$i] $hex $display ${getCharName(codePoint)}")
        }

        return sb.toString()
    }

    private fun getCharName(codePoint: Int): String {
        return when (codePoint) {
            0x200B -> "Zero Width Space"
            0x200C -> "Zero Width Non-Joiner"
            0x200D -> "Zero Width Joiner"
            0x200E -> "Left-to-Right Mark"
            0x200F -> "Right-to-Left Mark"
            0x2060 -> "Word Joiner"
            0x2061 -> "Function Application"
            0x2062 -> "Invisible Times"
            0x2063 -> "Invisible Separator"
            0x2064 -> "Invisible Plus"
            0x2066 -> "Left-to-Right Isolate"
            0x2067 -> "Right-to-Left Isolate"
            0x2068 -> "First Strong Isolate"
            0x2069 -> "Pop Directional Isolate"
            0x206A -> "Inhibit Symmetric Swap"
            0x206B -> "Activate Symmetric Swap"
            0x206C -> "Inhibit Arabic Form"
            0x206D -> "Activate Arabic Form"
            0x206E -> "National Digit Shapes"
            0x206F -> "Nominal Digit Shapes"
            0xFEFF -> "Zero Width No-Break Space"
            0x00AD -> "Soft Hyphen"
            0x034F -> "Combining Grapheme Joiner"
            0x061C -> "Arabic Letter Mark"
            0x2800 -> "Braille Pattern Blank"
            0x115F -> "Hangul Choseong Filler"
            0x1160 -> "Hangul Jungseong Filler"
            0x17B4 -> "Khmer Vowel Inherent Aq"
            0x17B5 -> "Khmer Vowel Inherent Aa"
            0x3164 -> "Hangul Filler"
            0xFFA0 -> "Halfwidth Hangul Filler"
            else -> ""
        }
    }

    data class CharResult(
        val char: Char,
        val name: String,
        val survived: Boolean
    )

    data class CompatibilityResult(
        val survived: List<CharResult>,
        val stripped: List<CharResult>,
        val survivedCodePoints: List<Int>,
        val isUsable: Boolean,
        val recommendation: String
    )
}