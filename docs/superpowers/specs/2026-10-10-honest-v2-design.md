# Honest v2 — Crypto Hardening Design Spec

Date: 2026-10-10
Status: Approved (specification supplied by the project owner in conversation; transcribed here)

## Background

A security review (independently verified against the codebase line-by-line) confirmed
12 of 13 findings: dead PIN/app-lock features advertised in docs, false replay-protection
claim in SECURITY.md, unauthenticated GCM header, static fingerprint on the wire, panic
overlay removal as an unimplemented TODO, missing recovery-phrase checksum, password
fields scanned by the accessibility service, unbounded plaintext cache, plaintext draft
storage, unsanitized cover text, tiny fingerprintable cover pool, and a hand-rolled
10k-round SHA-256 PIN hash. The one incorrect claim ("send wipes input before commit"
in `CryptoBarComponent`) was found to be true in the production path
(`CryptoBarHook.sendEncryptedMessage`), which is tracked as a residual item.

The goal is a full fix pass that closes every verified finding while keeping existing
v1 contacts working.

## 1. Wire Protocol v2 & Trial Decryption

- Message format v2: `[1 byte version=2][12 bytes IV][N bytes ciphertext + GCM tag]`.
  The 4-byte fingerprint slot is removed entirely (v2 header is 13 bytes).
- AAD: the 13-byte header (version + IV) is passed to `cipher.updateAAD()` on both
  encrypt and decrypt, so any tampering with version or IV fails authentication.
- Obfuscated wire: no static per-contact identifier is transmitted in v2.
- Receiver lookup: the receiver iterates over all stored contact keys; AES-GCM's
  `AEADBadTagException` guarantees only the matching key produces a valid tag.
- Backward compatibility: version byte 1 → legacy v1 path (fingerprint extraction,
  key lookup by fingerprint, decrypt without AAD) so existing contacts keep working.
- Encryption always produces v2.

## 2. Receiver-Side Anti-Replay

- Bounded LRU cache: 2,000 entries of SHA-256 payload hashes with timestamps,
  TTL 30 days, both enforced (size eviction + time expiry).
- Duplicate payloads are rejected before decryption; a payload is marked seen only
  after a successful decryption.
- Cache is in-memory and per-device; SECURITY.md must document this as best-effort
  deduplication against verbatim re-sends, not a monotonic ratchet/session binding.

## 3. PIN, App Lock & Key Derivation

- `PinHasher` switches to `PBKDF2WithHmacSHA256`, 600,000 iterations, 16-byte
  SecureRandom salt, stored as `pbkdf2$600000$salt$hash`; verification uses
  constant-time comparison (`MessageDigest.isEqual`).
- Rate limiting: exponential backoff after 5 failed attempts (base 5 s, doubling,
  capped at 5 min), state persisted across restarts.
- App lock actually gates: `SettingsStore.isAppLocked()` is checked in the keyboard
  display lifecycle (`CryptoBarHook.onKeyboardShow`); when locked, an `AppLockActivity`
  demands the PIN and calls `recordUnlock()` on success.
- Safety: lock can never engage when no PIN is configured (prevents panic-lockout),
  and the default lock timeout becomes 300 s (a 0 s timeout made the lock a no-op
  relock loop).

## 4. Hardening & Bug Fixes

- Recovery phrase gains a 33rd checksum word derived from `SHA-256(key)[0]`;
  import validates it before deriving the key. Legacy 32-word phrases remain
  importable (no checksum available) with a warning.
- Cover text is sanitized (all 16 encoding characters + the two marker characters
  U+2068/U+2069 stripped) before embedding, so pasted zero-width characters cannot
  corrupt the payload. Trade-off documented: ZWJ emoji sequences in covers lose
  their joiner.
- `ScreenScanner` skips nodes where `node.isPassword` is true or the hint indicates
  a password/PIN field.
- Panic mode: `PanicHandler.executePanic()` removes all active overlays via an
  `OverlayManager` companion registry; the handler itself is instantiated and started
  by the accessibility service when `panicEnabled` (it was never instantiated before).
- `DecryptCache` gets a 500-entry LRU cap.
- NO_COVER messages (raw Base64 payload) get a decode path in `detectAndDecrypt`.
- Dead plaintext draft persistence (`SharedState.pendingSecretMessage`,
  `pendingCoverMessage`, `clearPendingMessages`) is deleted.
- Catch-all `Exception -> null` sites in `CryptoEngine`, `InvisibleEncoder`,
  `MessageProcessor`, `KeyExporter`, `QrCodeHelper` become typed sealed results;
  call sites map them to user-facing errors.

## 5. Verification & Docs

- JVM unit tests (kotest FunSpec, flavor source set `app/src/testCrypto/`): v2
  roundtrip, v1 legacy decryption, tampered AAD rejection, replay rejection,
  recovery-phrase typo detection, cover zero-width stripping, plus base64 roundtrip,
  LRU/expiry bounds, PBKDF2 format, backoff schedule, app-lock policy, password-node
  filter.
- README.md and SECURITY.md updated to describe the real architecture, remove the
  false replay/PIN/constant-time claims, and state residual limitations honestly.

## Residual / Out of Scope (documented, not fixed here)

- `CryptoBarHook.sendEncryptedMessage` replaces pre-existing text in the target app's
  input field (batched with the commit, so atomic, but destructive to a draft the user
  had typed). Needs a product decision on replace-vs-append.
- Cover-pool diversity for `CoverGenerator` remains small (documented).
- v1 messages inherently still carry the fingerprint (legacy receive path only).
