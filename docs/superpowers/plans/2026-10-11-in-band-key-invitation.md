# In-Band Key Invitation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user hand a fresh AES-256 pairing key to a contact by hiding a versioned invite envelope inside an ordinary cover message, detected on-screen (Path A) or from the clipboard (Path B), reviewed in a Compose approval flow, and saved under an alias — plus a sender flow and an opt-in, auto-shutoff scan toggle.

**Architecture:** A pure `InviteEnvelope` byte protocol (magic "SK" / v1 / type / 32-byte key / length-prefixed UTF-8 note) rides the existing `InvisibleEncoder` cover channel. `MessageProcessor.detectAndDecrypt` sniffs the magic and returns a new `DecryptResult.KeyInvite` instead of attempting decryption. Because neither a `FLAG_NOT_TOUCHABLE` overlay nor an IME can host a Compose dialog, both detection paths launch a small `KeyInviteApprovalActivity` (mirroring the existing `AppLockActivity`) that renders the approval UI, saves a `CipherKey` via `SecureKeyStore`, and tells the accessibility service to stop scanning. A pure `InviteScanController` gates scanning behind an opt-in flag plus a 5-minute window / accept-shutoff.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `EncryptedSharedPreferences`, plain-JVM kotest tests in `app/src/testCrypto/`.

**Spec:** User-provided architecture (in-band key invitation), 2026-10-11 — see **Spec adaptations** under Global Constraints for two places the request named APIs that don't exist and how they map to the real ones.

## Global Constraints

- All code and comments in **English**.
- **TDD**: write the failing test, watch it fail for the right reason, then implement. Plain-JVM tests live in `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/...` (no Android deps).
- New crypto logic lives in the **crypto** source set (`app/src/crypto/kotlin/...`) so the **floris** flavor is unaffected. Only `CryptoBarHook.kt` (main source set) changes, and only via reflection/class-name strings so floris still compiles.
- Envelope wire format (exact): MAGIC `0x53,0x4B` (2B) · VERSION `0x01` (1B) · TYPE `0x01` Key Invite (1B) · KEY 32 raw bytes · NOTE_LEN 2B big-endian · NOTE UTF-8 capped at 128 bytes. Fixed prefix = 38 bytes.
- Full verify before every commit: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug :app:assembleFlorisDebug` — must be BUILD SUCCESSFUL, 0 failures (baseline 245 tests).
- Commit per task: `git add -A && git commit -m "feat(crypto): ..."`. Nothing pushed.

### Spec adaptations (spec named non-existent APIs → real ones)
1. `InvisibleEncoder.extract()` → the real method is `InvisibleEncoder.extractFromMessage(message): ExtractOutcome` (`Success(bytes)` / `NotPresent` / `Malformed`). Invite extraction uses this.
2. `DecryptedResult.KeyInvite` → the real result type is `MessageProcessor.DecryptResult` (sealed class). We add `DecryptResult.KeyInvite(envelope)`.
3. Approval UI host: a Compose `AlertDialog` cannot be shown from a `FLAG_NOT_TOUCHABLE` overlay or an IME. We use a translucent `KeyInviteApprovalActivity` (existing `AppLockActivity` precedent) so both paths reuse one Compose surface. The overlay bubble is touchable (`FLAG_NOT_FOCUSABLE` kept, `FLAG_NOT_TOUCHABLE` dropped) and its tap launches the Activity.

## Review Focus
(input classes the spec implies but no single task's happy-path test covers — each gets a test in the named task)
- **Truncated / short envelope** (fewer than 38 bytes) → `deserialize` must fail, never throw out of `runCatching`; never show a raw decryption error. (Task 1)
- **Note length field lies** (claims more/fewer bytes than remain) → fail with length-mismatch, don't read past the array. (Task 1)
- **Multi-byte UTF-8 note at exactly 128 bytes** → serialize truncates on a character boundary, not mid-codepoint. (Task 1)
- **Non-invite / garbage cover text reaches invite path** → `extractInvite` returns null, normal decrypt untouched. (Task 2)
- **Scan toggle OFF or 5-min window elapsed or already-accepted** → `shouldScan` false; service shows no invite bubble. (Task 3)
- **Duplicate key id on re-invite** → `saveKey` upserts by id, doesn't duplicate. (Task 5)

---

### Task 1: `InviteEnvelope` byte protocol (pure)

**Files:**
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/InviteEnvelope.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/InviteEnvelopeTest.kt`

**Interfaces:**
- Produces: `object InviteEnvelope` with `MAGIC: ByteArray`, `VERSION: Byte`, `TYPE_KEY_INVITE: Byte`, `KEY_SIZE: Int = 32`, `MAX_NOTE_BYTES: Int = 128`, `FIXED_SIZE: Int = 38`, `serialize(keyBytes: ByteArray, note: String): ByteArray`, `deserialize(bytes: ByteArray): Result<InviteEnvelope>`; `data class InviteEnvelope(val keyBytes: ByteArray, val note: String)`; `class InviteFormatException(message: String) : Exception(message)`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
        bytes[0] = 0x00
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("unsupported version fails") {
        val bytes = InviteEnvelope.serialize(key, "x")
        bytes[2] = 0x02
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("unsupported type fails") {
        val bytes = InviteEnvelope.serialize(key, "x")
        bytes[3] = 0x09
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("note length claiming more bytes than present fails") {
        val bytes = InviteEnvelope.serialize(key, "abc")
        bytes[37] = 0x10 // claims 16 note bytes, only 3 present
        InviteEnvelope.deserialize(bytes).isFailure shouldBe true
    }

    test("note length over 128 fails") {
        val bytes = InviteEnvelope.serialize(key, "abc")
        bytes[36] = 0x00
        bytes[37] = 0x81 // 129
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.crypto.InviteEnvelopeTest"`
Expected: FAIL / compilation error — `InviteEnvelope` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Thrown when an invite envelope is malformed, truncated, or unsupported. */
class InviteFormatException(message: String) : Exception(message)

/**
 * In-band key invitation envelope — a versioned byte container that carries a
 * raw AES-256 pairing key and an optional sender note. It rides the existing
 * InvisibleEncoder cover channel like any other payload, but is recognized by
 * its magic bytes before any decryption is attempted.
 *
 * Wire format (big-endian):
 *   [0..1]  MAGIC 0x53 0x4B ("SK")
 *   [2]     VERSION 0x01
 *   [3]     TYPE 0x01 (key invite)
 *   [4..35] raw 32-byte AES key
 *   [36..37] note length (unsigned short, BE)
 *   [38..]  note, UTF-8, capped at 128 bytes
 */
data class InviteEnvelope(
    val keyBytes: ByteArray,
    val note: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is InviteEnvelope && note == other.note && keyBytes.contentEquals(other.keyBytes))

    override fun hashCode(): Int = 31 * keyBytes.contentHashCode() + note.hashCode()

    companion object {
        val MAGIC = byteArrayOf(0x53, 0x4B)
        const val VERSION: Byte = 0x01
        const val TYPE_KEY_INVITE: Byte = 0x01
        const val KEY_SIZE = 32
        const val MAX_NOTE_BYTES = 128
        const val FIXED_SIZE = 4 + KEY_SIZE + 2 // magic+ver+type + key + noteLen

        fun serialize(keyBytes: ByteArray, note: String): ByteArray {
            require(keyBytes.size == KEY_SIZE) { "key must be $KEY_SIZE bytes" }
            val noteBytes = note.truncateUtf8(MAX_NOTE_BYTES)
            val out = ByteArray(FIXED_SIZE + noteBytes.size)
            var i = 0
            for (b in MAGIC) out[i++] = b
            out[i++] = VERSION
            out[i++] = TYPE_KEY_INVITE
            keyBytes.copyInto(out, i); i += KEY_SIZE
            out[i++] = ((noteBytes.size shr 8) and 0xFF).toByte()
            out[i++] = (noteBytes.size and 0xFF).toByte()
            noteBytes.copyInto(out, i)
            return out
        }

        fun deserialize(bytes: ByteArray): Result<InviteEnvelope> = runCatching {
            if (bytes.size < FIXED_SIZE) throw InviteFormatException("too short: ${bytes.size}")
            if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1]) throw InviteFormatException("bad magic")
            if (bytes[2] != VERSION) throw InviteFormatException("unsupported version ${bytes[2]}")
            if (bytes[3] != TYPE_KEY_INVITE) throw InviteFormatException("unsupported type ${bytes[3]}")
            val keyBytes = bytes.copyOfRange(4, 4 + KEY_SIZE)
            val noteLen = ((bytes[36].toInt() and 0xFF) shl 8) or (bytes[37].toInt() and 0xFF)
            if (noteLen > MAX_NOTE_BYTES) throw InviteFormatException("note too long: $noteLen")
            if (bytes.size != FIXED_SIZE + noteLen) {
                throw InviteFormatException("length mismatch: have ${bytes.size}, want ${FIXED_SIZE + noteLen}")
            }
            val note = strictUtf8(bytes, FIXED_SIZE, noteLen)
            InviteEnvelope(keyBytes, note)
        }

        /** Strict UTF-8 decode — malformed bytes become a failure, not U+FFFD. */
        private fun strictUtf8(bytes: ByteArray, offset: Int, length: Int): String =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()

        /** UTF-8 bytes of the note, truncated to [maxBytes] on a char boundary. */
        private fun String.truncateUtf8(maxBytes: Int): ByteArray {
            val bytes = toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            var end = maxBytes
            // Walk back off any continuation byte (10xxxxxx) so we never split a codepoint.
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return bytes.copyOf(end)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.crypto.InviteEnvelopeTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(crypto): InviteEnvelope byte protocol with strict validation"
```

---

### Task 2: Invite routing in `MessageProcessor` (pure)

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/MessageProcessor.kt` (add `KeyInvite` subclass to `DecryptResult` ~L192; add `extractInvite`; sniff in `detectAndDecrypt` ~L84)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ime/CryptoInputManager.kt` (handle new subclass in the `DecryptResult` → `DecryptionResult` mapping)
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/MessageProcessorInviteTest.kt`

**Interfaces:**
- Consumes: `InviteEnvelope.serialize/deserialize`, `InvisibleEncoder.embedInCover/extractFromMessage`.
- Produces: `MessageProcessor.extractInvite(message: String): InviteEnvelope?`; `MessageProcessor.DecryptResult.KeyInvite(val envelope: InviteEnvelope) : DecryptResult()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf

class MessageProcessorInviteTest : FunSpec({

    val key = ByteArray(32) { (it * 2).toByte() }

    fun coverWith(envelope: InviteEnvelope): String =
        InvisibleEncoder.embedInCover(InviteEnvelope.serialize(key, envelope.note), "hi there")

    test("extractInvite returns the envelope from a cover message") {
        val msg = coverWith(InviteEnvelope(key, "hey"))
        val env = MessageProcessor.extractInvite(msg)
        env.shouldNotBeNull()
        env.note shouldBe "hey"
        env.keyBytes shouldBe key
    }

    test("extractInvite returns envelope from bare Base64 payload (no cover)") {
        val b64 = android.util.Base64.encodeToString(
            InviteEnvelope.serialize(key, "n"), android.util.Base64.NO_WRAP
        )
        MessageProcessor.extractInvite(b64).shouldNotBeNull()
    }

    test("extractInvite is null for plain text") {
        MessageProcessor.extractInvite("just a normal sentence").shouldBeNull()
    }

    test("extractInvite is null for an ordinary encrypted message") {
        val enc = MessageProcessor.encryptAndEmbed("secret", "cover", testKey(key))
        MessageProcessor.extractInvite(enc.finalMessage).shouldBeNull()
    }

    test("detectAndDecrypt returns KeyInvite for an invite message") {
        val msg = coverWith(InviteEnvelope(key, "bob"))
        val res = MessageProcessor.detectAndDecrypt(msg, emptyList())
        res.shouldBeInstanceOf<MessageProcessor.DecryptResult.KeyInvite>()
        res.envelope.note shouldBe "bob"
    }

    test("detectAndDecrypt still returns NotEncrypted for plain text") {
        MessageProcessor.detectAndDecrypt("hello world", emptyList())
            .shouldBeInstanceOf<MessageProcessor.DecryptResult.NotEncrypted>()
    }
})

private fun testKey(bytes: ByteArray) = CipherKey(
    id = KeyGenerator.fingerprint(bytes),
    label = "t",
    keyBytes = bytes,
    createdAt = 0L,
    lastUsedAt = 0L,
    coverStyle = CoverStyle.RANDOM_CASUAL,
    compatibleChars = emptyList(),
)
```

> Note: the bare-Base64 test uses `android.util.Base64`. If the plain-JVM test lacks it, replace with a JVM equivalent (`java.util.Base64.getEncoder().encodeToString(...)`) — `MessageProcessor`'s NO_COVER fallback reads standard Base64. Confirm which the existing `MessageProcessor` tests use and match it.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.crypto.MessageProcessorInviteTest"`
Expected: FAIL — `extractInvite` unresolved / `KeyInvite` unresolved.

- [ ] **Step 3: Write the implementation**

In `MessageProcessor`:

```kotlin
/** Extracts a key-invite envelope, or null when the message is not an invite. */
fun extractInvite(message: String): InviteEnvelope? {
    val raw: ByteArray? = when {
        InvisibleEncoder.containsHiddenData(message) ->
            when (val out = InvisibleEncoder.extractFromMessage(message)) {
                is InvisibleEncoder.ExtractOutcome.Success -> out.data
                else -> null
            }
        else -> runCatching {
            java.util.Base64.getDecoder().decode(message.trim())
        }.getOrNull()
    } ?: return null
    if (raw.size < InviteEnvelope.FIXED_SIZE) return null
    if (raw[0] != InviteEnvelope.MAGIC[0] || raw[1] != InviteEnvelope.MAGIC[1]) return null
    return InviteEnvelope.deserialize(raw).getOrNull()
}
```

Add to the `DecryptResult` sealed class:

```kotlin
data class KeyInvite(val envelope: InviteEnvelope) : DecryptResult()
```

In `detectAndDecrypt(message, keys)`, at the very top (before the existing logic):

```kotlin
extractInvite(message)?.let { return DecryptResult.KeyInvite(it) }
```

In `CryptoInputManager`, the `when` mapping `DecryptResult` → `DecryptionResult` gains:

```kotlin
is MessageProcessor.DecryptResult.KeyInvite -> DecryptionResult.Error("Key invitation")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.crypto.MessageProcessorInviteTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole suite (new exhaustive `when` branch)**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest`
Expected: 0 failures. Fix any `when` that no longer compiles exhaustively.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(crypto): route in-band invites through MessageProcessor"
```

---

### Task 3: `InviteScanController` auto-shutoff (pure)

**Files:**
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/InviteScanController.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/core/InviteScanControllerTest.kt`

**Interfaces:**
- Produces: `class InviteScanController(val windowMs: Long = 300_000L, nowMs: () -> Long = System::currentTimeMillis)` with `fun beginWindow()`, `fun shouldScan(inviteScanEnabled: Boolean): Boolean`, `fun onInviteAccepted()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InviteScanControllerTest : FunSpec({

    test("disabled toggle never scans") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.beginWindow()
        c.shouldScan(inviteScanEnabled = false) shouldBe false
    }

    test("enabled within window scans") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.beginWindow()
        now = 1000L
        c.shouldScan(inviteScanEnabled = true) shouldBe true
    }

    test("stops scanning after the 5-minute window elapses") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.beginWindow()
        now = 300_000L // exactly at the boundary -> expired
        c.shouldScan(inviteScanEnabled = true) shouldBe false
    }

    test("stops scanning after an invite is accepted") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.beginWindow()
        c.onInviteAccepted()
        c.shouldScan(inviteScanEnabled = true) shouldBe false
    }

    test("does not scan before a window is begun") {
        val c = InviteScanController(nowMs = { 0L })
        c.shouldScan(inviteScanEnabled = true) shouldBe false
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.core.InviteScanControllerTest"`
Expected: FAIL — `InviteScanController` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.mkcreations.cryptokeyboard.core

/**
 * Pure session gate for invite scanning. Scanning is only allowed while the
 * user's opt-in toggle is on, a scan window is open, that window is under
 * [windowMs] old, and no invite has been accepted yet — so a forgotten toggle
 * cannot drain battery in the background all day.
 */
class InviteScanController(
    private val windowMs: Long = 5 * 60 * 1000L,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    private val nowMs = nowMs
    private var windowStartMs: Long? = null
    private var accepted = false

    fun beginWindow() {
        if (windowStartMs == null) windowStartMs = nowMs()
    }

    fun shouldScan(inviteScanEnabled: Boolean): Boolean {
        if (!inviteScanEnabled) return false
        if (accepted) return false
        val start = windowStartMs ?: return false
        return nowMs() - start < windowMs
    }

    fun onInviteAccepted() {
        accepted = true
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.core.InviteScanControllerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(crypto): InviteScanController opt-in scan window with auto-shutoff"
```

---

### Task 4: `scanInvitesEnabled` setting + toggle

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/model/AppSettings.kt` (add field to the "Messaging" block)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/storage/SettingsStore.kt` (`saveSettings` ~L36 add `putBoolean`; `loadSettings` ~L71 add `getBoolean`)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/CryptoSettingsScreen.kt` (add a Preference switch under a new "Key Invitations" group)

**Interfaces:**
- Produces: `AppSettings.scanInvitesEnabled: Boolean = false`.

- [ ] **Step 1: Add the field (no unit test — SharedPreferences wiring, verified by compile + existing suite)**

`AppSettings.kt`, after `smartKeySelection`:

```kotlin
    // Key invitations — opt-in screen scan for in-band invites (off by default)
    val scanInvitesEnabled: Boolean = false,
```

`SettingsStore.saveSettings`, with the other `putBoolean` lines:

```kotlin
putBoolean("scan_invites_enabled", settings.scanInvitesEnabled)
```

`SettingsStore.loadSettings`, building `base`:

```kotlin
scanInvitesEnabled = prefs.getBoolean("scan_invites_enabled", false),
```

- [ ] **Step 2: Add the toggle to `CryptoSettingsScreen`**

Add imports: `androidx.compose.material.icons.filled.Share` (or reuse `Icons.Default.VpnKey`). Add state near the other `remember` vars:

```kotlin
var settingsState by remember { mutableStateOf(SettingsStore.getInstance(context).loadSettings()) }
```

Add a new `PreferenceGroup` after "Exchange Mode" (mirroring the Panic switch pattern — a `Preference` whose `onClick` flips and persists):

```kotlin
PreferenceGroup(title = "Key Invitations") {
    Preference(
        icon = Icons.Default.Share,
        title = "Scan for key invitations",
        summary = if (settingsState.scanInvitesEnabled)
            "On — scans for 5 minutes, then stops automatically"
        else
            "Off — tap to detect key invitations shared in cover messages",
        onClick = {
            val next = !settingsState.scanInvitesEnabled
            val updated = settingsState.copy(scanInvitesEnabled = next)
            settingsState = updated
            SettingsStore.getInstance(context).saveSettings(updated)
            CryptoAccessibilityService.instance?.refreshPanicTriggers()
            Toast.makeText(
                context,
                if (next) "Invite scanning on (5 min)" else "Invite scanning off",
                Toast.LENGTH_SHORT
            ).show()
        }
    )
}
```

- [ ] **Step 3: Verify build + suite**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(crypto): scanInvitesEnabled opt-in setting and toggle"
```

---

### Task 5: `CipherKey` factory from envelope (pure)

**Files:**
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/crypto/InviteApproval.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/crypto/InviteApprovalTest.kt`

**Interfaces:**
- Consumes: `InviteEnvelope`, `KeyGenerator.fingerprint`, `CipherKey`, `CoverStyle`.
- Produces: `object InviteApproval` with `fun toCipherKey(envelope: InviteEnvelope, alias: String): CipherKey`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.crypto.InviteApprovalTest"`
Expected: FAIL — `InviteApproval` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle

/** Turns an approved invite into a storable contact key. */
object InviteApproval {
    fun toCipherKey(envelope: InviteEnvelope, alias: String): CipherKey {
        val now = System.currentTimeMillis()
        return CipherKey(
            id = KeyGenerator.fingerprint(envelope.keyBytes),
            label = alias.trim().ifEmpty { "Contact ${KeyGenerator.shortPhrase(envelope.keyBytes)}" },
            keyBytes = envelope.keyBytes,
            createdAt = now,
            lastUsedAt = now,
            coverStyle = CoverStyle.RANDOM_CASUAL,
            compatibleChars = emptyList(),
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.crypto.InviteApprovalTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(crypto): InviteApproval envelope-to-CipherKey factory"
```

---

### Task 6: `KeyInviteApprovalActivity` (Compose approval UI)

**Files:**
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyInviteApprovalActivity.kt`
- Modify: `app/src/crypto/AndroidManifest.xml` (register the Activity, translucent theme)

**Interfaces:**
- Consumes: `MessageProcessor.extractInvite`, `InviteApproval.toCipherKey`, `SecureKeyStore.getInstance().saveKey`, `CryptoAccessibilityService.instance?.onInviteAccepted()`.
- Produces: `class KeyInviteApprovalActivity : ComponentActivity()`; companion `const val EXTRA_RAW_MESSAGE`; `fun start(context: Context, rawMessage: String)`.

- [ ] **Step 1: Register in `AndroidManifest.xml` (inside `<application>`)**

```xml
<!-- In-band key invitation review/approval, launched from overlay tap or decrypt button -->
<activity
    android:name="com.mkcreations.cryptokeyboard.ui.KeyInviteApprovalActivity"
    android:exported="false"
    android:excludeFromRecents="true"
    android:launchMode="singleTop"
    android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

- [ ] **Step 2: Write the Activity**

```kotlin
package com.mkcreations.cryptokeyboard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.mkcreations.cryptokeyboard.accessibility.CryptoAccessibilityService
import com.mkcreations.cryptokeyboard.crypto.InviteApproval
import com.mkcreations.cryptokeyboard.crypto.MessageProcessor
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore

/**
 * Hosts the invite approval UI. A Compose dialog cannot live inside a
 * FLAG_NOT_TOUCHABLE overlay or an IME, so both detection paths launch this
 * translucent Activity instead — one surface, two entries.
 */
class KeyInviteApprovalActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RAW_MESSAGE = "invite_raw_message"

        fun start(context: Context, rawMessage: String) {
            val intent = Intent(context, KeyInviteApprovalActivity::class.java)
                .putExtra(EXTRA_RAW_MESSAGE, rawMessage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_RAW_MESSAGE)
        val envelope = raw?.let { MessageProcessor.extractInvite(it) }
        if (envelope == null) {
            Toast.makeText(this, "No valid key invitation found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            MaterialTheme {
                InviteApprovalDialog(
                    note = envelope.note,
                    onSave = { alias ->
                        val key = InviteApproval.toCipherKey(envelope, alias)
                        SecureKeyStore.getInstance(this).saveKey(key)
                        CryptoAccessibilityService.instance?.onInviteAccepted()
                        Toast.makeText(this, "Contact “${key.label}” saved", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onDecline = { finish() },
                )
            }
        }
    }
}

@Composable
private fun InviteApprovalDialog(
    note: String,
    onSave: (alias: String) -> Unit,
    onDecline: () -> Unit,
) {
    var alias by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(elevation = CardDefaults.cardElevation(8.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Key Invitation Received", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (note.isBlank()) "No note from sender." else "Note: $note",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Verification: Unverified · Ephemeral",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Contact name / alias") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDecline) { Text("Decline") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(alias) }, enabled = true) { Text("Save Contact") }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Verify build + suite**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug`
Expected: BUILD SUCCESSFUL, 0 failures. (floris unaffected — crypto-only source set.)

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(crypto): KeyInviteApprovalActivity review and save flow"
```

---

### Task 7: Touchable invite bubble + service wiring (Path A)

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/OverlayManager.kt` (add `showInviteBubble`/`removeInviteBubble`, a touchable LayoutParams variant, a separate `activeInviteBubbles` map)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/CryptoAccessibilityService.kt` (`processNode` gains a `KeyInvite` branch; holds an `InviteScanController`; adds `onInviteAccepted()`)

**Interfaces:**
- Consumes: `MessageProcessor.DecryptResult.KeyInvite`, `InviteScanController`, `KeyInviteApprovalActivity.start`.
- Produces: `OverlayManager.showInviteBubble(overlayId: String, rawMessage: String, bounds: Rect, onTap: (String) -> Unit)`; `OverlayManager.removeInviteBubble(overlayId: String)`; `CryptoAccessibilityService.onInviteAccepted()`.

- [ ] **Step 1: Add the touchable bubble to `OverlayManager`**

Add a field: `private val activeInviteBubbles = ConcurrentHashMap<String, OverlayEntry>()`. Add:

```kotlin
/** Layout params for a tappable bubble: focusable (so it gets clicks) but not stealing IME focus. */
private fun createInviteLayoutParams(bounds: Rect): WindowManager.LayoutParams =
    createLayoutParams(bounds).apply {
        // Drop FLAG_NOT_TOUCHABLE so the bubble receives taps; keep FLAG_NOT_FOCUSABLE.
        flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    }

/** Shows a tappable bubble. Tapping it invokes [onTap] with [rawMessage]. */
fun showInviteBubble(overlayId: String, rawMessage: String, bounds: Rect, onTap: (String) -> Unit) {
    if (activeInviteBubbles.containsKey(overlayId)) return
    val bubble = android.widget.TextView(context).apply {
        text = "🔑 Key invite — tap to review"
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xF56A1B9A.toInt())
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onTap(rawMessage) }
    }
    val params = createInviteLayoutParams(bounds)
    try {
        windowManager.addView(bubble, params)
        activeInviteBubbles[overlayId] = OverlayEntry(bubble, params, bounds, System.currentTimeMillis())
    } catch (e: Exception) { /* overlay permission missing — skip */ }
}

fun removeInviteBubble(overlayId: String) {
    activeInviteBubbles.remove(overlayId)?.let { entry ->
        try { windowManager.removeView(entry.view) } catch (e: Exception) { }
    }
}
```

Also clear invite bubbles in `removeAllOverlays`/`hideAllOverlays` as appropriate (call `activeInviteBubbles.keys.forEach { removeInviteBubble(it) }`).

- [ ] **Step 2: Wire the service**

Add a field and start the window on connect/refresh:

```kotlin
private val inviteScan = InviteScanController()

fun onInviteAccepted() {
    inviteScan.onInviteAccepted()
}
```

In `startPanicHandlerIfEnabled`/`refreshPanicTriggers` (where settings load): `if (settings.scanInvitesEnabled) inviteScan.beginWindow()`.

In `processNode`, add a branch in the `when (val result = ...)` **before** the success branch:

```kotlin
is MessageProcessor.DecryptResult.KeyInvite -> {
    val settings = SettingsStore.getInstance(this).loadSettings()
    if (inviteScan.shouldScan(settings.scanInvitesEnabled)) {
        val id = "invite_${node.nodeId}"
        overlayManager?.showInviteBubble(id, node.fullText, node.bounds) { raw ->
            KeyInviteApprovalActivity.start(this, raw)
        }
    }
}
```

- [ ] **Step 3: Verify build + suite**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(crypto): touchable invite bubble on screen (Path A)"
```

---

### Task 8: Clipboard invite detection + launch (Path B)

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ime/CryptoInputManager.kt` (add `detectInvite(message): Boolean`)
- Modify: `app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/CryptoBarHook.kt` (reflective `detectInvite` + launch approval Activity by class-name)

**Interfaces:**
- Consumes: `MessageProcessor.extractInvite`; `KeyInviteApprovalActivity` (by class-name string).
- Produces: `CryptoInputManager.detectInvite(message: String): Boolean`.

- [ ] **Step 1: Add `detectInvite` to `CryptoInputManager`**

```kotlin
/** True when the message carries a valid in-band key invite (clipboard/Path B). */
fun detectInvite(message: String): Boolean = MessageProcessor.extractInvite(message) != null
```

- [ ] **Step 2: Intercept in `CryptoBarHook.ClipboardDecryptButton`**

Inside the `clickable` block, **before** `CryptoBarHook.doDecrypt(clipText)`, add a reflective invite check mirroring the existing reflection pattern (same class/method lookup style used by `doDecrypt`). Pseudocode to adapt to the file's actual reflection helper:

```kotlin
val isInvite = try {
    val mgrClass = Class.forName("com.mkcreations.cryptokeyboard.ime.CryptoInputManager")
    val mgr = mgrClass.getDeclaredField("instance").get(null) // match the file's existing accessor
    val m = mgrClass.getMethod("detectInvite", String::class.java)
    m.invoke(mgr, clipText) as Boolean
} catch (e: Exception) { false }

if (isInvite) {
    val intent = Intent().setClassName(
        context,
        "com.mkcreations.cryptokeyboard.ui.KeyInviteApprovalActivity"
    ).putExtra("invite_raw_message", clipText)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    try { context.startActivity(intent) } catch (e: Exception) {
        Toast.makeText(context, "Invite approval unavailable", Toast.LENGTH_SHORT).show()
    }
    return@clickable
}
```

> Match `doDecrypt`'s exact reflection mechanism for obtaining the `CryptoInputManager` instance (it may be a getter, a singleton field, or via `FlorisImeService`). Read `CryptoBarHook.doDecrypt` (~L389) and reuse its accessor verbatim.

- [ ] **Step 3: Verify build + suite (floris must still compile — reflection only)**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug :app:assembleFlorisDebug`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(crypto): clipboard invite detection launches approval (Path B)"
```

---

### Task 9: Sender flow — "Share via Cover Message"

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyManagementScreen.kt` (add dialog state + "Share via Cover Message" button + AlertDialog, after the export dialog ~L930)

**Interfaces:**
- Consumes: `KeyGenerator.generateKey/fingerprint`, `CoverGenerator.generate`, `InviteEnvelope.serialize`, `InvisibleEncoder.embedInCover`, existing clipboard/share-sheet patterns.

- [ ] **Step 1: Add dialog state (near the other `remember` dialog vars)**

```kotlin
var showCoverShareFor by remember { mutableStateOf<CipherKey?>(null) }
var coverText by remember { mutableStateOf("") }
var inviteNote by remember { mutableStateOf("") }
```

- [ ] **Step 2: Add the AlertDialog (after the export dialog)**

```kotlin
showCoverShareFor?.let { shareKey ->
    AlertDialog(
        onDismissRequest = { showCoverShareFor = null },
        title = { Text("Share via Cover Message") },
        text = {
            Column {
                Text("Sends a fresh pairing key to a contact inside an ordinary-looking message.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = coverText,
                    onValueChange = { coverText = it },
                    label = { Text("Cover text (blank = random)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = inviteNote,
                    onValueChange = { inviteNote = it },
                    label = { Text("Greeting / note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row {
                OutlinedButton(onClick = {
                    val freshKey = KeyGenerator.generateKey()
                    val cover = coverText.ifBlank { CoverGenerator.generate(CoverStyle.RANDOM_CASUAL) }
                    val envelope = InviteEnvelope.serialize(freshKey, inviteNote)
                    val finalMsg = InvisibleEncoder.embedInCover(envelope, cover)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cover Message", finalMsg))
                    Toast.makeText(context, "Cover message copied", Toast.LENGTH_SHORT).show()
                    showCoverShareFor = null
                }) { Text("Copy") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val freshKey = KeyGenerator.generateKey()
                    val cover = coverText.ifBlank { CoverGenerator.generate(CoverStyle.RANDOM_CASUAL) }
                    val envelope = InviteEnvelope.serialize(freshKey, inviteNote)
                    val finalMsg = InvisibleEncoder.embedInCover(envelope, cover)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, finalMsg)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Cover Message"))
                    showCoverShareFor = null
                }) { Text("Share…") }
            }
        },
        dismissButton = { TextButton(onClick = { showCoverShareFor = null }) { Text("Cancel") } },
    )
}
```

> Extract the shared `freshKey`/`cover`/`envelope`/`finalMsg` build into a small local `fun buildCoverMessage(): String` to avoid duplicating it across Copy/Share. Add required imports: `ClipboardManager`, `ClipData`, `Context`, `KeyGenerator`, `CoverGenerator`, `CoverStyle`, `InviteEnvelope`, `InvisibleEncoder`.

- [ ] **Step 3: Add the button that opens it**

In the "Show Phrases" dialog (where "Share via…" lives, ~L592) or the `KeyItemCard` action row, add a button that sets `showCoverShareFor = shareKey` (wire a new callback param through `KeyItemCard` if using the card, updating its call site ~L1074).

- [ ] **Step 4: Verify build + suite**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(crypto): sender Share via Cover Message flow"
```

---

### Task 10: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the complete verify command**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug :app:assembleFlorisDebug`
Expected: BUILD SUCCESSFUL; crypto + floris test counts, 0 failures, 0 errors.

- [ ] **Step 2: Confirm test totals did not regress**

Run: `grep -rho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testCryptoDebugUnitTest/*.xml | awk -F'"' '{t+=$2;f+=$6;e+=$8} END {print "crypto tests="t" failures="f" errors="e}'`
Expected: failures=0, errors=0; total ≥ 245 + new invite tests.

- [ ] **Step 3: Hand off for on-device verification**

Test APK: `app/build/outputs/apk/crypto/debug/app-crypto-debug.apk`. On-device checklist (commit after, per standing rule):
1. Turn on "Scan for key invitations" in Crypto Engine settings.
2. Sender: Key Management → Share via Cover Message → Copy; paste into a note; the cover text shows, invite invisible.
3. Path B: copy the cover message → keyboard Decrypt button → approval dialog opens → Save Contact → contact appears in Manage Keys with chosen alias.
4. Path A: with exchange mode on and the cover message on screen in another app → tap the "Key invite" bubble → approval dialog → Save.
5. Auto-shutoff: 5 minutes after enabling (or after one accept), the service stops surfacing bubbles.
```