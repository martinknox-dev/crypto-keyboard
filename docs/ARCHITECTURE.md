# Architecture — Crypto Keyboard

Crypto Keyboard is a FlorisBoard fork with an added **crypto flavor** (`app/src/crypto`) that provides steganographic encrypted messaging: AES-256-GCM ciphertext hidden inside innocent-looking text via invisible Unicode characters.

---

## 1. Module Map

```
app/src/
├── main/                     # FlorisBoard (upstream keyboard) + bridge hooks
│   └── .../ime/text/CryptoBarHook.kt     # Compose crypto bar + reflection bridge
│   └── .../ime/keyboard/KeyboardManager.kt  # intercepts typing into crypto fields
│   └── .../app/Routes.kt                # registers crypto settings screens
└── crypto/                   # The crypto flavor (package com.mkcreations.cryptokeyboard)
    ├── crypto/
    │   ├── CryptoEngine.kt        # AES-256-GCM, protocol v1/v2
    │   ├── InvisibleEncoder.kt    # zero-width steganographic encoding
    │   ├── MessageProcessor.kt    # high-level encrypt/decrypt pipeline
    │   ├── KeyGenerator.kt        # keygen, fingerprints, keyed hints, word phrases
    │   ├── PinHasher.kt           # PBKDF2-HMAC-SHA256 PIN hashing (v2 + legacy)
    │   ├── KeyExporter.kt         # password-protected key export/import
    │   ├── CoverGenerator.kt      # cover message generation (SecureRandom)
    │   ├── DecryptCache.kt        # bounded LRU of decrypted plaintexts
    │   ├── ReplayGuard.kt         # session-scoped replay detection
    │   ├── QrCodeHelper.kt        # QR key exchange payloads
    │   └── CompatibilityTester.kt # platform invisible-char testing
    ├── accessibility/
    │   ├── CryptoAccessibilityService.kt  # screen scanning + overlay lifecycle
    │   ├── ScreenScanner.kt               # finds nodes with hidden payloads
    │   └── OverlayManager.kt              # draws decrypted bubbles
    ├── ime/
    │   ├── CryptoBarComponent.kt   # legacy View-based crypto bar
    │   ├── CryptoBridge.kt         # bridge init/toggle
    │   ├── CryptoInputManager.kt   # encrypt-and-send flow
    │   └── SpacebarGestureHandler.kt
    ├── core/
    │   ├── SharedState.kt          # cross-process flags (plain prefs, no secrets)
    │   └── PanicHandler.kt         # shake/orientation panic triggers
    ├── storage/
    │   ├── SecureKeyStore.kt       # keys in EncryptedSharedPreferences
    │   └── SettingsStore.kt        # settings + PIN hash + lockout state
    ├── model/                      # CipherKey, CoverStyle, AppSettings
    └── ui/                         # Compose screens (settings, key mgmt, PIN gate, QR)
```

---

## 2. Wire Format (Protocol v2)

```
┌─────────┬────────────┬────────┬────────────────────────────┐
│ Version │  Key hint  │   IV   │   Ciphertext + Auth Tag    │
│ 1 byte  │  4 bytes   │ 12 B   │      variable length       │
└─────────┴────────────┴────────┴────────────────────────────┘
```

| Field | Size | Meaning |
|-------|------|---------|
| Version | 1 B | `0x02` = current (AAD-bound header, keyed hint). `0x01` = legacy (static fingerprint, no AAD) — still accepted on decrypt. |
| Key hint | 4 B | First 4 bytes of `HMAC-SHA256(key, "CryptoKeyboard/key-hint/v2")`. Lets the receiver pick the right key **without exposing a constant per-key identifier** to observers. |
| IV | 12 B | Random per message (96-bit GCM nonce). |
| Ciphertext + tag | var | AES-256-GCM over the UTF-8 plaintext; 128-bit tag appended. |

**AAD:** the 5 header bytes (`version ‖ key-hint`) are passed as GCM *additional authenticated data*. Tampering with the version or hint is detected at decryption (`AEADBadTagException` → treated as failure).

**Why not the static fingerprint on the wire?** The v1 format embedded `SHA-256(key)[0..4]` in every message — a constant per-contact identifier that lets an observer cluster messages by key. The keyed hint can only be computed by the key holder, so it can't be precomputed or correlated without the key.

**Legacy v1:** `[0x01][fingerprint 4B][IV 12B][ciphertext+tag]`, no AAD. Decrypt-only support.

---

## 3. Steganographic Encoding

- **Alphabet:** 16 invisible Unicode characters (U+200B, U+200C, U+200D, U+2060–U+2064, U+206A–U+206F, U+FEFF, U+00AD) — one per 4-bit nibble, so **2 invisible chars per byte**.
- **Markers:** start `U+2069 U+2068 U+2069 U+2068`, end `U+2068 U+2069 U+2068 U+2069` (built from directional isolates, *not* in the encoding alphabet).
- **Embedding:** the payload is distributed in chunks between the cover message's visible characters, so the hidden data is spread out instead of appended as one block.
- **Cover sanitization:** characters from the encoding alphabet or marker set are stripped from the cover before embedding — otherwise a pasted zero-width space would corrupt the payload.
- **Size:** `totalChars = coverLength + 2 × (17 + plaintextBytes) + 8`. Warned past 3000, hard-rejected past `InvisibleEncoder.MAX_TOTAL_CHARS` (4000).
- **No-cover mode:** the raw ciphertext is sent as visible Base64.

**Known platform caveats:** some apps strip invisible characters; bidi reordering can reorder the isolate-based markers in RTL contexts. Use the built-in Compatibility Tester.

---

## 4. Key Hierarchy & Storage

```
User PIN ──PBKDF2-HMAC-SHA256 (600k)──▶  PIN hash (stored, gates settings UI)
                                            │
Random 256-bit key (SecureRandom) ──────────┼──▶ EncryptedSharedPreferences (AES256-GCM
                                            │     hardware-backed master key)
                                            ├──▶ fingerprint = SHA-256(key)[0..4]   (local id, QR)
                                            └──▶ key hint    = HMAC-SHA256(key, label)[0..4]  (on the wire)
Recovery phrase: 32 words (1 word/byte) + 33rd checksum word = SHA-256(key)[0]
Export file: PBKDF2-HMAC-SHA256 (600k, count stored in file) + AES-256-GCM
```

- **At rest:** keys live in `EncryptedSharedPreferences` (`cipher_keys_secure`). The app-lock PIN gates the *UI*; background decryption (Exchange Mode) necessarily keeps keys usable in the IME/accessibility processes.
- **PIN lockout:** failed PIN attempts back off exponentially (5s → 5 min cap), tracked in `SettingsStore`.
- **Backup:** recovery phrase (33 words, checksummed) or password-protected export file.
- **Exchange:** QR payload is JSON containing the **raw key** — always verify the fingerprint out-of-band.

---

## 5. Data Flow

**Sending:**
```
Crypto bar (Compose) → CryptoBarHook.doEncrypt → CryptoInputManager.encryptMessage
  → MessageProcessor.encryptAndEmbed → CryptoEngine.encrypt (AES-256-GCM, AAD)
  → InvisibleEncoder.embedInCover → InputConnection.commitText + editor action
```

**Receiving (Exchange Mode):**
```
AccessibilityEvent → CryptoAccessibilityService (debounced)
  → ScreenScanner.findEncryptedNodes (skips password fields)
  → DecryptCache? → else MessageProcessor.detectAndDecrypt
      → InvisibleEncoder.extract → key selection by keyed hint (v2) / fingerprint (v1)
      → CryptoEngine.decrypt → ReplayGuard.checkAndRecord
  → OverlayManager.showOverlay (bubble above the message)
```

**Manual receiving:** copy message → 📋🔓 clipboard-decrypt button in the crypto bar.

---

## 6. State & Lifecycle

| State | Where | Lifetime |
|-------|-------|----------|
| Keys | `SecureKeyStore` (EncryptedSharedPreferences) | Until deleted/panic-wiped |
| PIN hash + lockout | `SettingsStore` (EncryptedSharedPreferences) | Until changed/disabled |
| Flags (modes, active key) | `SharedState` (plain prefs) | Session; **no secrets** |
| Decrypted plaintext | `DecryptCache` (bounded LRU, 100) | ≤ 5 min, wiped on lock/panic/app-switch |
| Replay state | `ReplayGuard` (bounded LRU, 512) | Session, wiped on panic/service destroy |
| Overlays | `OverlayManager` (WindowManager views) | Until removed (scroll/app-switch/panic) |

**Panic** (shake / face-down, configurable): removes overlays, wipes caches + replay state, disables Exchange Mode, optionally switches keyboard, locks the app, and optionally wipes all keys (explicit opt-in).

---

## 7. Testing

```
app/src/testCryptoDebug/kotlin/.../crypto/
├── CryptoEngineTest.kt        # v2 round trip, AAD tamper, v1 legacy, format checks
├── InvisibleEncoderTest.kt    # round trips, sanitization, corruption, stats
├── KeyGeneratorTest.kt        # keys, hints, phrases, checksum, legacy phrases
├── PinHasherTest.kt           # PBKDF2 round trip, legacy verify, malformed inputs
├── DecryptCacheTest.kt        # expiry, LRU bound, ordering
├── ReplayGuardTest.kt         # first/same/replayed semantics, bounds
├── KeyExporterTest.kt         # export/import, legacy 100k, iteration bounds
└── EndToEndPipelineTest.kt    # full encrypt→embed→extract→decrypt→replay pipeline
```

Run with: `./gradlew testCryptoDebugUnitTest`

The tests live in the `testCryptoDebug` variant source set so the upstream `floris` flavor's test compilation is unaffected.

---

## 8. Threat Model (Summary)

**Protected:** passive surveillance of content, server-side inspection, tampering (GCM + AAD), casual forensics (encrypted prefs + PIN gate), verbatim re-sends (session replay detection), password-field leakage (scanner skips them).

**Not protected:** compromised/rooted devices, screen recording, keyloggers, metadata (who/when/length), statistical stego detection, quantum adversaries, rubber-hose. See `SECURITY.md` and `docs/SECURITY-REVIEW.md` for the full model.
