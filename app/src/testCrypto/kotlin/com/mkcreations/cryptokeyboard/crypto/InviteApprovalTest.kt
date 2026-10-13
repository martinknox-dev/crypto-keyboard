package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InviteApprovalTest : FunSpec({
    test("builds a CipherKey whose id is the key fingerprint and label the alias") {
        val key = ByteArray(32) { 7 }
        val env = InviteEnvelope(key, "note")
        val ck = InviteApproval.toCipherKey(env, "Alice")
        ck.label shouldBe "Alice"
        ck.id shouldBe KeyGenerator.fingerprint(key)
        ck.keyBytes shouldBe key
    }
})
