# Roadmap

This document outlines development priorities for Crypto Keyboard.

**No ETAs** — Features ship when ready, not by deadlines.

---

## Current: v0.6-beta

**Status:** Core functionality complete, needs polish and testing

### What's Working ✅

- AES-256-GCM encryption/decryption
- Steganographic encoding (16 invisible characters)
- Keyboard UI with crypto mode
- Secret + cover message input
- Auto-generated cover messages
- Multi-key support with fingerprints
- QR code key exchange
- Accessibility Service screen scanning
- Overlay decryption display
- Decryption cache
- PIN-protected key storage
- Password-protected key export
- Compatibility testing tool
- Clipboard decryption

### In Progress 🚧

- UI polish and animations
- Battery optimization for Exchange Mode
- Error handling improvements

### Security Hardening — Completed 2026-10 ✅

See [docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md) for the full analysis (18 findings).

- ✅ PBKDF2-HMAC-SHA256 (600k) PIN hashing, replacing 10k-round SHA-256
- ✅ Working app-lock PIN gate (set/change/disable) with exponential lockout
- ✅ Protocol v2: GCM AAD binds the header (version + key hint)
- ✅ Keyed per-key HMAC hint on the wire (no constant fingerprint → no key clustering)
- ✅ Session-scoped replay detection (re-sent ciphertexts flagged)
- ✅ 33-word recovery phrases with checksum word (typo detection; legacy 32-word import kept)
- ✅ Cover message sanitization (stego chars can no longer corrupt payloads)
- ✅ Panic mode now actually removes decrypted overlays (was a TODO)
- ✅ Send no longer deletes the user's typed text
- ✅ Accessibility scanner skips password fields
- ✅ Decrypt cache bounded (LRU); draft secrets no longer persisted in plaintext prefs
- ✅ Key export: 600k PBKDF2, iteration count stored per file, legacy imports work
- ✅ ~60 unit tests for the crypto core (`testCryptoDebugUnitTest` in CI)
- ✅ Documentation corrected (replay/PIN claims) + architecture & review docs added

---

## v1.0 — Production Ready

**Goal:** Stable release suitable for daily use

### Must Have

- [ ] **Security audit** — Professional cryptographer review
- [ ] **Platform verification**
  - [ ] WhatsApp tested
  - [ ] Telegram tested
  - [ ] Signal tested
  - [ ] SMS tested
  - [ ] Discord tested
- [ ] **Performance**
  - [ ] < 5% battery impact in Exchange Mode
  - [ ] < 100ms encryption time
  - [ ] Smooth scrolling with overlays
- [ ] **Reliability**
  - [ ] Handle all edge cases gracefully
  - [ ] No crashes in common workflows
  - [ ] Proper error messages
- [ ] **Crypto test coverage** (partially done — see hardening above; on-device
  instrumented tests for the accessibility overlay path still needed)
- [ ] **Documentation**
  - [ ] Complete user guide
  - [ ] Security documentation
  - [ ] API reference
  - [ ] Contribution guide
- [ ] **Distribution**
  - [ ] Signed APK releases
  - [ ] F-Droid listing
  - [ ] Installation guide

### Should Have

- [ ] Panic button (quick wipe of all keys)
- [ ] Key verification improvements (better fingerprint comparison)
- [ ] Onboarding wizard for first-time setup
- [ ] Settings UI (cache duration, auto-decrypt timeout)
- [ ] Multi-language support

### Nice to Have

- [ ] Theme support (dark/light)
- [ ] Key backup reminders
- [ ] Usage statistics (local only)
- [ ] Export logs for debugging

---

## v1.1 — Enhanced Security

**Goal:** Advanced security features

### Planned

- [ ] **Perfect forward secrecy**
  - Ephemeral session keys
  - Key ratcheting
  - Automatic key rotation

- [ ] **Deniable encryption**
  - Multiple valid plaintexts for same ciphertext
  - Plausible deniability layer

- [ ] **Anti-forensics**
  - Secure memory wiping
  - Minimal device traces
  - Auto-delete after read

- [ ] **Key management**
  - Key expiration
  - Automatic key rotation
  - Key revocation

### Research Required

- [ ] Post-quantum encryption (hybrid classical + PQ)
- [ ] Threshold encryption (multi-party key sharing)
- [ ] Statistical steganography analysis resistance
- [ ] Traffic analysis resistance

---

## v2.0 — Platform Expansion

**Goal:** Beyond Android keyboard

### Ideas (Long-term)

- [ ] **iOS keyboard** — Port to Apple ecosystem
- [ ] **Desktop app** — Encrypt files, documents
- [ ] **Browser extension** — Web-based encryption
- [ ] **Group messaging** — Shared keys for groups
- [ ] **File encryption** — Hide files in images (steganography)
- [ ] **Cross-platform sync** — Encrypted key backup (optional)

---

## Backlog

**Features under consideration:**

- Voice message encryption
- Video encryption
- End-to-end encrypted file sharing
- Decentralized key distribution (DHT/blockchain)
- Hardware security module support
- Biometric authentication for keys
- Self-destructing messages
- Screenshot prevention
- Watermarking for leak detection

---

## Out of Scope

**Will NOT build:**

- ❌ Cryptocurrency features (despite the name)
- ❌ Cloud sync / centralized servers
- ❌ Analytics or telemetry
- ❌ In-app purchases
- ❌ Ads
- ❌ Proprietary messaging app

**Why:** Privacy-first means local-only, open-source, and zero tracking.

---

## Community Input

### How to Influence Priorities

1. Open [feature requests](https://github.com/martinknox-dev/crypto-keyboard/issues/new)
2. Vote with 👍 on existing issues
3. Discuss in [GitHub Discussions](https://github.com/martinknox-dev/crypto-keyboard/discussions)
4. Contribute code for features you want

### Current Top Requests

(Will be updated as community grows)

---

## Development Philosophy

- **Security > Features** — Won't add features that compromise security
- **Privacy > Convenience** — Local-only, even if it's harder
- **Open > Closed** — Transparent development, auditable code
- **Stable > Fast** — Ship when ready, not by deadlines

---

## Success Metrics

For v1.0:

- ✅ Zero known security vulnerabilities
- ✅ Works on major messaging platforms
- ✅ < 5% battery drain in Exchange Mode
- ✅ Complete documentation

---

*Roadmap updated: January 2025*

**Timeline:** Features ship when they're secure and tested, not by calendar dates.