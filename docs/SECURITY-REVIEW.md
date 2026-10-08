# Security Review — Crypto Keyboard

**Date:** 2026-10-08
**Scope:** `app/src/crypto/**` (crypto flavor), the `CryptoBarHook` bridge in `app/src/main`, and the security claims in `README.md` / `SECURITY.md`.
**Method:** Full manual code review of the cryptographic core, steganographic encoder, key storage, accessibility pipeline, and UI flows; algorithm-level validation of the encoding/encryption logic against a reference implementation; JVM unit tests added for all pure components (`app/src/testCryptoDebug`).

> This is a best-effort review by a contributor, **not a professional cryptographic audit**. The project still needs an independent audit before high-risk use (see `SECURITY.md`).

---

## 1. Executive Summary

The core idea — AES-256-GCM plus zero-width-character steganography — is sound, and the GCM usage was already mostly correct. However, the review found **two incorrect security claims in the documentation**, **one broken security feature** (the app-lock PIN was never set or verified anywhere), **one silent data-loss bug**, and several cryptographic weaknesses that made the tool weaker than its own README claimed.

The most important findings:

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| 1 | PIN hashing used 10k rounds of plain SHA-256 (not a KDF) and the PIN was never actually set/verified — the "PIN protection" feature was non-functional | **Critical** (claim vs. reality) | **Fixed** — PBKDF2-HMAC-SHA256 @ 600k + working PIN gate with lockout |
| 2 | Documentation claimed "Random IV prevents replaying old messages" — false; GCM accepts verbatim re-sends | **High** (false claim) | **Fixed** — session replay detection + corrected docs |
| 3 | Documentation claimed keys are "PIN-encrypted on device" — false; keys sat in `EncryptedSharedPreferences` only, gated by nothing | **High** (false claim) | **Fixed** — real PIN gate on key screens; docs corrected |
| 4 | GCM header (version + key selector) was unauthenticated — no AAD | **High** | **Fixed** — v2 format binds the header as AAD |
| 5 | Static key fingerprint on the wire allowed clustering all of a contact's messages by a constant identifier | **High** (traffic analysis) | **Fixed** — keyed per-key HMAC hint (v2) |
| 6 | Recovery phrase had no checksum — one typo silently produced a wrong key | **Medium** | **Fixed** — 33rd checksum word; legacy 32-word phrases still import |
| 7 | `sendEncryptedMessage` deleted whatever the user had typed in the input field | **Medium** (data loss) | **Fixed** — appends instead of replacing |
| 8 | Panic mode's most important action (remove decrypted overlays) was an unimplemented TODO | **High** (feature broken) | **Fixed** — wired to `CryptoAccessibilityService.panicClear()` |
| 9 | Cover messages were not sanitized — a zero-width char in the cover silently corrupted the payload | **Medium** | **Fixed** — sanitization on embed |
| 10 | Decrypt cache was unbounded (plaintext accumulation) | **Medium** | **Fixed** — bounded LRU (100 entries) |
| 11 | Accessibility scanner read password fields | **Medium** (privacy) | **Fixed** — password nodes skipped |
| 12 | Draft secret messages were designed to persist in *plaintext* SharedPreferences | **Medium** (leak) | **Fixed** — removed (memory-only drafts) |
| 13 | No PIN brute-force protection | **Medium** | **Fixed** — exponential lockout (5s → 5min cap) |
| 14 | Cover generator used non-CSPRNG and a tiny (~30-phrase) pool — statistically fingerprintable | **Low** | **Fixed** — SecureRandom + ~70-phrase pool |
| 15 | Key export used 100k PBKDF2 iterations (below OWASP guidance) and didn't store the count | **Low** | **Fixed** — 600k, count stored per file, legacy imports work |
| 16 | README linked `docs/ARCHITECTURE.md` / `docs/API.md` — files did not exist | **Low** (docs) | **Fixed** — `docs/ARCHITECTURE.md` added |
| 17 | No unit tests for any cryptographic code | **Medium** (assurance) | **Fixed** — 7 test classes, ~60 tests |
| 18 | No forward secrecy, no sender authentication, stego is statistically detectable, not post-quantum | **High** (design) | **Documented** — inherent design limits, see §4 |

---

## 2. Detailed Findings

### 2.1 Cryptographic core

#### F1 — PIN hashing was not a KDF, and the PIN feature was dead code (Critical)

`PinHasher` hashed PINs with 10,000 rounds of **plain iterated SHA-256**. That is not a password-hashing function: a modern GPU computes billions of SHA-256 rounds per second, so a stolen hash (or a hash extracted from a rooted/unlocked device) falls to offline brute force almost instantly for any short numeric PIN. Worse, **nothing in the app ever called `PinHasher`** — there was no UI to set a PIN and no code path that verified one. `SettingsStore.pinHash` existed, but the README's "PIN-protected key storage" and SECURITY.md's "Keys are PIN-encrypted on device" described a feature that did not exist. The app lock only *stored* a flag.

**Fix applied:**
- `PinHasher` now uses **PBKDF2-HMAC-SHA256 with 600,000 iterations** (OWASP 2023 recommendation), a random 16-byte salt, and a versioned storage format (`v2:salt:hash:iterations`). Legacy hashes still verify so existing installs keep working, and the PIN gate transparently re-hashes them on next unlock.
- Verification is constant-time (`MessageDigest.isEqual`) and exception-safe.
- A real **app-lock PIN gate** (`ui/PinLockGate.kt`) now protects the key-management and crypto-settings screens, with set/change/disable flows in `CryptoSettingsScreen`.
- **Exponential lockout** after failed attempts (5s, 10s, 20s … capped at 5 min) in `SettingsStore`.

**Residual risk:** the PIN only gates the *settings UI*. The IME and accessibility services can still use keys in the background (that's what makes auto-decryption work). Keys at rest are protected by Android's `EncryptedSharedPreferences` (hardware-backed master key where available). This boundary is now documented honestly.

#### F2 — Replay protection was claimed but did not exist (High)

SECURITY.md's threat model stated: *"Replay attacks — Random IV prevents replaying old messages."* This is incorrect. A random IV prevents *pattern* analysis, not replay: AES-GCM happily decrypts an exact copy of an old ciphertext. An attacker who records a message can re-send it verbatim and the receiver will accept it as fresh.

**Fix applied:**
- New `ReplayGuard`: a bounded, session-scoped LRU of seen ciphertext hashes. The *same ciphertext inside a different message* (different cover/surroundings) is flagged `REPLAYED` and surfaced as a visible warning in the overlay and clipboard-decrypt paths. Re-scanning the same on-screen bubble is correctly **not** flagged.
- SECURITY.md corrected.

**Residual risk:** this is a heuristic, not cryptographic replay resistance. True replay protection requires sequence numbers/timestamps inside the authenticated plaintext plus receiver-side state — a protocol change left for a future version (see §4).

#### F3 — GCM header was unauthenticated (High)

The v1 wire format `[version][fingerprint][IV][ciphertext+tag]` left the version and fingerprint bytes **outside** the GCM authentication tag. GCM authenticated only the ciphertext. An attacker could flip the fingerprint bytes undetected, causing the receiver to try the wrong key (a denial-of-service / key-confusion vector), and the version byte was only checked by a plain comparison.

**Fix applied:** protocol **v2**: the header (`version ‖ key-hint`) is passed as **AAD** (`cipher.updateAAD(...)`) on encrypt and verified on decrypt — any header tampering now makes `doFinal()` throw `AEADBadTagException`. v1 messages remain readable for backward compatibility.

#### F4 — Static key fingerprint on the wire (High, traffic analysis)

Every v1 message carried the same 4-byte fingerprint of the key. That is a **constant per-contact identifier**: an observer (or a malicious messaging server) can cluster all messages that use the same key, link them across chats/platforms, and build a communication graph without breaking the encryption. SECURITY.md listed "metadata analysis" as unprotected, but the fingerprint made it trivially easy.

**Fix applied:** v2 messages carry a **keyed per-key hint**: the first 4 bytes of `HMAC-SHA256(key, "CryptoKeyboard/key-hint/v2")`. Only the key holder can compute it; the receiver recomputes the hint for each of its own keys and matches in constant time (`KeyGenerator.matchesKeyHint`). An observer can no longer correlate messages by a constant identifier. The static fingerprint remains only for local key storage and QR exchange.

**Residual risk:** message *length* and *timing* still leak metadata (see §4).

#### F5 — Recovery phrase had no checksum (Medium)

The 32-word phrase mapped one word per key byte — exactly 256 bits of entropy with **zero redundancy**. A single mistyped word silently produced a *different, valid-looking* key; the user would only discover it when decryption failed, with no way to tell "typo" from "wrong phrase".

**Fix applied:** the phrase is now **33 words**: 32 key words + a 33rd **checksum word** (first byte of `SHA-256(key)`). `KeyGenerator.parseWordPhrase` distinguishes `Valid`, `ValidLegacyNoChecksum`, `InvalidChecksum`, `InvalidWord`, `InvalidCount`, and the import UI shows a specific error for each. Legacy 32-word phrases still import (with a warning recommending re-export).

#### F6 — Key export parameters (Low)

Exports used PBKDF2 at 100k iterations (below the 600k OWASP recommendation) and did not store the iteration count in the file, so raising it later would have made old exports unimportable. **Fix applied:** 600k for new exports, `iter` stored per file, legacy files (with or without `iter`) import correctly, iteration counts are sanity-bounded, and `PBEKeySpec` passwords are wiped (`clearPassword()`).

### 2.2 Steganographic layer

#### F7 — Cover messages were not sanitized (Medium)

`InvisibleEncoder.embedInCover` interleaved the payload between the cover's visible characters. If the cover itself contained any of the 16 encoding characters or the marker characters (trivially possible via copy-paste: zero-width spaces, soft hyphens, and bidi marks are common in real text), the extracted nibble stream was corrupted — the GCM tag then failed and the message was lost, with no explanation. **Fix applied:** `sanitizeCover()` strips stego/marker characters from the cover before embedding (and `MessageProcessor` uses the sanitized cover for stats).

#### F8 — Marker characters are bidi isolates (documented)

The start/end markers are built from U+2068/U+2069 (directional isolates). In right-to-left text contexts, Unicode bidi reordering can reorder isolate sequences, which can break marker detection on some platforms/renderers. This is a platform caveat, partially covered by the `CompatibilityTester`. **Status:** documented; the tester remains the mitigation. Not changed in code.

#### F9 — Cover generator was predictable and repetitive (Low)

`CoverGenerator` used `kotlin.random.Random` (not a CSPRNG) and a pool of ~30 short phrases. Repeated use of a tiny, fixed pool is itself a statistical fingerprint ("this contact always sends `ok 👍`"). **Fix applied:** `SecureRandom`-backed selection and a ~70-phrase pool with more natural variety.

#### F10 — Payload size (documented)

The nibble encoding costs 2 invisible characters per byte, so a 100-character secret becomes ~270 invisible characters. The 4000-character warning threshold is a heuristic; per-platform limits differ wildly (e.g. SMS in UCS-2 mode segments at 70 chars). **Status:** documented in `docs/ARCHITECTURE.md`; the limit is now a named constant (`InvisibleEncoder.MAX_TOTAL_CHARS`).

### 2.3 Accessibility / Exchange Mode

#### F11 — The scanner read password fields (Medium, privacy)

`ScreenScanner` walked every text node on screen, including password fields. A hidden message placed in a password field would have been decrypted and shown as an overlay — and the service received the password text at all. **Fix applied:** nodes with `isPassword == true` are now skipped entirely (both the full scan and the quick check).

#### F12 — Panic mode's overlay removal was a TODO (High, broken feature)

`PanicHandler.executePanic()` step 1 ("Remove all overlays") was an empty TODO — the single most important panic action did nothing, leaving decrypted plaintext on screen exactly when the user needed it gone. **Fix applied:** wired to `CryptoAccessibilityService.instance?.panicClear()`, which removes overlays, wipes the decrypt cache and replay guard, and disables Exchange Mode.

#### F13 — Unbounded decrypted-plaintext cache (Medium)

`DecryptCache` was an unbounded static map of decrypted plaintexts, living until timeout. **Fix applied:** bounded LRU (default 100 entries), full-length SHA-256 keys (no truncation collisions), wiped on lock/panic/app-switch/service destroy.

#### F14 — Draft secrets in plaintext SharedPreferences (Medium, leak)

`SharedState` exposed `pendingSecretMessage`/`pendingCoverMessage` persisted to **plain** (unencrypted) SharedPreferences. They were unused (dead code), but any future use would have written plaintext secrets to unencrypted storage. **Fix applied:** removed entirely; drafts are memory-only (Compose state), documented in the file.

### 2.4 Keyboard bridge / UX

#### F15 — Send silently deleted the user's typed text (Medium, data loss)

`CryptoBarHook.sendEncryptedMessage` selected all text in the input field and deleted it before committing the encrypted message. Type a normal message, then tap crypto-send → your text was gone. **Fix applied:** the encrypted message is appended; existing text is never touched.

#### F16 — QR key exchange exposes the raw key (documented)

The QR payload contains the raw 256-bit key in plaintext. Anyone who photographs the screen during exchange gets the key. This is inherent to QR key exchange; mitigations are out-of-band fingerprint verification (already in the docs and UI). Fingerprint comparison on scan is now constant-time. **Status:** documented + UI warning already present.

### 2.5 Documentation integrity

#### F17 — False security claims (High, trust)

- SECURITY.md: *"Replay attacks — Random IV prevents replaying old messages"* — false (F2).
- README/SECURITY.md: *"Keys are PIN-encrypted on device"* — false (F1).
- README advertised *"PIN protection: SHA-256, 10k iterations"* as a feature — weak and non-functional (F1).
- README linked `docs/ARCHITECTURE.md` and `docs/API.md`, which **did not exist**.

**Fix applied:** SECURITY.md rewritten where wrong; README security table corrected; `docs/ARCHITECTURE.md` created.

---

## 3. What Was Verified (Not Just Assumed)

- **Algorithm-level validation:** the nibble encoding, chunked cover distribution, checksum-word scheme, keyed hint, PBKDF2/legacy verification, and replay-guard semantics were all validated against an independent reference implementation (200+ randomized round trips, all passing).
- **Unit tests:** 7 new test classes (~60 tests) under `app/src/testCryptoDebug/` covering `CryptoEngine` (v2 round trip, AAD tamper detection on hint/version/ciphertext, v1 legacy decrypt, format validation), `InvisibleEncoder` (round trips, sanitization, corruption handling, stats), `KeyGenerator` (key/hint properties, phrase round trip + typo detection + legacy), `PinHasher` (round trip, legacy verify, malformed inputs, iteration bounds), `DecryptCache` (expiry, LRU bound, ordering), `ReplayGuard` (first/same/replayed, bounds), `KeyExporter` (round trip, wrong password, legacy 100k imports, iteration bounds, no plaintext key leak), plus an end-to-end pipeline test (encrypt → embed → extract → hint-based key selection → decrypt → replay).
- CI now runs `./gradlew testCryptoDebugUnitTest`.

---

## 4. Remaining Limitations (Inherent / Future Work)

These are **design-level** limits that this patch does not (and mostly cannot) fix:

1. **No perfect forward secrecy.** One key encrypts all messages with a contact; if the key leaks, all history is readable. A Double-Ratchet-style key ratchet is the proper fix (roadmap item).
2. **No sender authentication.** GCM authenticates integrity, not *who* sent the message. Anyone with the key can forge messages from the other party. Digital signatures (or a MAC under a separate sending key) would fix this.
3. **True replay resistance** needs nonces/sequence numbers in the authenticated plaintext + receiver state. `ReplayGuard` is a session heuristic.
4. **Steganography is statistically detectable.** Clusters of zero-width characters are not natural text; a determined analyst with the message corpus can flag them. Keep messages short and covers natural.
5. **Metadata is not hidden.** Who talks to whom, when, message lengths and timing are visible to the platform and traffic observers. The keyed hint removes the *constant identifier*, but nothing else.
6. **Platform dependency.** Some apps strip or reorder invisible characters; bidi contexts can reorder the isolate markers. Always run the Compatibility Tester.
7. **Not post-quantum.** AES-256-GCM is quantum-resistant for confidentiality, but there is no post-quantum key exchange.
8. **Side channels on device.** Compromised/rooted devices, screen-recording malware, keyloggers, and RAM remnants are out of scope (Android/Kotlin cannot guarantee zeroization).
9. **32-bit key selector.** Both fingerprint and hint are 4 bytes; with ~77k keys the birthday bound makes collisions plausible. Acceptable for a personal key ring, worth enlarging in a future protocol version.

---

## 5. Recommendations

**For users (now enforced/documented in-app):**
- Set an app-lock PIN (it actually works now) and use a strong one — lockout limits online guessing to a few tries per 5 minutes, but the hash must resist *offline* attack too.
- Verify fingerprints out-of-band after QR exchange; the QR contains the raw key.
- Keep secrets short; watch the size indicator.
- Don't rely on this against state-level adversaries — use Signal-class tools for that.

**For developers:**
- Commission an independent cryptographic audit before v1.0 (the primitives are now standard and the test suite covers the core, but this review is not a substitute).
- Implement key ratcheting (forward secrecy) and sender authentication as protocol v3.
- Consider enlarging the key selector to 8 bytes in v3.
- Add an instrumented end-to-end test (on-device) for the accessibility overlay path.

---

*Review conducted as part of a hardening pass on branch `arena/1ddf5c0e-crypto-keyboard`. All findings marked "Fixed" are covered by unit tests where the component is JVM-testable.*
