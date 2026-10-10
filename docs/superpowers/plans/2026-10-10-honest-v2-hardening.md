# Honest v2 Crypto Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all 12 verified security findings: protocol v2 (AAD-bound header, no wire fingerprint, trial decryption, v1 fallback), best-effort anti-replay, real PIN/app-lock with PBKDF2-600k and backoff, checksummed recovery phrase, cover sanitization, password-field skipping, panic overlay wiring, cache cap, typed errors, and honest docs.

**Architecture:** Pure-Kotlin crypto core (`app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/`) stays free of `android.*` imports so it is JVM-unit-testable. Android wiring (lock activity, panic, scanner) sits in `storage/`, `ui/`, `accessibility/`, `ime/`. Tests live in the flavor-specific unit test source set `app/src/testCrypto/`.

**Tech Stack:** Kotlin, JCA (AES/GCM, PBKDF2), kotest FunSpec on JUnit Platform, Android Gradle Plugin flavors (`floris`, `crypto`).

**Spec:** `docs/superpowers/specs/2026-10-10-honest-v2-design.md`

## Global Constraints

- Test command for this work: `./gradlew :app:testCryptoDebugUnitTest` (flavor-specific). Full-flavor compile check: `./gradlew :app:assembleCryptoDebug`.
- Files under `crypto/` that are unit-tested must not import `android.*` (android.jar stubs throw in JVM tests). `java.util.Base64` replaces `android.util.Base64` wherever tests touch it (minSdk 26, so it is available on-device too).
- Reflection contract with `app/src/main/.../CryptoBarHook.kt` must keep working: `CryptoInputManager.encryptMessage(String,String)` returning a result whose `Success` has `getFinalMessage()`; `tryDecrypt(String)` whose `Success` has `getSecretMessage()`/`getKeyLabel()`; `EncryptionResult.Error` has `getMessage()`. Do not rename these.
- v1 wire format (received) must remain decryptable forever: `[1 ver][4 fp][12 IV][ct+tag]`, no AAD. Encryption always emits v2: `[1 ver=2][12 IV][ct+tag]`, AAD = the 13 header bytes.
- `CipherKey.id` remains `KeyGenerator.fingerprint(keyBytes)` (local key identifier only; never written to the wire in v2).
- Exact constants: PBKDF2 iterations `600_000`, salt 16 bytes; replay LRU `2_000` entries, TTL 30 days; `DecryptCache` cap `500`; PIN backoff threshold 5 attempts, base 5_000 ms, cap 300_000 ms; app-lock default timeout `300` s; recovery phrase = 32 data words + 1 checksum word (33 total), legacy 32 accepted.
- `keyBytes` are always 32 bytes (AES-256); reject other sizes with a typed error, never an exception leak.
- Write all code and comments in English. Frequent commits, one per task.

## Review Focus

These are the input classes the spec implies but which no single task naturally covers; each has a test in the owning task:

1. **Plain chat text that happens to be Base64-decodable** (e.g. `"test"`, `"ok"`) must return `NotEncrypted`, not an attempted decryption — gate the base64 fallback on `isValidFormat` (Task 2, `MessageProcessorTest.plainTextThatLooksLikeBase64IsNotEncrypted`).
2. **v1 message from an old contact with the wrong key selected** must return `AuthenticationFailed`/`DecryptionFailed` and never crash or leak an exception — construct a real v1 vector in tests (Task 1, `CryptoEngineTest.v1LegacyRoundtrip` + `v1WrongKeyFails`).
3. **Cover sanitization must not mangle ordinary text** — regular ASCII, accents, and non-ZWJ emoji survive byte-for-byte; only the 18 forbidden chars are removed (Task 3, `InvisibleEncoderTest.sanitizeCoverPreservesOrdinaryText`).
4. **Replay guard boundaries**: exactly 2,000 entries then eviction of the least-recently-used, TTL expiry with injected clock, and thread-safe access (Task 2, `ReplayGuardTest`).
5. **App lock with no PIN configured must never lock** (panic-lock with no PIN would brick the keyboard) and timeout ≤ 0 must not create a relock loop (Task 7, `AppLockPolicyTest`).

---

### Task 1: CryptoEngine v2 + typed results + call-site port

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/CryptoEngine.kt` (full rewrite)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/MessageProcessor.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/model/CipherKey.kt` (`android.util.Base64` → `java.util.Base64`)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/CryptoAccessibilityService.kt:252`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ime/CryptoInputManager.kt:89`
- Modify: `app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/CryptoBarHook.kt:693` (size estimate `1 + 4 + 12` → `1 + 12`)
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/CryptoEngineTest.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/MessageProcessorTest.kt`

**Interfaces:**
- Produces: `CryptoEngine.encrypt(plaintext: String, keyBytes: ByteArray): EncryptOutcome` where `EncryptOutcome.Success(payload: ByteArray)` / `EncryptOutcome.Failure(reason: EncryptFailure, cause: Throwable?)`; `CryptoEngine.decryptV2(data, keyBytes): DecryptOutcome`; `CryptoEngine.decryptV1(data, keyBytes): DecryptOutcome`; `DecryptOutcome.Success(plaintext)` / `NotOurFormat` / `AuthenticationFailed` / `Failure(reason, cause)`; `extractFingerprint(data): String?` (v1 only); `isValidFormat(data): Boolean` (v1 or v2).
- Produces: `MessageProcessor.detectAndDecrypt(message: String, keys: List<CipherKey>): DecryptResult` (signature change — Task 2 adds `Replayed`).
- Consumes: `InvisibleEncoder.containsHiddenData/extractFromMessage/embedInCover/extractCoverMessage` (unchanged), `KeyGenerator.fingerprint`.

- [ ] **Step 1: Write failing tests (also verifies the test source-set name)**

Create `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/CryptoEngineTest.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoEngineTest : FunSpec({

    val key = ByteArray(32) { it.toByte() }

    fun legacyEncryptV1(plaintext: String, keyBytes: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val fp = KeyGenerator.fingerprint(keyBytes).chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + 4 + 12 + enc.size)
        out[0] = 1
        fp.copyInto(out, 1)
        iv.copyInto(out, 5)
        enc.copyInto(out, 17)
        return out
    }

    test("v2 roundtrip") {
        val payload = (CryptoEngine.encrypt("hello world", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        payload[0] shouldBe 2
        payload.size shouldBe 13 + "hello world".toByteArray().size + 16
        val out = CryptoEngine.decryptV2(payload, key)
        out.shouldBeInstanceOf<CryptoEngine.DecryptOutcome.Success>()
        out.plaintext shouldBe "hello world"
    }

    test("v2 encrypt rejects wrong key size") {
        CryptoEngine.encrypt("x", ByteArray(16))
            .shouldBeInstanceOf<CryptoEngine.EncryptOutcome.Failure>()
            .reason shouldBe CryptoEngine.EncryptFailure.INVALID_KEY_SIZE
    }

    test("v2 tampered IV is rejected by AAD/tag") {
        val payload = (CryptoEngine.encrypt("secret", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        payload[1] = (payload[1].toInt() xor 0x01).toByte()
        CryptoEngine.decryptV2(payload, key)
            .shouldBeInstanceOf<CryptoEngine.DecryptOutcome.AuthenticationFailed>()
    }

    test("v2 tampered ciphertext is rejected") {
        val payload = (CryptoEngine.encrypt("secret", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        CryptoEngine.decryptV2(payload, key)
            .shouldBeInstanceOf<CryptoEngine.DecryptOutcome.AuthenticationFailed>()
    }

    test("v2 wrong key fails authentication") {
        val other = ByteArray(32) { (it + 1).toByte() }
        val payload = (CryptoEngine.encrypt("secret", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        CryptoEngine.decryptV2(payload, other)
            .shouldBeInstanceOf<CryptoEngine.DecryptOutcome.AuthenticationFailed>()
    }

    test("v1 legacy roundtrip") {
        val payload = legacyEncryptV1("old friend says hi", key)
        CryptoEngine.isValidFormat(payload) shouldBe true
        CryptoEngine.extractFingerprint(payload) shouldBe KeyGenerator.fingerprint(key)
        val out = CryptoEngine.decryptV1(payload, key)
        out.shouldBeInstanceOf<CryptoEngine.DecryptOutcome.Success>()
        out.plaintext shouldBe "old friend says hi"
    }

    test("v1 wrong key fails authentication") {
        val other = ByteArray(32) { (it + 7).toByte() }
        val payload = legacyEncryptV1("old", key)
        CryptoEngine.decryptV1(payload, other)
            .shouldBeInstanceOf<CryptoEngine.DecryptOutcome.AuthenticationFailed>()
    }

    test("garbage is not a valid format") {
        CryptoEngine.isValidFormat(ByteArray(5) { 0x42 }) shouldBe false
        CryptoEngine.isValidFormat(ByteArray(40) { 0x42 }) shouldBe false // version 0x42 unknown
        CryptoEngine.extractFingerprint(ByteArray(40) { 0x42 }) shouldBe null
    }
})
```

Create `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/MessageProcessorTest.kt`:

```kotlin
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
})
```

- [ ] **Step 2: Run tests to verify they fail and the source set exists**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*CryptoEngineTest*'`
Expected: either FAIL (API does not exist yet) — if the task does not exist or reports `No tests found`, the source-set name is wrong: check `./gradlew :app:sourceSets | grep -i test` and move the files to the flavor unit-test source set AGP reports, then re-run.

- [ ] **Step 3: Rewrite CryptoEngine with v2 + AAD + typed results**

Replace `CryptoEngine.kt` with:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM message encryption.
 *
 * v2 wire format: [1 byte version=2][12 byte IV][ciphertext + 16 byte tag]
 * AAD = the 13 header bytes (version + IV), so header tampering fails auth.
 *
 * v1 wire format (legacy receive only): [1 byte version=1][4 byte fingerprint]
 * [12 byte IV][ciphertext + tag], no AAD. The fingerprint identifies the key.
 */
object CryptoEngine {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 16
    private const val KEY_SIZE = 32

    const val VERSION_V1: Byte = 1
    const val VERSION_V2: Byte = 2
    private const val V1_HEADER_SIZE = 1 + 4 + IV_SIZE   // version + fingerprint + IV
    private const val V2_HEADER_SIZE = 1 + IV_SIZE       // version + IV

    private val secureRandom = SecureRandom()

    sealed interface EncryptOutcome {
        data class Success(val payload: ByteArray) : EncryptOutcome {
            override fun equals(other: Any?): Boolean =
                other is Success && payload.contentEquals(other.payload)
            override fun hashCode(): Int = payload.contentHashCode()
        }
        data class Failure(val reason: EncryptFailure, val cause: Throwable? = null) : EncryptOutcome
    }

    enum class EncryptFailure { INVALID_KEY_SIZE, CIPHER_ERROR }

    sealed interface DecryptOutcome {
        data class Success(val plaintext: String) : DecryptOutcome
        object NotOurFormat : DecryptOutcome
        object AuthenticationFailed : DecryptOutcome
        data class Failure(val reason: String, val cause: Throwable? = null) : DecryptOutcome
    }

    fun encrypt(plaintext: String, keyBytes: ByteArray): EncryptOutcome {
        if (keyBytes.size != KEY_SIZE) {
            return EncryptOutcome.Failure(EncryptFailure.INVALID_KEY_SIZE)
        }
        return try {
            val iv = ByteArray(IV_SIZE).also { secureRandom.nextBytes(it) }
            val header = ByteArray(V2_HEADER_SIZE)
            header[0] = VERSION_V2
            iv.copyInto(header, 1)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val payload = ByteArray(V2_HEADER_SIZE + encrypted.size)
            header.copyInto(payload, 0)
            encrypted.copyInto(payload, V2_HEADER_SIZE)
            EncryptOutcome.Success(payload)
        } catch (e: Exception) {
            EncryptOutcome.Failure(EncryptFailure.CIPHER_ERROR, e)
        }
    }

    fun decryptV2(data: ByteArray, keyBytes: ByteArray): DecryptOutcome {
        if (data.size < V2_HEADER_SIZE + TAG_SIZE) return DecryptOutcome.NotOurFormat
        if (data[0] != VERSION_V2) return DecryptOutcome.NotOurFormat
        return try {
            val header = data.copyOfRange(0, V2_HEADER_SIZE)
            val iv = header.copyOfRange(1, V2_HEADER_SIZE)
            val ciphertext = data.copyOfRange(V2_HEADER_SIZE, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(header)
            DecryptOutcome.Success(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            DecryptOutcome.AuthenticationFailed
        } catch (e: Exception) {
            DecryptOutcome.Failure("v2 decryption error", e)
        }
    }

    fun decryptV1(data: ByteArray, keyBytes: ByteArray): DecryptOutcome {
        if (data.size < V1_HEADER_SIZE + TAG_SIZE) return DecryptOutcome.NotOurFormat
        if (data[0] != VERSION_V1) return DecryptOutcome.NotOurFormat
        return try {
            val iv = data.copyOfRange(5, 5 + IV_SIZE)
            val ciphertext = data.copyOfRange(V1_HEADER_SIZE, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            DecryptOutcome.Success(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            DecryptOutcome.AuthenticationFailed
        } catch (e: Exception) {
            DecryptOutcome.Failure("v1 decryption error", e)
        }
    }

    /** Only meaningful for v1 payloads; null for v2 or unknown data. */
    fun extractFingerprint(data: ByteArray): String? {
        if (data.size < 5 || data[0] != VERSION_V1) return null
        return (1..4).joinToString("") { "%02x".format(data[it]) }
    }

    fun isValidFormat(data: ByteArray): Boolean = when {
        data.isEmpty() -> false
        data[0] == VERSION_V2 && data.size >= V2_HEADER_SIZE + TAG_SIZE -> true
        data[0] == VERSION_V1 && data.size >= V1_HEADER_SIZE + TAG_SIZE -> true
        else -> false
    }
}
```

- [ ] **Step 4: Port MessageProcessor to the new APIs**

`MessageProcessor.encryptAndEmbed`: replace the encrypt call with:

```kotlin
val encrypted = when (val out = CryptoEngine.encrypt(secretMessage, key.keyBytes)) {
    is CryptoEngine.EncryptOutcome.Success -> out.payload
    is CryptoEngine.EncryptOutcome.Failure -> return EncryptResult.Error(
        when (out.reason) {
            CryptoEngine.EncryptFailure.INVALID_KEY_SIZE -> "Key has invalid size"
            CryptoEngine.EncryptFailure.CIPHER_ERROR -> "Encryption failed"
        }
    )
}
```

Replace the NO_COVER `android.util.Base64.encodeToString(...)` line with `java.util.Base64.getEncoder().encodeToString(encrypted)` and drop the `android.util` import.

Change the signature to `fun detectAndDecrypt(message: String, keys: List<CipherKey>): DecryptResult` and replace the validate/fingerprint/decrypt block with:

```kotlin
if (!InvisibleEncoder.containsHiddenData(message)) {
    return DecryptResult.NotEncrypted
}
val encryptedBytes = when (val ex = InvisibleEncoder.extractFromMessage(message)) {
    is InvisibleEncoder.ExtractOutcome.Success -> ex.data
    else -> return DecryptResult.CorruptedData
}
if (!CryptoEngine.isValidFormat(encryptedBytes)) {
    return DecryptResult.CorruptedData
}
return decryptPayload(encryptedBytes, keys)
```

Add the helper (replay/base64 extensions come in Task 2):

```kotlin
private fun decryptPayload(payload: ByteArray, keys: List<CipherKey>): DecryptResult {
    return when (payload[0]) {
        CryptoEngine.VERSION_V2 -> {
            for (candidate in keys) {
                when (val out = CryptoEngine.decryptV2(payload, candidate.keyBytes)) {
                    is CryptoEngine.DecryptOutcome.Success ->
                        return DecryptResult.Success(out.plaintext, "", candidate.label, candidate.id)
                    else -> continue
                }
            }
            DecryptResult.DecryptionFailed
        }
        CryptoEngine.VERSION_V1 -> {
            val fingerprint = CryptoEngine.extractFingerprint(payload)
                ?: return DecryptResult.CorruptedData
            val key = keys.find { it.id == fingerprint }
                ?: return DecryptResult.NoMatchingKey(fingerprint)
            when (val out = CryptoEngine.decryptV1(payload, key.keyBytes)) {
                is CryptoEngine.DecryptOutcome.Success ->
                    DecryptResult.Success(out.plaintext, "", key.label, key.id)
                else -> DecryptResult.DecryptionFailed
            }
        }
        else -> DecryptResult.CorruptedData
    }
}
```

Note: this step requires `InvisibleEncoder.extractFromMessage` to return `ExtractOutcome` — Task 3 defines it. To keep this task compiling, first make the **minimal** change to `InvisibleEncoder.extractFromMessage`: keep its current nullable signature for now and write the `when` as:

```kotlin
val encryptedBytes = InvisibleEncoder.extractFromMessage(message)
    ?: return DecryptResult.CorruptedData
```

Task 3 converts it to the typed result. Also restore the `extractCoverMessage` call for the `coverMessage` field of `Success` in the embedded path (pass the cover through `decryptPayload` by capturing it in `detectAndDecrypt` and mapping `decryptPayload`'s result: simplest is to have `decryptPayload` return `Success(plaintext, keyLabel, keyId)`-style data — implement as: `detectAndDecrypt` calls `decryptPayload`, and on `Success` rebuilds with `coverMessage = InvisibleEncoder.extractCoverMessage(message)`).

- [ ] **Step 5: Update call sites**

`CryptoAccessibilityService.kt:252`:

```kotlin
when (val result = MessageProcessor.detectAndDecrypt(node.fullText, store.getAllKeys())) {
```

(keep the existing exhaustive `when` branches unchanged for now).

`CryptoInputManager.kt:89`:

```kotlin
return when (val result = MessageProcessor.detectAndDecrypt(message, keyStore.getAllKeys())) {
```

`CipherKey.kt`: replace `android.util.Base64.encodeToString(key.keyBytes, android.util.Base64.NO_WRAP)` with `java.util.Base64.getEncoder().encodeToString(key.keyBytes)` and the decode line with `java.util.Base64.getDecoder().decode(json.getString("keyBytes"))`; fix imports.

`CryptoBarHook.kt:693`: `val estimatedSize = ... else 1 + 12 + secretMessage.toByteArray(Charsets.UTF_8).size + 16`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testCryptoDebugUnitTest`
Expected: PASS (all CryptoEngineTest + MessageProcessorTest tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/crypto app/src/testCrypto app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/CryptoBarHook.kt
git commit -m "feat(crypto): protocol v2 with AAD-bound header, trial decryption, typed results"
```

---

### Task 2: ReplayGuard + NO_COVER base64 decode path

**Files:**
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/ReplayGuard.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/MessageProcessor.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/CryptoAccessibilityService.kt` (`processNode` pre-check + `when` branch)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ime/CryptoInputManager.kt` (`when` branch)
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/ReplayGuardTest.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/MessageProcessorTest.kt` (additions)

**Interfaces:**
- Consumes: Task 1 `decryptPayload`, `DecryptResult`.
- Produces: `ReplayGuard(maxEntries: Int = 2000, ttlMs: Long = 30 days, clock: () -> Long = System::currentTimeMillis)` with `isReplay(payload: ByteArray): Boolean`, `markSeen(payload: ByteArray)`, `clear()`; `ReplayGuard.shared: ReplayGuard`; `MessageProcessor.isKnownReplay(message: String): Boolean`; `DecryptResult.Replayed` object.

- [ ] **Step 1: Write failing tests**

Add to `ReplayGuardTest.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReplayGuardTest : FunSpec({

    test("first sight is not a replay, second is") {
        val guard = ReplayGuard()
        val payload = "payload-bytes".toByteArray()
        guard.isReplay(payload) shouldBe false
        guard.markSeen(payload)
        guard.isReplay(payload) shouldBe true
    }

    test("different payloads are independent") {
        val guard = ReplayGuard()
        guard.markSeen("a".toByteArray())
        guard.isReplay("b".toByteArray()) shouldBe false
    }

    test("LRU cap evicts oldest beyond 2000") {
        val guard = ReplayGuard(maxEntries = 3)
        guard.markSeen("1".toByteArray())
        guard.markSeen("2".toByteArray())
        guard.markSeen("3".toByteArray())
        guard.markSeen("4".toByteArray()) // evicts "1"
        guard.isReplay("1".toByteArray()) shouldBe false
        guard.isReplay("4".toByteArray()) shouldBe true
    }

    test("entries expire after TTL") {
        var now = 0L
        val guard = ReplayGuard(ttlMs = 1_000L, clock = { now })
        guard.markSeen("old".toByteArray())
        now = 999L
        guard.isReplay("old".toByteArray()) shouldBe true
        now = 1_001L
        guard.isReplay("old".toByteArray()) shouldBe false
    }

    test("clear resets state") {
        val guard = ReplayGuard()
        guard.markSeen("x".toByteArray())
        guard.clear()
        guard.isReplay("x".toByteArray()) shouldBe false
    }
})
```

Add to `MessageProcessorTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*ReplayGuardTest*'`
Expected: FAIL — `ReplayGuard` does not exist.

- [ ] **Step 3: Implement ReplayGuard**

Create `ReplayGuard.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest

/**
 * Best-effort, per-device deduplication of verbatim message re-sends.
 * Bounded LRU with TTL. In-memory only: a restart or cache clear forgets
 * history (documented in SECURITY.md). This is NOT a session ratchet.
 */
class ReplayGuard(
    private val maxEntries: Int = 2_000,
    private val ttlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val seenAt: Long)

    // accessOrder=true gives LRU behavior on get
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun isReplay(payload: ByteArray): Boolean {
        val hash = hash(payload)
        val entry = entries[hash] ?: return false
        if (clock() - entry.seenAt > ttlMs) {
            entries.remove(hash)
            return false
        }
        return true
    }

    @Synchronized
    fun markSeen(payload: ByteArray) {
        val hash = hash(payload)
        entries[hash] = Entry(clock())
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    private fun hash(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }

    companion object {
        val shared = ReplayGuard()
    }
}
```

- [ ] **Step 4: Wire replay + base64 into MessageProcessor**

Add `object Replayed` to `DecryptResult`. Add to `detectAndDecrypt`, replacing the `NotEncrypted` early return:

```kotlin
if (!InvisibleEncoder.containsHiddenData(message)) {
    // NO_COVER path: message may be a raw base64 payload
    val raw = try {
        java.util.Base64.getDecoder().decode(message.trim())
    } catch (e: IllegalArgumentException) {
        return DecryptResult.NotEncrypted
    }
    if (!CryptoEngine.isValidFormat(raw)) return DecryptResult.NotEncrypted
    return decryptAndTrack(raw, keys, coverMessage = "")
}
val encryptedBytes = InvisibleEncoder.extractFromMessage(message)
    ?: return DecryptResult.CorruptedData
if (!CryptoEngine.isValidFormat(encryptedBytes)) return DecryptResult.CorruptedData
return decryptAndTrack(encryptedBytes, keys, InvisibleEncoder.extractCoverMessage(message))
```

Add:

```kotlin
private fun decryptAndTrack(payload: ByteArray, keys: List<CipherKey>, coverMessage: String): DecryptResult {
    if (ReplayGuard.shared.isReplay(payload)) return DecryptResult.Replayed
    return when (val result = decryptPayload(payload, keys)) {
        is DecryptResult.Success -> {
            ReplayGuard.shared.markSeen(payload)
            result.copy(coverMessage = coverMessage)
        }
        else -> result
    }
}

/** Pre-check used before the decrypt cache so replays cannot surface via cache hits. */
fun isKnownReplay(message: String): Boolean {
    val payload = if (InvisibleEncoder.containsHiddenData(message)) {
        InvisibleEncoder.extractFromMessage(message) ?: return false
    } else {
        try {
            java.util.Base64.getDecoder().decode(message.trim())
        } catch (e: IllegalArgumentException) {
            return false
        }
    }
    return ReplayGuard.shared.isReplay(payload)
}
```

and have `decryptPayload` build `Success(plaintext, coverMessage = "", keyLabel, keyId)` — `decryptAndTrack` overwrites the cover.

- [ ] **Step 5: Update consumers**

`CryptoAccessibilityService.processNode`: before the `DecryptCache.get(node.fullText)` block add:

```kotlin
if (MessageProcessor.isKnownReplay(node.fullText)) {
    return
}
```

Add to its `when`: `is MessageProcessor.DecryptResult.Replayed -> {}`.

`CryptoInputManager.tryDecrypt` `when`: add

```kotlin
is MessageProcessor.DecryptResult.Replayed -> {
    DecryptionResult.Error("Duplicate message rejected (replay)")
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testCryptoDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/crypto app/src/testCrypto
git commit -m "feat(crypto): receiver-side anti-replay guard and NO_COVER base64 decode path"
```

---

### Task 3: Cover sanitization + typed extraction + stats fix

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/InvisibleEncoder.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/MessageProcessor.kt` (map typed extract, sanitize before stats)
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/InvisibleEncoderTest.kt`

**Interfaces:**
- Produces: `InvisibleEncoder.sanitizeCover(text: String): String`; `InvisibleEncoder.extractFromMessage(message): ExtractOutcome` with `Success(data: ByteArray)`, `NotPresent`, `Malformed`.
- Consumes: Task 1/2 `MessageProcessor` internals.

- [ ] **Step 1: Write failing tests**

Create `InvisibleEncoderTest.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class InvisibleEncoderTest : FunSpec({

    test("sanitizeCover strips every encoding and marker character") {
        val dirty = "ok\u200B\u200C\u200D\u00AD\u2060\u2068\u2069\uFEFF done"
        InvisibleEncoder.sanitizeCover(dirty) shouldBe "ok done"
    }

    test("sanitizeCover preserves ordinary text and emoji") {
        val cover = "sounds good 👍 — café 100%"
        InvisibleEncoder.sanitizeCover(cover) shouldBe cover
    }

    test("embedded payload with dirty cover still decrypts") {
        val key = ByteArray(32) { it.toByte() }
        val payload = (CryptoEngine.encrypt("secret", key)
            as CryptoEngine.EncryptOutcome.Success).payload
        val dirtyCover = "ok\u200B please"
        val message = InvisibleEncoder.embedInCover(payload, dirtyCover)
        // Sanitization happened at embed time: extract must reproduce bytes exactly
        val extracted = InvisibleEncoder.extractFromMessage(message)
        extracted.shouldBeInstanceOf<InvisibleEncoder.ExtractOutcome.Success>()
        extracted.data.contentEquals(payload) shouldBe true
        InvisibleEncoder.extractCoverMessage(message) shouldBe "ok please"
    }

    test("message without signature yields NotPresent") {
        InvisibleEncoder.extractFromMessage("hello world")
            .shouldBeInstanceOf<InvisibleEncoder.ExtractOutcome.NotPresent>()
    }

    test("odd number of invisible chars yields Malformed") {
        val sig = "\u2069\u2068\u2069\u2068"
        val end = "\u2068\u2069\u2068\u2069"
        InvisibleEncoder.extractFromMessage("a${sig}\u200B\u200C\u200D$end")
            .shouldBeInstanceOf<InvisibleEncoder.ExtractOutcome.Malformed>()
    }

    test("stats use the v2 header size") {
        val stats = InvisibleEncoder.getEncodingStats("ab", "ok")
        stats.estimatedEncryptedBytes shouldBe 1 + 12 + 2 + 16
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*InvisibleEncoderTest*'`
Expected: FAIL — `sanitizeCover` / `ExtractOutcome` do not exist.

- [ ] **Step 3: Implement sanitization + typed extraction**

In `InvisibleEncoder.kt` add:

```kotlin
/** All characters that would corrupt payload extraction (encoding alphabet + markers). */
private val FORBIDDEN_COVER_CHARS: Set<Char> =
    INVISIBLE_CHARS.toSet() + '\u2068' + '\u2069'

fun sanitizeCover(text: String): String =
    text.filter { it !in FORBIDDEN_COVER_CHARS }

sealed interface ExtractOutcome {
    data class Success(val data: ByteArray) : ExtractOutcome {
        override fun equals(other: Any?) = other is Success && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode()
    }
    object NotPresent : ExtractOutcome
    object Malformed : ExtractOutcome
}
```

Change `extractFromMessage` to return `ExtractOutcome`: no signature → `NotPresent`; no/empty end marker → `Malformed`; empty or odd-length invisible run → `Malformed`; otherwise `Success(bytes)`.

Change `embedInCover` first line to `val cover = sanitizeCover(coverMessage)` and use `cover` everywhere in place of `coverMessage` (including the empty-cover branch and the catch fallback). In `getEncodingStats`, change `1 + 4 + 12` to `1 + 12`.

- [ ] **Step 4: Map the typed result in MessageProcessor**

In `detectAndDecrypt`:

```kotlin
val encryptedBytes = when (val ex = InvisibleEncoder.extractFromMessage(message)) {
    is InvisibleEncoder.ExtractOutcome.Success -> ex.data
    InvisibleEncoder.ExtractOutcome.NotPresent, InvisibleEncoder.ExtractOutcome.Malformed ->
        return DecryptResult.CorruptedData
}
```

In `encryptAndEmbed`, sanitize the cover before computing stats:

```kotlin
val finalCover = InvisibleEncoder.sanitizeCover(
    /* the existing cover-determination expression */
)
```

and return that sanitized value in `Success.coverMessage`.

Update `isKnownReplay`'s extraction to the same `when` (`NotPresent`/`Malformed` → `return false`).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testCryptoDebugUnitTest`
Expected: PASS (all suites).

- [ ] **Step 6: Commit**

```bash
git add app/src/crypto app/src/testCrypto
git commit -m "fix(crypto): sanitize cover text and type steganographic extraction results"
```

---

### Task 4: Recovery-phrase checksum word

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/KeyGenerator.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyManagementScreen.kt` (import dialog ~lines 655-695, phrase dialog label ~line 458)
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/KeyGeneratorTest.kt`

**Interfaces:**
- Produces: `KeyGenerator.keyToWordPhrase(keyBytes): List<String>` now returns **33** words (32 data + 1 checksum); `KeyGenerator.checksumWord(keyBytes): String`; `KeyGenerator.wordPhraseToKey(words): ByteArray?` accepts 33 (checksum validated) or 32 (legacy, no validation), rejects anything else.
- Consumes: existing `WORD_LIST` (256 entries), `KeyGenerator.fingerprint`.

- [ ] **Step 1: Write failing tests**

Create `KeyGeneratorTest.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.collections.shouldHaveSize

class KeyGeneratorTest : FunSpec({

    val key = ByteArray(32) { (it * 5).toByte() }

    test("phrase is 33 words and roundtrips") {
        val words = KeyGenerator.keyToWordPhrase(key)
        words shouldHaveSize 33
        KeyGenerator.wordPhraseToKey(words)!!.contentEquals(key) shouldBe true
    }

    test("checksum word matches checksumWord()") {
        val words = KeyGenerator.keyToWordPhrase(key)
        words[32] shouldBe KeyGenerator.checksumWord(key)
    }

    test("single data word typo is detected") {
        val words = KeyGenerator.keyToWordPhrase(key).toMutableList()
        words[0] = if (words[0] == "anchor") "apple" else "anchor"
        KeyGenerator.wordPhraseToKey(words).shouldBeNull()
    }

    test("tampered checksum word is detected") {
        val words = KeyGenerator.keyToWordPhrase(key).toMutableList()
        words[32] = if (words[32] == "anchor") "apple" else "anchor"
        KeyGenerator.wordPhraseToKey(words).shouldBeNull()
    }

    test("legacy 32-word phrase still imports") {
        val words = KeyGenerator.keyToWordPhrase(key).take(32)
        KeyGenerator.wordPhraseToKey(words)!!.contentEquals(key) shouldBe true
    }

    test("wrong length is rejected") {
        KeyGenerator.wordPhraseToKey(KeyGenerator.keyToWordPhrase(key).take(31)).shouldBeNull()
        KeyGenerator.wordPhraseToKey(KeyGenerator.keyToWordPhrase(key) + "anchor" + "apple").shouldBeNull()
    }

    test("unknown word is rejected") {
        val words = KeyGenerator.keyToWordPhrase(key).toMutableList()
        words[5] = "notawordinthelist"
        KeyGenerator.wordPhraseToKey(words).shouldBeNull()
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*KeyGeneratorTest*'`
Expected: FAIL (phrase is currently 32 words; no `checksumWord`).

- [ ] **Step 3: Implement the checksum**

In `KeyGenerator.kt`:

```kotlin
/** Checksum word derived from SHA-256(key)[0]; appended as the 33rd word. */
fun checksumWord(keyBytes: ByteArray): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(keyBytes)
    return WORD_LIST[hash[0].toInt() and 0xFF]
}
```

Change `keyToWordPhrase` to append: `words.add(checksumWord(keyBytes))` before returning.

Replace `wordPhraseToKey` with:

```kotlin
fun wordPhraseToKey(words: List<String>): ByteArray? {
    val dataWords = when (words.size) {
        33 -> words.take(32)
        32 -> words // legacy phrase without checksum
        else -> return null
    }
    val bytes = ByteArray(32)
    for (i in dataWords.indices) {
        val index = WORD_LIST.indexOf(dataWords[i].lowercase().trim())
        if (index < 0) return null
        bytes[i] = index.toByte()
    }
    if (words.size == 33 && words[32].lowercase().trim() != checksumWord(bytes)) {
        return null // checksum mismatch — a word is mistyped
    }
    return bytes
}
```

- [ ] **Step 4: Update the import/export UI copy**

`KeyManagementScreen.kt` import dialog (~line 656): label `Text("Paste 33 words here (32 legacy also accepted)")`. Size check (~line 674):

```kotlin
if (words.size != 32 && words.size != 33) {
    Toast.makeText(
        context,
        "Expected 33 words (or 32 for legacy phrases), got ${words.size}. Check and try again.",
        Toast.LENGTH_LONG
    ).show()
    return@launch
}
```

Null-message (~line 686): `"Invalid phrase — an unknown word or a failed checksum (a word is likely mistyped)."`.

Phrase display dialog (`showPhraseDialog = PhraseDialogData(...)` at ~line 458): no data change needed (it now carries 33 words), but update any dialog title/caption text mentioning "32" to "33 words (last word is the checksum)" — search `KeyManagementScreen.kt` for `"32"` and update user-visible strings.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testCryptoDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/crypto app/src/testCrypto
git commit -m "feat(crypto): checksum word for recovery phrases, typo detection on import"
```

---

### Task 5: DecryptCache LRU cap

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/DecryptCache.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/DecryptCacheTest.kt`

**Interfaces:**
- Consumes: existing call sites `DecryptCache.put/get/isCached/clearAll/cleanExpired/size/setCacheDuration` (public API unchanged).
- Produces: internal `DecryptCacheStore(maxEntries: Int = 500, durationMs: Long, clock: () -> Long)` for direct testing.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class DecryptCacheTest : FunSpec({

    test("entries beyond cap evict the least recently used") {
        val store = DecryptCacheStore(maxEntries = 2)
        store.put("m1", "s1", "k")
        store.put("m2", "s2", "k")
        store.get("m1") // access m1 so m2 becomes LRU
        store.put("m3", "s3", "k")
        store.get("m2").shouldBeNull()
        store.get("m1")!!.secretMessage shouldBe "s1"
        store.get("m3")!!.secretMessage shouldBe "s3"
    }

    test("entries expire after duration") {
        var now = 0L
        val store = DecryptCacheStore(maxEntries = 10, durationMs = 1_000L, clock = { now })
        store.put("m", "s", "k")
        now = 999L
        store.get("m")!!.secretMessage shouldBe "s"
        now = 1_001L
        store.get("m").shouldBeNull()
    }

    test("cleanExpired removes stale entries only") {
        var now = 0L
        val store = DecryptCacheStore(maxEntries = 10, durationMs = 1_000L, clock = { now })
        store.put("old", "a", "k")
        now = 2_000L
        store.put("new", "b", "k")
        store.cleanExpired()
        store.size() shouldBe 1
        store.get("new")!!.secretMessage shouldBe "b"
    }

    test("clearAll empties the cache") {
        val store = DecryptCacheStore()
        store.put("m", "s", "k")
        store.clearAll()
        store.size() shouldBe 0
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*DecryptCacheTest*'`
Expected: FAIL — `DecryptCacheStore` does not exist.

- [ ] **Step 3: Implement capped store + delegate**

Rewrite `DecryptCache.kt`: define `class DecryptCacheStore(private val maxEntries: Int = 500, durationMs: Long = 5 * 60 * 1000L, private val clock: () -> Long = System::currentTimeMillis)` holding a `LinkedHashMap<String, CacheEntry>(16, 0.75f, true)` guarded by `@Synchronized` methods `put/get/isCached/clearAll/cleanExpired/size/setDuration`. On `put`, after insertion `while (map.size > maxEntries)` remove the first (eldest) entry via iterator. `get` checks expiry like today.

Keep `object DecryptCache` as a thin delegate over `private val store = DecryptCacheStore()` with the exact existing public functions (its `put/get/isCached/clearAll/cleanExpired/size/setCacheDuration` signatures unchanged), preserving the existing doc comments and adding a line: "Capped at 500 entries, LRU eviction."

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testCryptoDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/crypto app/src/testCrypto
git commit -m "fix(crypto): cap DecryptCache at 500 entries with LRU eviction"
```

---

### Task 6: PinHasher PBKDF2-600k + rate limiter

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/PinHasher.kt` (rewrite)
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/PinRateLimiter.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/PinHasherTest.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/PinRateLimiterTest.kt`

**Interfaces:**
- Produces: `PinHasher.hashPin(pin: String): String` → `"pbkdf2$600000$<b64salt>$<b64hash>"`; `PinHasher.verifyPin(pin, stored): Boolean` (constant-time, false on malformed).
- Produces: `PinRateLimiter(state: State, maxAttempts: Int = 5, baseDelayMs: Long = 5_000, maxDelayMs: Long = 300_000)` with `interface State { fun attempts(): Int; fun setAttempts(n: Int); fun lockoutUntil(): Long; fun setLockoutUntil(t: Long) }`; methods `retryDelayMs(now: Long): Long`, `recordFailure(now: Long)`, `recordSuccess()`.

- [ ] **Step 1: Write failing tests**

`PinHasherTest.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PinHasherTest : FunSpec({

    test("hash uses PBKDF2 format with 600k iterations") {
        val prefix = "pbkdf2" + '$' + "600000" + '$'
        PinHasher.hashPin("1234") shouldStartWith prefix
    }

    test("verify accepts correct PIN") {
        PinHasher.verifyPin("4321", PinHasher.hashPin("4321")) shouldBe true
    }

    test("verify rejects wrong PIN") {
        PinHasher.verifyPin("9999", PinHasher.hashPin("4321")) shouldBe false
    }

    test("verify rejects malformed stored value") {
        PinHasher.verifyPin("x", "not-a-hash") shouldBe false
        PinHasher.verifyPin("x", "") shouldBe false
        val badFormat = "pbkdf2" + '$' + "abc" + '$' + "c2FsdA==" + '$' + "aGFzaA=="
        PinHasher.verifyPin("x", badFormat) shouldBe false // iterations not a number / too low
    }

    test("salts are unique per hash") {
        PinHasher.hashPin("same") != PinHasher.hashPin("same") shouldBe true
    }
})
```

(with `io.kotest.matchers.string.shouldStartWith` imported.)

`PinRateLimiterTest.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PinRateLimiterTest : FunSpec({

    class MemState : PinRateLimiter.State {
        var attempts = 0
        var lockout = 0L
        override fun attempts() = attempts
        override fun setAttempts(n: Int) { attempts = n }
        override fun lockoutUntil() = lockout
        override fun setLockoutUntil(t: Long) { lockout = t }
    }

    test("first four failures do not delay") {
        val state = MemState()
        val limiter = PinRateLimiter(state)
        repeat(4) {
            limiter.recordFailure(now = 0L)
            limiter.retryDelayMs(now = 0L) shouldBe 0L
        }
    }

    test("fifth failure starts 5s backoff") {
        val state = MemState()
        val limiter = PinRateLimiter(state)
        repeat(5) { limiter.recordFailure(now = 1_000L) }
        limiter.retryDelayMs(now = 1_000L) shouldBe 5_000L
        limiter.retryDelayMs(now = 3_000L) shouldBe 3_000L // remaining
        limiter.retryDelayMs(now = 7_000L) shouldBe 0L     // expired
    }

    test("backoff doubles then caps at 5 minutes") {
        val state = MemState()
        val limiter = PinRateLimiter(state)
        repeat(5) { limiter.recordFailure(now = 0L) }  // 5s
        limiter.recordFailure(now = 0L)                 // 6th → 10s
        limiter.retryDelayMs(now = 0L) shouldBe 10_000L
        repeat(20) { limiter.recordFailure(now = 0L) }  // keep doubling
        limiter.retryDelayMs(now = 0L) shouldBe 300_000L
    }

    test("successful unlock resets backoff") {
        val state = MemState()
        val limiter = PinRateLimiter(state)
        repeat(7) { limiter.recordFailure(now = 0L) }
        limiter.recordSuccess()
        state.attempts shouldBe 0
        limiter.retryDelayMs(now = 0L) shouldBe 0L
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*PinHasherTest*' --tests '*PinRateLimiterTest*'`
Expected: FAIL (old PinHasher format, no PinRateLimiter).

- [ ] **Step 3: Implement**

Rewrite `PinHasher.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN hashing: PBKDF2-HMAC-SHA256, 600k iterations, 16-byte random salt. */
object PinHasher {

    private const val ALGO = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun hashPin(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val hashB64 = Base64.getEncoder().encodeToString(hash)
        return listOf("pbkdf2", ITERATIONS.toString(), saltB64, hashB64)
            .joinToString(separator = "$")
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        val parts = storedHash.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations < ITERATIONS) return false
        val salt = try { Base64.getDecoder().decode(parts[2]) } catch (e: IllegalArgumentException) { return false }
        val expected = try { Base64.getDecoder().decode(parts[3]) } catch (e: IllegalArgumentException) { return false }
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(ALGO)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
```

(Create `PinRateLimiter.kt` with the class as specified in Interfaces; delay computation:)

```kotlin
fun recordFailure(now: Long) {
    val n = state.attempts() + 1
    state.setAttempts(n)
    if (n >= maxAttempts) {
        val exponent = (n - maxAttempts).coerceAtMost(30)
        val delay = (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
        state.setLockoutUntil(now + delay)
    }
}

fun retryDelayMs(now: Long): Long {
    if (state.attempts() < maxAttempts) return 0L
    return (state.lockoutUntil() - now).coerceAtLeast(0L)
}

fun recordSuccess() {
    state.setAttempts(0)
    state.setLockoutUntil(0L)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testCryptoDebugUnitTest`
Expected: PASS. (PBKDF2 tests each take ~0.3–0.6 s — acceptable.)

- [ ] **Step 5: Commit**

```bash
git add app/src/crypto app/src/testCrypto
git commit -m "feat(crypto): PBKDF2-600k PIN hashing with constant-time verify and exponential backoff"
```

---

### Task 7: App lock wiring (policy + activity + lifecycle hook)

**Files:**
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/AppLockPolicy.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/storage/SettingsStore.kt`
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/AppLockActivity.kt`
- Modify: `app/src/crypto/AndroidManifest.xml` (register activity)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/CryptoSettingsScreen.kt` (PIN dialog replaces TODO at line 106)
- Modify: `app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/CryptoBarHook.kt` (`initialize` stores `appContext`, `onKeyboardShow` launches lock, `onKeyboardHide` resets guard)
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/core/AppLockPolicyTest.kt`

**Interfaces:**
- Consumes: `PinHasher`, `PinRateLimiter` (Task 6), `SettingsStore.loadSettings()/recordUnlock()`.
- Produces: `AppLockPolicy.isLocked(appLockEnabled: Boolean, pinConfigured: Boolean, lastUnlockAt: Long, timeoutSeconds: Int, nowMs: Long): Boolean`; `SettingsStore.setPin(pin: String)`, `SettingsStore.clearPin()`, `SettingsStore.verifyPin(pin: String): Boolean`, `SettingsStore.pinRateLimiter(): PinRateLimiter`; `ui.AppLockActivity`.

- [ ] **Step 1: Write failing policy test**

```kotlin
package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppLockPolicyTest : FunSpec({

    test("never locked when disabled") {
        AppLockPolicy.isLocked(
            appLockEnabled = false, pinConfigured = true,
            lastUnlockAt = 0L, timeoutSeconds = 300, nowMs = 10_000L
        ) shouldBe false
    }

    test("never locked when no PIN configured (prevents lockout)") {
        AppLockPolicy.isLocked(
            appLockEnabled = true, pinConfigured = false,
            lastUnlockAt = 0L, timeoutSeconds = 300, nowMs = 10_000L
        ) shouldBe false
    }

    test("locked after timeout elapsed") {
        AppLockPolicy.isLocked(true, true, lastUnlockAt = 0L, timeoutSeconds = 300, nowMs = 301_000L) shouldBe true
        AppLockPolicy.isLocked(true, true, lastUnlockAt = 0L, timeoutSeconds = 300, nowMs = 299_000L) shouldBe false
    }

    test("recordUnlock clears the lock until timeout passes") {
        AppLockPolicy.isLocked(true, true, lastUnlockAt = 1_000L, timeoutSeconds = 300, nowMs = 1_001L) shouldBe false
    }

    test("zero or negative timeout does not instantly relock") {
        AppLockPolicy.isLocked(true, true, lastUnlockAt = 1_000L, timeoutSeconds = 0, nowMs = 1_001L) shouldBe false
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*AppLockPolicyTest*'`
Expected: FAIL — `AppLockPolicy` does not exist.

- [ ] **Step 3: Implement policy + SettingsStore wiring**

`AppLockPolicy.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.core

/** Pure app-lock decision so it can be unit-tested without Android. */
object AppLockPolicy {
    fun isLocked(
        appLockEnabled: Boolean,
        pinConfigured: Boolean,
        lastUnlockAt: Long,
        timeoutSeconds: Int,
        nowMs: Long
    ): Boolean {
        if (!appLockEnabled || !pinConfigured) return false
        if (timeoutSeconds <= 0) return false // never relock-loop on a zero timeout
        return nowMs - lastUnlockAt > timeoutSeconds * 1000L
    }
}
```

`SettingsStore.kt` additions:

```kotlin
fun setPin(pin: String) {
    prefs.edit()
        .putString("pin_hash", com.mkcreations.cryptokeyboard.crypto.PinHasher.hashPin(pin))
        .putBoolean("app_lock_enabled", true)
        .putLong("last_unlock_time", System.currentTimeMillis())
        .apply()
}

fun clearPin() {
    prefs.edit()
        .putString("pin_hash", "")
        .putBoolean("app_lock_enabled", false)
        .putInt("pin_failed_attempts", 0)
        .putLong("pin_lockout_until", 0L)
        .apply()
}

fun verifyPin(pin: String): Boolean =
    com.mkcreations.cryptokeyboard.crypto.PinHasher.verifyPin(pin, loadSettings().pinHash)

fun pinRateLimiter(): com.mkcreations.cryptokeyboard.crypto.PinRateLimiter {
    val state = object : com.mkcreations.cryptokeyboard.crypto.PinRateLimiter.State {
        override fun attempts() = prefs.getInt("pin_failed_attempts", 0)
        override fun setAttempts(n: Int) { prefs.edit().putInt("pin_failed_attempts", n).apply() }
        override fun lockoutUntil() = prefs.getLong("pin_lockout_until", 0L)
        override fun setLockoutUntil(t: Long) { prefs.edit().putLong("pin_lockout_until", t).apply() }
    }
    return com.mkcreations.cryptokeyboard.crypto.PinRateLimiter(state)
}
```

Rewrite `isAppLocked()` to use the policy:

```kotlin
fun isAppLocked(): Boolean {
    val settings = loadSettings()
    val lastUnlock = prefs.getLong("last_unlock_time", 0)
    return AppLockPolicy.isLocked(
        appLockEnabled = settings.appLockEnabled,
        pinConfigured = settings.pinHash.isNotEmpty(),
        lastUnlockAt = lastUnlock,
        timeoutSeconds = settings.lockTimeoutSeconds,
        nowMs = System.currentTimeMillis()
    )
}
```

In `loadSettings()`, change `lockTimeoutSeconds = prefs.getInt("lock_timeout", 0)` to `prefs.getInt("lock_timeout", 300)`. Import `AppLockPolicy`.

- [ ] **Step 4: Run policy test to verify it passes**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*AppLockPolicyTest*'`
Expected: PASS.

- [ ] **Step 5: Create AppLockActivity + manifest entry**

`AppLockActivity.kt` (programmatic Views, no Compose needed — keeps it small and dependency-free):

```kotlin
package com.mkcreations.cryptokeyboard.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mkcreations.cryptokeyboard.storage.SettingsStore

/**
 * PIN gate shown when the app lock is engaged. On success it records the
 * unlock and finishes. If no PIN is configured it finishes immediately
 * (defensive: never brick the keyboard).
 */
class AppLockActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var pinInput: EditText
    private lateinit var statusText: TextView
    private lateinit var unlockButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore.getInstance(this)
        if (!store.isAppLocked()) {
            finish()
            return
        }
        buildUi()
        applyBackoffIfAny()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(0xFF12121F.toInt())
        }
        root.addView(TextView(this).apply {
            text = "🔒 Crypto Keyboard Locked"
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Enter your PIN"
            setTextColor(0xFF888899.toInt())
            textSize = 13f
            setPadding(0, 24, 0, 8)
        })
        pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF555566.toInt())
            hint = "PIN"
        }
        root.addView(pinInput)
        statusText = TextView(this).apply {
            setTextColor(0xFFFF8A80.toInt())
            textSize = 12f
            setPadding(0, 12, 0, 0)
        }
        root.addView(statusText)
        unlockButton = Button(this).apply { text = "Unlock" }
        root.addView(unlockButton)
        setContentView(root)

        unlockButton.setOnClickListener { attemptUnlock() }
    }

    private fun applyBackoffIfAny() {
        val delay = store.pinRateLimiter().retryDelayMs(System.currentTimeMillis())
        if (delay > 0) {
            unlockButton.isEnabled = false
            statusText.text = "Too many attempts — wait ${delay / 1000 + 1}s"
            unlockButton.postDelayed({
                unlockButton.isEnabled = true
                statusText.text = ""
            }, delay)
        }
    }

    private fun attemptUnlock() {
        val pin = pinInput.text.toString()
        if (pin.isEmpty()) return
        val limiter = store.pinRateLimiter()
        if (limiter.retryDelayMs(System.currentTimeMillis()) > 0) return
        if (store.verifyPin(pin)) {
            limiter.recordSuccess()
            store.recordUnlock()
            finish()
        } else {
            val now = System.currentTimeMillis()
            limiter.recordFailure(now)
            statusText.text = "Wrong PIN"
            pinInput.setText("")
            applyBackoffIfAny()
        }
    }
}
```

Manifest — add inside `<application>` in `app/src/crypto/AndroidManifest.xml`:

```xml
<activity
    android:name="com.mkcreations.cryptokeyboard.ui.AppLockActivity"
    android:exported="false"
    android:noHistory="true"
    android:excludeFromRecents="true"
    android:theme="@android:style/Theme.Material.NoActionBar" />
```

- [ ] **Step 6: Wire CryptoBarHook lifecycle**

In `CryptoBarHook.kt` add `import android.content.Intent` and:

```kotlin
private var appContext: Context? = null
private var lockPromptShowing = false
```

In `initialize(context)` first line: `appContext = context.applicationContext`.

In `onKeyboardShow()` after the existing body, add (all inside the existing try, or its own try/catch):

```kotlin
maybeLaunchAppLock()
```

Add the method to the object:

```kotlin
private fun maybeLaunchAppLock() {
    if (lockPromptShowing) return
    val ctx = appContext ?: return
    try {
        val storeClass = Class.forName("com.mkcreations.cryptokeyboard.storage.SettingsStore")
        val getInstance = storeClass.getMethod("getInstance", Context::class.java)
        val store = getInstance.invoke(null, ctx)
        val locked = storeClass.getMethod("isAppLocked").invoke(store) as? Boolean ?: false
        if (!locked) return
        val intent = Intent(ctx, Class.forName("com.mkcreations.cryptokeyboard.ui.AppLockActivity"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        lockPromptShowing = true
    } catch (e: ClassNotFoundException) {
        // floris flavor — no crypto lock
    } catch (e: Exception) {
        Log.e(TAG, "App lock launch failed", e)
    }
}
```

In `onKeyboardHide()` add `lockPromptShowing = false`. In `destroy()` add `appContext = null; lockPromptShowing = false`.

- [ ] **Step 7: PIN setup dialog in CryptoSettingsScreen**

Replace the TODO at line 105-107 with a dialog. Add state near the top of `CryptoSettingsScreen`:

```kotlin
var showPinDialog by remember { mutableStateOf(false) }
var pinValue by remember { mutableStateOf("") }
var pinConfirm by remember { mutableStateOf("") }
```

Add before the closing of `content {}` (after the existing PreferenceGroups):

```kotlin
if (showPinDialog) {
    AlertDialog(
        onDismissRequest = { showPinDialog = false },
        title = { Text("Set App Lock PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pinValue,
                    onValueChange = { pinValue = it },
                    label = { Text("New PIN (4+ digits)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinConfirm,
                    onValueChange = { pinConfirm = it },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (pinValue.length < 4) {
                    Toast.makeText(context, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                } else if (pinValue != pinConfirm) {
                    Toast.makeText(context, "PINs do not match", Toast.LENGTH_SHORT).show()
                } else {
                    SettingsStore.getInstance(context).setPin(pinValue)
                    pinValue = ""; pinConfirm = ""
                    showPinDialog = false
                    Toast.makeText(context, "App lock enabled", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = {
                SettingsStore.getInstance(context).clearPin()
                pinValue = ""; pinConfirm = ""
                showPinDialog = false
                Toast.makeText(context, "App lock disabled", Toast.LENGTH_SHORT).show()
            }) { Text("Turn off lock") }
        }
    )
}
```

Change the "Change App Lock PIN" `onClick` to `showPinDialog = true`. Add imports: `androidx.compose.material.*` pieces as used elsewhere in the file (`AlertDialog`, `Button`, `TextButton`, `OutlinedTextField`, `Spacer`, `Column`, `height`, `Modifier` already partly present — match the file's existing import style), `androidx.compose.ui.text.input.PasswordVisualTransformation`, `androidx.compose.foundation.text.KeyboardOptions`, `androidx.compose.ui.text.input.KeyboardType`, `com.mkcreations.cryptokeyboard.storage.SettingsStore`.

- [ ] **Step 8: Compile + run tests**

Run: `./gradlew :app:assembleCryptoDebug` then `./gradlew :app:testCryptoDebugUnitTest`
Expected: both PASS. (Manual check: set PIN in Settings → lock engages after 300 s idle keyboard shows → PIN unlocks.)

- [ ] **Step 9: Commit**

```bash
git add app/src/crypto app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/CryptoBarHook.kt
git commit -m "feat(crypto): real app lock — PBKDF2 PIN, backoff, gate on keyboard show"
```

---

### Task 8: ScreenScanner password-field skip

**Files:**
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/SensitiveNodeFilter.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/ScreenScanner.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/SensitiveNodeFilterTest.kt`

**Interfaces:**
- Produces: `SensitiveNodeFilter.shouldSkip(isPassword: Boolean, className: String?, hint: String?): Boolean`.

- [ ] **Step 1: Write failing test**

```kotlin
package com.mkcreations.cryptokeyboard.accessibility

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SensitiveNodeFilterTest : FunSpec({

    test("password-flagged nodes are always skipped") {
        SensitiveNodeFilter.shouldSkip(isPassword = true, className = "android.widget.TextView", hint = null) shouldBe true
    }

    test("password hints are skipped") {
        SensitiveNodeFilter.shouldSkip(false, "android.widget.EditText", "Password") shouldBe true
        SensitiveNodeFilter.shouldSkip(false, "android.widget.EditText", "enter passcode") shouldBe true
        SensitiveNodeFilter.shouldSkip(false, "android.widget.EditText", "PIN") shouldBe true
    }

    test("ordinary nodes are scanned") {
        SensitiveNodeFilter.shouldSkip(false, "android.widget.TextView", null) shouldBe false
        SensitiveNodeFilter.shouldSkip(false, "android.widget.EditText", "Message") shouldBe false
        SensitiveNodeFilter.shouldSkip(false, null, null) shouldBe false
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests '*SensitiveNodeFilterTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement filter and wire into scanner**

`SensitiveNodeFilter.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.accessibility

/**
 * The accessibility scanner must never read password/PIN fields.
 * node.isPassword is the primary signal; hints cover EditTexts that
 * fail to flag isPassword on some OEM keyboards/apps.
 */
object SensitiveNodeFilter {
    private val SENSITIVE_HINTS = listOf("password", "passcode", "passwd", "pin")

    fun shouldSkip(isPassword: Boolean, className: String?, hint: String?): Boolean {
        if (isPassword) return true
        val h = hint?.lowercase()?.trim() ?: return false
        if (h.isEmpty()) return false
        return SENSITIVE_HINTS.any { h == it || h.startsWith("$it ") || h.contains("$it:") }
    }
}
```

In `ScreenScanner.scanNode`, wrap the two read blocks. Before reading `node.text`:

```kotlin
val hint = node.hintText?.toString()
val skip = SensitiveNodeFilter.shouldSkip(node.isPassword, node.className?.toString(), hint)
if (!skip) {
    // existing text-read block
}
```

Apply the same `skip` guard to the content-description block (compute `skip` once before both). Child traversal stays unguarded (children may still contain visible messages). Apply the same guard in `quickCheckNode`.

- [ ] **Step 4: Run tests + compile**

Run: `./gradlew :app:testCryptoDebugUnitTest` and `./gradlew :app:assembleCryptoDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/crypto app/src/testCrypto
git commit -m "fix(accessibility): skip password and PIN nodes during screen scanning"
```

---

### Task 9: Panic mode — overlay removal + handler instantiation

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/OverlayManager.kt` (companion registry)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/PanicHandler.kt` (step 1)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/CryptoAccessibilityService.kt` (create/stop PanicHandler, clear registry)

**Interfaces:**
- Produces: `OverlayManager.removeAllActiveOverlays()` (companion, static); service owns `panicHandler: PanicHandler?`.
- Consumes: existing `OverlayManager.removeAllOverlays()`, `PanicHandler.executePanic()`.

- [ ] **Step 1: Add companion registry to OverlayManager**

```kotlin
companion object {
    private const val TAG = "OverlayManager"

    /** Single active manager (services run in the app's main process). */
    @Volatile
    private var current: OverlayManager? = null

    fun onManagerCreated(manager: OverlayManager) { current = manager }

    fun onManagerDestroyed(manager: OverlayManager) {
        if (current === manager) current = null
    }

    /** Called by panic mode: remove every overlay this process has added. */
    fun removeAllActiveOverlays() {
        current?.removeAllOverlays()
    }
}
```

Remove the old `companion object { private const val TAG = ... }` body (merge into the above). Add `init { current = this }`? No — call `OverlayManager.onManagerCreated(this)` in the service after construction instead (init in a constructor fires for any instance; the explicit call keeps ownership clear).

- [ ] **Step 2: Wire PanicHandler step 1**

In `PanicHandler.executePanic()`, replace the TODO block:

```kotlin
if (settings.panicRemoveOverlays) {
    try {
        com.mkcreations.cryptokeyboard.accessibility.OverlayManager.removeAllActiveOverlays()
    } catch (e: Exception) {
        // Overlay removal is best-effort during panic
    }
}
```

- [ ] **Step 3: Instantiate PanicHandler in the accessibility service**

In `CryptoAccessibilityService.onServiceConnected()` after `overlayManager = OverlayManager(this)`:

```kotlin
OverlayManager.onManagerCreated(overlayManager!!)
startPanicHandlerIfEnabled()
```

Add to the service:

```kotlin
private var panicHandler: PanicHandler? = null

private fun startPanicHandlerIfEnabled() {
    val settings = com.mkcreations.cryptokeyboard.storage.SettingsStore.getInstance(this).loadSettings()
    if (!settings.panicEnabled) return
    panicHandler = PanicHandler(this).also { it.startListening(settings.panicTrigger) }
}
```

(Import `com.mkcreations.cryptokeyboard.core.PanicHandler`.)

In `onDestroy()` add:

```kotlin
panicHandler?.stopListening()
panicHandler = null
overlayManager?.let { OverlayManager.onManagerDestroyed(it) }
```

before `super.onDestroy()` (existing `overlayManager?.removeAllOverlays()` stays).

- [ ] **Step 4: Compile + verify**

Run: `./gradlew :app:assembleCryptoDebug`
Expected: PASS. Manual: enable Panic (settings) → shake device → overlays vanish, cache clears.

- [ ] **Step 5: Commit**

```bash
git add app/src/crypto
git commit -m "fix(panic): wire overlay removal and instantiate panic handler in accessibility service"
```

---

### Task 10: Delete dead plaintext draft persistence

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/SharedState.kt`

- [ ] **Step 1: Verify no references**

Run: `grep -rn "pendingSecretMessage\|pendingCoverMessage\|clearPendingMessages\|KEY_PENDING" app/src/`
Expected: only `SharedState.kt` itself. If any other file appears, update that file's usage in the same commit (replace reads with `""` and drop writes).

- [ ] **Step 2: Delete the dead API**

Remove from `SharedState.kt`: `KEY_PENDING_SECRET`, `KEY_PENDING_COVER`, `pendingSecretMessage`, `pendingCoverMessage`, `clearPendingMessages()`. Update the file comment from "only stores flags, not sensitive data" to remain accurate (it now truly only stores flags).

- [ ] **Step 3: Compile**

Run: `./gradlew :app:assembleCryptoDebug`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/crypto
git commit -m "chore(crypto): remove dead plaintext draft persistence from SharedState"
```

---

### Task 11: Typed results for KeyExporter and QrCodeHelper

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/KeyExporter.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/QrCodeHelper.kt`
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyManagementScreen.kt` (import ~791, export ~873)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/QrScannerScreen.kt` (~134)

**Interfaces:**
- Produces: `KeyExporter.exportKey(key, password): ExportOutcome` (`Success(json: String)` / `Failure(reason: String, cause: Throwable?)`); `KeyExporter.importKey(exportString, password): ImportOutcome` (`Success(key: CipherKey)` / `Failure(...)`); `QrCodeHelper.parseQrPayload(payload): ParseOutcome` (`Success(key: CipherKey)` / `Failure(reason: String)`).

- [ ] **Step 1: Convert KeyExporter**

```kotlin
sealed interface ExportOutcome {
    data class Success(val json: String) : ExportOutcome
    data class Failure(val reason: String, val cause: Throwable? = null) : ExportOutcome
}

sealed interface ImportOutcome {
    data class Success(val key: CipherKey) : ImportOutcome
    data class Failure(val reason: String, val cause: Throwable? = null) : ImportOutcome
}
```

`exportKey` returns `ExportOutcome`: on catch → `ExportOutcome.Failure("Export failed: ${e.javaClass.simpleName}", e)` (no `printStackTrace`, no plaintext in the message). Success → `Success(json)`. `importKey` similarly: wrong version/type → `Failure("Unsupported export format")`, catch AEAD failures → `Failure("Wrong password or corrupted file", e)`, parse errors → `Failure("Malformed export data", e)`. Also switch its `android.util.Base64` to `java.util.Base64` (`getEncoder().encodeToString` / `getDecoder().decode`) and drop the android import.

- [ ] **Step 2: Convert QrCodeHelper.parseQrPayload**

```kotlin
sealed interface ParseOutcome {
    data class Success(val key: CipherKey) : ParseOutcome
    data class Failure(val reason: String) : ParseOutcome
}
```

Return `Failure("Not a crypto key QR")` for bad version/type, `Failure("Key data invalid size")`, `Failure("Fingerprint mismatch")`, and `Failure("Malformed QR payload")` on catch. Switch `android.util.Base64` → `java.util.Base64`.

- [ ] **Step 3: Update call sites**

`KeyManagementScreen.kt` import branch (~line 790):

```kotlin
val imported = withContext(Dispatchers.IO) {
    KeyExporter.importKey(importFileData, importPassword)
}
when (imported) {
    is KeyExporter.ImportOutcome.Success -> {
        val importedKey = imported.key
        // existing body that used `importedKey`, unchanged
    }
    is KeyExporter.ImportOutcome.Failure -> {
        Toast.makeText(context, imported.reason, Toast.LENGTH_LONG).show()
    }
}
```

Export branch (~line 872):

```kotlin
val result = withContext(Dispatchers.IO) {
    KeyExporter.exportKey(keyToExport, exportPassword)
}
when (result) {
    is KeyExporter.ExportOutcome.Success -> exportedResult = result.json
    is KeyExporter.ExportOutcome.Failure ->
        Toast.makeText(context, "Export failed: ${result.reason}", Toast.LENGTH_SHORT).show()
}
```

`QrScannerScreen.kt` (~line 134):

```kotlin
when (val parsed = QrCodeHelper.parseQrPayload(rawValue)) {
    is QrCodeHelper.ParseOutcome.Success -> {
        if (!hasScanned) {
            hasScanned = true
            onKeyScanned(parsed.key)
        }
    }
    is QrCodeHelper.ParseOutcome.Failure -> {
        scanError = "Invalid key data in QR code"
    }
}
```

(If `QrCodeHelper.parseQrPayload` callers exist elsewhere, the compiler will list them — apply the same `when` shape.)

- [ ] **Step 4: Compile**

Run: `./gradlew :app:assembleCryptoDebug`
Expected: PASS. (No JVM tests: these classes depend on android `org.json`/`Bitmap` stubs.)

- [ ] **Step 5: Commit**

```bash
git add app/src/crypto
git commit -m "refactor(crypto): typed sealed results for key export/import and QR parsing"
```

---

### Task 12: Honest documentation

**Files:**
- Modify: `README.md`
- Modify: `SECURITY.md`
- Modify: `ROADMAP.md` (only the claims verified stale)

- [ ] **Step 1: README.md updates**

- Encryption feature list: add "Header authenticated with AES-GCM AAD (tamper-evident)", "No key identifiers on the wire (v2)", "Best-effort replay deduplication", "Recovery phrases include a checksum word (33 words)".
- Security table: replace `PIN protection | SHA-256, 10k iterations` with `PIN protection | PBKDF2-HMAC-SHA256, 600k iterations, constant-time verify, exponential backoff (5 fails)`.
- Message format section: replace with:

```
Current (v2):
[1 byte version=2][12 bytes IV][N bytes ciphertext + tag]
— header (version + IV) authenticated via GCM AAD; no fingerprint on the wire;
  receiver tries stored keys (only the right key verifies).

Legacy (v1, receive only):
[1 byte version=1][4 bytes key fingerprint][12 bytes IV][N bytes ciphertext + tag]
```

- "What This Protects Against": add `✅ Replay of captured messages — best-effort duplicate detection (per device, in-memory)` and a "What this doesn't" line: `❌ Long-term replay protection — dedup cache is per-device and resets on restart`.
- Setup section step 3: "32-word recovery phrase" → "33-word recovery phrase (last word is a checksum)".

- [ ] **Step 2: SECURITY.md updates**

- Line ~60: replace the replay claim with:

```
- **Verbatim message re-sends** — Best-effort: a per-device, in-memory LRU (2,000
  entries, 30-day TTL) rejects payloads it has already decrypted. This is NOT a
  session ratchet: a device restart, cache clear, or a second device that never
  saw the original will still accept the replay.
```

- PIN section (line ~174): rewrite to PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt, format `pbkdf2$600000$salt$hash`, constant-time comparison via `MessageDigest.isEqual`, exponential backoff after 5 failed attempts (5 s doubling to a 5 min cap, persisted).
- Line ~258 "PIN verification uses constant-time comparison" is now true — keep, but ensure it refers to `MessageDigest.isEqual`.
- Add a "Traffic analysis" note: v2 messages carry no key identifier; legacy v1 messages (received only) still contain a 4-byte fingerprint and remain clusterable.
- Add known limitations: cover text is stripped of zero-width characters before embedding (ZWJ emoji sequences in covers lose their joiner); the send path replaces pre-existing text in the target input field; the cover pool is small.

- [ ] **Step 3: ROADMAP.md**

In "What's Working", change "PIN-protected key storage" → "PIN-protected app lock (PBKDF2 + backoff)" and add "Replay detection (best-effort)" — only these two lines; leave the rest.

- [ ] **Step 4: Commit**

```bash
git add README.md SECURITY.md ROADMAP.md
git commit -m "docs: describe real protocol v2 architecture, remove false replay/PIN claims"
```

---

### Task 13: Full verification

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest`
Expected: PASS on both flavors (floris must still compile its shared test source set).

- [ ] **Step 2: Assemble both flavors**

Run: `./gradlew :app:assembleCryptoDebug :app:assembleFlorisDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Report**

Summarize: tests run/pass counts, APK outputs, any deviations from the plan, residual items (send-flow replace semantics, cover pool size).

- [ ] **Step 4: Final commit (if any fixes were needed)**

```bash
git add -A
git commit -m "chore: fixes from full verification run"
```
