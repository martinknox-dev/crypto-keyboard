package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.security.SecureRandom

class InvisibleEncoderTest : FunSpec({

    val random = SecureRandom()

    // Signature/end markers and a few encoding chars, as explicit escapes
    // (writing them as literals would make the source unreviewable).
    val SIG = "\u2069\u2068\u2069\u2068"
    val END = "\u2068\u2069\u2068\u2069"
    val ZWSP = "\u200B"
    val SOFT_HYPHEN = "\u00AD"
    val PDI = "\u2069"

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    test("embed/extract round trip with empty cover") {
        val data = randomBytes(64)
        val message = InvisibleEncoder.embedInCover(data, "")
        InvisibleEncoder.containsHiddenData(message).shouldBeTrue()
        val extracted = InvisibleEncoder.extractFromMessage(message)
        extracted.shouldNotBeNull()
        extracted!!.contentEquals(data).shouldBeTrue()
    }

    test("embed/extract round trip with various covers") {
        val covers = listOf("o", "ok", "ok 👍", "a much longer cover message that spreads the payload", "x".repeat(500))
        for (cover in covers) {
            val data = randomBytes(64)
            val message = InvisibleEncoder.embedInCover(data, cover)
            val extracted = InvisibleEncoder.extractFromMessage(message)
            extracted.shouldNotBeNull()
            extracted!!.contentEquals(data).shouldBeTrue()
            InvisibleEncoder.extractCoverMessage(message) shouldBe cover
        }
    }

    test("round trip preserves unicode cover text") {
        val data = randomBytes(32)
        val cover = "héllo wörld 秘密 👋"
        val message = InvisibleEncoder.embedInCover(data, cover)
        InvisibleEncoder.extractFromMessage(message)!!.contentEquals(data).shouldBeTrue()
        InvisibleEncoder.extractCoverMessage(message) shouldBe cover
    }

    test("cover containing stego characters is sanitized before embedding") {
        val dirty = "ok" + ZWSP + SOFT_HYPHEN + PDI + " there"
        InvisibleEncoder.containsStegoChars(dirty).shouldBeTrue()
        InvisibleEncoder.sanitizeCover(dirty) shouldBe "ok there"

        val data = randomBytes(32)
        val message = InvisibleEncoder.embedInCover(data, dirty)
        // Payload must survive even though the cover contained stego chars
        InvisibleEncoder.extractFromMessage(message)!!.contentEquals(data).shouldBeTrue()
        InvisibleEncoder.extractCoverMessage(message) shouldBe "ok there"
    }

    test("clean cover passes sanitization unchanged") {
        InvisibleEncoder.sanitizeCover("hello world") shouldBe "hello world"
        InvisibleEncoder.containsStegoChars("hello world").shouldBeFalse()
    }

    test("containsHiddenData is false for plain text") {
        InvisibleEncoder.containsHiddenData("just a normal message").shouldBeFalse()
        InvisibleEncoder.containsHiddenData("short").shouldBeFalse()
        InvisibleEncoder.containsHiddenData("").shouldBeFalse()
    }

    test("extractFromMessage returns null for corrupted payloads") {
        val data = randomBytes(32)
        val message = InvisibleEncoder.embedInCover(data, "cover")

        // Truncated message (end marker removed)
        val truncated = message.removeSuffix(END)
        InvisibleEncoder.extractFromMessage(truncated).shouldBeNull()

        // Empty payload between markers
        InvisibleEncoder.extractFromMessage(SIG + END).shouldBeNull()
    }

    test("extractFromMessage returns null when payload has odd nibble count") {
        // Signature + a single encoding char + end marker = odd nibble count
        val message = SIG + ZWSP + END
        InvisibleEncoder.extractFromMessage(message).shouldBeNull()
    }

    test("extractCoverMessage strips payload and keeps visible text") {
        val data = randomBytes(48)
        val message = InvisibleEncoder.embedInCover(data, "visible cover")
        InvisibleEncoder.extractCoverMessage(message) shouldBe "visible cover"
    }

    test("encoding stats estimate size and limits") {
        val stats = InvisibleEncoder.getEncodingStats("hello", "ok")
        stats.secretLength shouldBe 5
        stats.coverLength shouldBe 2
        // 1 + 4 + 12 + 5 + 16 = 38 bytes -> 76 invisible chars + 8 marker chars
        stats.estimatedEncryptedBytes shouldBe 38
        stats.invisibleCharCount shouldBe 76
        stats.totalMessageLength shouldBe 86
        stats.withinLimits.shouldBeTrue()

        val huge = InvisibleEncoder.getEncodingStats("x".repeat(3000), "")
        huge.withinLimits.shouldBeFalse()
        // (1 + 4 + 12 + 3000 + 16) bytes * 2 nibbles + 8 marker chars
        huge.totalMessageLength shouldBe (1 + 4 + 12 + 3000 + 16) * 2 + 8
    }

    test("marker characters are not part of the encoding alphabet") {
        // The signature/end markers must not collide with the 16 encoding
        // chars, otherwise extraction would be ambiguous.
        val encodingChars =
                "\u200B\u200C\u200D\u2060\u2061\u2062\u2063\u2064" +
                "\u206A\u206B\u206C\u206D\u206E\u206F\uFEFF\u00AD"
        listOf('\u2069', '\u2068').forEach { markerChar ->
            if (encodingChars.contains(markerChar)) {
                throw AssertionError(
                    "marker char U+%04X collides with encoding alphabet".format(markerChar.code)
                )
            }
        }
    }
})
