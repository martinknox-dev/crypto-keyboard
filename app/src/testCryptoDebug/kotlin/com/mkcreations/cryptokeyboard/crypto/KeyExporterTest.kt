package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class KeyExporterTest : FunSpec({

    fun sampleKey(): CipherKey = CipherKey(
        id = KeyGenerator.fingerprint(KeyGenerator.generateKey()),
        label = "Alice",
        keyBytes = KeyGenerator.generateKey(),
        createdAt = 1_700_000_000_000L,
        lastUsedAt = 0L,
        coverStyle = CoverStyle.CONTEXTUAL,
        compatibleChars = emptyList()
    )

    test("export/import round trip preserves the key") {
        val key = sampleKey()
        val export = KeyExporter.exportKey(key, "correct horse battery staple")
        export.shouldNotBeNull()

        val imported = KeyExporter.importKey(export!!, "correct horse battery staple")
        imported.shouldNotBeNull()
        imported!!.keyBytes.contentEquals(key.keyBytes).shouldBeTrue()
        imported.label shouldBe "Alice"
        imported.id shouldBe key.id
        imported.coverStyle shouldBe CoverStyle.CONTEXTUAL
    }

    test("export stores the PBKDF2 iteration count for forward compatibility") {
        val export = KeyExporter.exportKey(sampleKey(), "pw").shouldNotBeNull()
        val json = JSONObject(export)
        json.getInt("iter") shouldBe 600_000
    }

    test("wrong password fails to import") {
        val export = KeyExporter.exportKey(sampleKey(), "right password").shouldNotBeNull()
        KeyExporter.importKey(export, "wrong password").shouldBeNull()
    }

    test("imports legacy exports that have no iter field (100k iterations)") {
        val export = buildLegacyExport("legacy pw", iterations = null)
        val imported = KeyExporter.importKey(export, "legacy pw")
        imported.shouldNotBeNull()
        imported!!.label shouldBe "Legacy Key"
    }

    test("imports legacy exports with explicit iter=100000") {
        val export = buildLegacyExport("legacy pw", iterations = 100_000)
        val imported = KeyExporter.importKey(export, "legacy pw")
        imported.shouldNotBeNull()
        imported!!.label shouldBe "Legacy Key"
    }

    test("rejects exports with absurd iteration counts") {
        // Build a fast export, then lie about the iteration count in the
        // header — import must reject it before doing any expensive work.
        val export = JSONObject(buildLegacyExport("pw", iterations = 1_000))
        export.put("iter", 999_999_999)
        KeyExporter.importKey(export.toString(), "pw").shouldBeNull()
    }

    test("rejects malformed export files") {
        KeyExporter.importKey("not json", "pw").shouldBeNull()
        KeyExporter.importKey("{\"version\":2}", "pw").shouldBeNull()
        KeyExporter.importKey("{\"version\":1,\"type\":\"other\"}", "pw").shouldBeNull()
    }

    test("export does not leak the key in plaintext") {
        val key = sampleKey()
        val export = KeyExporter.exportKey(key, "pw").shouldNotBeNull()
        export.shouldNotContainKey(key.keyBytes)
    }
})

/**
 * Builds an export file the way the pre-hardening app did (PBKDF2 with an
 * explicit or absent iteration count).
 */
private fun buildLegacyExport(password: String, iterations: Int?): String {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val iters = iterations ?: 100_000
    val spec = PBEKeySpec(password.toCharArray(), salt, iters, 256)
    val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(spec).encoded
    spec.clearPassword()

    val keyBytes = ByteArray(32) { 42 }
    val keyData = JSONObject().apply {
        put("id", "deadbeef")
        put("label", "Legacy Key")
        put("keyBytes", Base64.getEncoder().encodeToString(keyBytes))
        put("createdAt", 1_600_000_000_000L)
        put("coverStyle", "RANDOM_CASUAL")
    }

    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
    val encrypted = cipher.doFinal(keyData.toString().toByteArray(Charsets.UTF_8))

    return JSONObject().apply {
        put("version", 1)
        put("type", "cipher_key_export")
        if (iterations != null) put("iter", iterations)
        put("salt", Base64.getEncoder().encodeToString(salt))
        put("iv", Base64.getEncoder().encodeToString(iv))
        put("data", Base64.getEncoder().encodeToString(encrypted))
    }.toString()
}

private infix fun String.shouldNotContainKey(keyBytes: ByteArray) {
    val b64 = Base64.getEncoder().encodeToString(keyBytes)
    if (this.contains(b64)) {
        throw AssertionError("Export file contains the raw key in plaintext!")
    }
}
