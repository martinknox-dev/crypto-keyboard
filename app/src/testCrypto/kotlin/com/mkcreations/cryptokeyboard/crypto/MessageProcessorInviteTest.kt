package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MessageProcessorInviteTest : FunSpec({

    val key = ByteArray(32) { (it * 2).toByte() }

    fun testKey(bytes: ByteArray) = CipherKey(
        id = KeyGenerator.fingerprint(bytes),
        label = "t",
        keyBytes = bytes,
        createdAt = 0L,
        lastUsedAt = 0L,
        coverStyle = CoverStyle.RANDOM_CASUAL,
        compatibleChars = emptyList(),
    )

    fun coverWith(envelope: InviteEnvelope): String =
        InvisibleEncoder.embedInCover(InviteEnvelope.serialize(key, envelope.note), "hi there")

    test("extractInvite returns the envelope from a cover message") {
        val env = MessageProcessor.extractInvite(coverWith(InviteEnvelope(key, "hey")))
        env.shouldNotBeNull()
        env.note shouldBe "hey"
        env.keyBytes shouldBe key
    }

    test("extractInvite returns envelope from bare Base64 payload (no cover)") {
        val b64 = java.util.Base64.getEncoder()
            .encodeToString(InviteEnvelope.serialize(key, "n"))
        MessageProcessor.extractInvite(b64).shouldNotBeNull()
    }

    test("extractInvite is null for plain text") {
        MessageProcessor.extractInvite("just a normal sentence").shouldBeNull()
    }

    test("extractInvite is null for an ordinary encrypted message") {
        val enc = MessageProcessor.encryptAndEmbed("secret", "cover", testKey(key))
        MessageProcessor.extractInvite((enc as MessageProcessor.EncryptResult.Success).finalMessage)
            .shouldBeNull()
    }

    test("detectAndDecrypt returns KeyInvite for an invite message") {
        val res = MessageProcessor.detectAndDecrypt(coverWith(InviteEnvelope(key, "bob")), emptyList())
        res.shouldBeInstanceOf<MessageProcessor.DecryptResult.KeyInvite>()
        res.envelope.note shouldBe "bob"
    }

    test("detectAndDecrypt still returns NotEncrypted for plain text") {
        MessageProcessor.detectAndDecrypt("hello world", emptyList())
            .shouldBeInstanceOf<MessageProcessor.DecryptResult.NotEncrypted>()
    }
})
