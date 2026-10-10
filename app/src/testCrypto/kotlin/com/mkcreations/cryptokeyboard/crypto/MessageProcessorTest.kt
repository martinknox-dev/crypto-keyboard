package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MessageProcessorTest : FunSpec({

    val keyBytes = ByteArray(32) { (it * 3).toByte() }
    val key = CipherKey(
        id = KeyGenerator.fingerprint(keyBytes),
        label = "Alice",
        keyBytes = keyBytes,
        createdAt = 0L,
        lastUsedAt = 0L,
        coverStyle = CoverStyle.RANDOM_CASUAL,
        compatibleChars = emptyList()
    )

    test("v2 embed and decrypt roundtrip through MessageProcessor") {
        val enc = MessageProcessor.encryptAndEmbed("hi there", "ok", key)
            .shouldBeInstanceOf<MessageProcessor.EncryptResult.Success>()
        val out = MessageProcessor.detectAndDecrypt(enc.finalMessage, listOf(key))
        out.shouldBeInstanceOf<MessageProcessor.DecryptResult.Success>()
        out.secretMessage shouldBe "hi there"
        out.coverMessage shouldBe "ok"
        out.keyLabel shouldBe "Alice"
    }

    test("v2 payload from another key reports decryption failure") {
        val enc = MessageProcessor.encryptAndEmbed("classified", "ok", key)
            .shouldBeInstanceOf<MessageProcessor.EncryptResult.Success>()
        val other = key.copy(
            id = "ffffffff", keyBytes = ByteArray(32) { (it + 1).toByte() }
        )
        MessageProcessor.detectAndDecrypt(enc.finalMessage, listOf(other))
            .shouldBeInstanceOf<MessageProcessor.DecryptResult.DecryptionFailed>()
    }

    test("plain message without hidden data is NotEncrypted") {
        MessageProcessor.detectAndDecrypt("just a normal message", listOf(key))
            .shouldBeInstanceOf<MessageProcessor.DecryptResult.NotEncrypted>()
    }

    test("blank secret is rejected") {
        MessageProcessor.encryptAndEmbed("   ", "ok", key)
            .shouldBeInstanceOf<MessageProcessor.EncryptResult.Error>()
    }

    test("verbatim re-send of a decrypted message is rejected as replay") {
        val enc = MessageProcessor.encryptAndEmbed("only once", "ok", key)
            .shouldBeInstanceOf<MessageProcessor.EncryptResult.Success>()
        ReplayGuard.shared.clear()
        MessageProcessor.detectAndDecrypt(enc.finalMessage, listOf(key))
            .shouldBeInstanceOf<MessageProcessor.DecryptResult.Success>()
        MessageProcessor.detectAndDecrypt(enc.finalMessage, listOf(key))
            .shouldBeInstanceOf<MessageProcessor.DecryptResult.Replayed>()
        MessageProcessor.isKnownReplay(enc.finalMessage) shouldBe true
        ReplayGuard.shared.clear()
    }

    test("NO_COVER base64 message roundtrips") {
        val noCoverKey = key.copy(coverStyle = CoverStyle.NO_COVER)
        val enc = MessageProcessor.encryptAndEmbed("raw payload", "", noCoverKey)
            .shouldBeInstanceOf<MessageProcessor.EncryptResult.Success>()
        java.util.Base64.getDecoder().decode(enc.finalMessage) // valid base64
        ReplayGuard.shared.clear()
        val out = MessageProcessor.detectAndDecrypt(enc.finalMessage, listOf(noCoverKey))
        out.shouldBeInstanceOf<MessageProcessor.DecryptResult.Success>()
        out.secretMessage shouldBe "raw payload"
        ReplayGuard.shared.clear()
    }

    test("plain text that looks like base64 is not encrypted") {
        MessageProcessor.detectAndDecrypt("test", listOf(key))
            .shouldBeInstanceOf<MessageProcessor.DecryptResult.NotEncrypted>()
    }
})
