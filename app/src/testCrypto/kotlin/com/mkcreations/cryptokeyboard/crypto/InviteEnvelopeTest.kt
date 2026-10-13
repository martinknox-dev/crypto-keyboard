package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class InviteEnvelopeTest : FunSpec({

    val key = ByteArray(32) { it.toByte() }

    test("round-trips a 32-byte key and ASCII note") {
        val bytes = InviteEnvelope.serialize(key, "hello")
        val env = InviteEnvelope.deserialize(bytes).getOrThrow()
        env.keyBytes shouldBe key
        env.note shouldBe "hello"
    }

    test("round-trips an empty note") {
        val env = InviteEnvelope.deserialize(InviteEnvelope.serialize(key, "")).getOrThrow()
        env.note shouldBe ""
    }

    test("serialize emits the exact header bytes") {
        val bytes = InviteEnvelope.serialize(key, "ab")
        bytes[0] shouldBe 0x53.toByte()
        bytes[1] shouldBe 0x4B.toByte()
        bytes[2] shouldBe InviteEnvelope.VERSION
        bytes[3] shouldBe InviteEnvelope.TYPE_KEY_INVITE
        // note length big-endian at offset 36..37 = 2
        bytes[36] shouldBe 0x00
        bytes[37] shouldBe 0x02
        bytes.size shouldBe InviteEnvelope.FIXED_SIZE + 2
    }

    test("truncated below fixed size fails") {
        InviteEnvelope.deserialize(ByteArray(37)).isFailure shouldBe true
    }

    test("empty bytes fail") {
        InviteEnvelope.deserialize(ByteArray(0)).isFailure shouldBe true
    }

    test("wrong magic fails") {
        val bytes = InviteEnvelope.serialize(key, "x")
        bytes[0] = 0x00.toByte()
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("unsupported version fails") {
        val bytes = InviteEnvelope.serialize(key, "x")
        bytes[2] = 0x02.toByte()
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("unsupported type fails") {
        val bytes = InviteEnvelope.serialize(key, "x")
        bytes[3] = 0x09.toByte()
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("note length claiming more bytes than present fails") {
        val bytes = InviteEnvelope.serialize(key, "abc")
        bytes[37] = 0x10.toByte() // claims 16 note bytes, only 3 present
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("note length over 128 fails") {
        val bytes = InviteEnvelope.serialize(key, "abc")
        bytes[36] = 0x00.toByte()
        bytes[37] = 0x81.toByte() // 129
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("serialize caps a long note at 128 bytes") {
        val longNote = "a".repeat(200)
        val bytes = InviteEnvelope.serialize(key, longNote)
        val env = InviteEnvelope.deserialize(bytes).getOrThrow()
        env.note.toByteArray(Charsets.UTF_8).size shouldBe 128
        env.note shouldBe "a".repeat(128)
    }

    test("serialize truncates multi-byte note on a character boundary") {
        val euro = "\u20AC" // 3 UTF-8 bytes
        val note = euro.repeat(50) // 150 bytes > 128
        val bytes = InviteEnvelope.serialize(key, note)
        val env = InviteEnvelope.deserialize(bytes).getOrThrow()
        // 128 / 3 = 42 full euros (126 bytes); must not split the 43rd.
        env.note shouldBe euro.repeat(42)
    }

    test("deserialize reports failure as InviteFormatException") {
        InviteEnvelope.deserialize(ByteArray(10))
            .exceptionOrNull()
            .shouldBeInstanceOf<InviteFormatException>()
    }
})
