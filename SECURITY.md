# Security Policy

## Reporting a Vulnerability

**DO NOT open public issues for security vulnerabilities.**

### Contact

**Email:** [mkprogrammer01@gmail.com]

### What to Include

When reporting a security issue, please provide:

1. **Description** — What is the vulnerability?
2. **Impact** — What can an attacker do?
3. **Affected component** — Which file/module is affected?
4. **Steps to reproduce** — How to trigger the vulnerability
5. **Proof of concept** — Code or screenshots (if applicable)
6. **Suggested fix** — If you have ideas (optional)

### Response Timeline

- **Acknowledgment:** Within 48-72 hours
- **Initial assessment:** Within 1 week
- **Fix development:** Depends on severity
- **Public disclosure:** Coordinated with reporter (typically 90 days)

### What Happens Next

1. We acknowledge your report
2. We investigate and assess severity
3. We develop and test a fix
4. We release a patched version
5. We publish a security advisory
6. We credit you (unless you prefer anonymity)

---

## Supported Versions

| Version | Supported | Status |
|---------|-----------|--------|
| 0.6.x   | ✅ Yes | Current beta |
| < 0.6   | ❌ No  | Outdated |

**Note:** Only the latest version receives security updates.

---

## Security Model

### Threat Model

#### What This Protects Against ✅

- **Passive surveillance** — Message content is encrypted
- **Server-side inspection** — Servers see only cover messages
- **Message tampering** — GCM authentication detects modifications
- **Replay attacks** — Random IV prevents replaying old messages
- **Casual forensics** — Keys are PIN-encrypted on device
- **Shoulder surfing** — Decrypted content hidden until Exchange Mode active

#### What This Does NOT Protect Against ❌

- **Compromised Android OS** — Rooted devices or malicious system apps
- **Screen recording malware** — Can capture decrypted overlays
- **Physical device access** — Unlocked device = full access
- **Keyloggers** — Can capture messages before encryption
- **Metadata analysis** — Who you talk to, when, how often
- **Advanced traffic analysis** — Timing, message length patterns
- **Quantum computing** — Not post-quantum resistant
- **Rubber-hose cryptanalysis** — Physical coercion to reveal keys

### Security Assumptions

This system assumes:

1. **Android OS is not compromised** — System components are trustworthy
2. **Device is physically secure** — Locked when unattended
3. **Messaging apps don't strip Unicode** — Platform preserves invisible characters
4. **Keys are kept secret** — Users protect PINs and recovery phrases
5. **Out-of-band key verification** — Users verify fingerprints via trusted channel

If any assumption is violated, security guarantees are void.

---

## Cryptographic Design

### Primitives

| Component | Algorithm | Parameters |
|-----------|-----------|------------|
| **Symmetric encryption** | AES-256-GCM | 256-bit key, 96-bit IV, 128-bit tag |
| **Key generation** | SecureRandom | 256 bits |
| **Key fingerprinting** | SHA-256 | First 4 bytes (32 bits) |
| **PIN hashing** | SHA-256 | 10,000 iterations, random 16-byte salt |
| **Export encryption** | PBKDF2-HMAC-SHA256 | 100,000 iterations, random 16-byte salt |

### Message Format

```
┌────────┬─────────────┬──────┬─────────────────────────┐
│Version │ Fingerprint │  IV  │ Ciphertext + Auth Tag   │
│ 1 byte │   4 bytes   │12 by │     Variable length     │
└────────┴─────────────┴──────┴─────────────────────────┘
```

**Version (1 byte):** Protocol version (currently `0x01`)  
**Fingerprint (4 bytes):** First 4 bytes of SHA-256(key) — identifies which key to use  
**IV (12 bytes):** Random initialization vector (96 bits for GCM)  
**Ciphertext + Tag:** AES-GCM output (plaintext + 16-byte authentication tag)

### Steganographic Encoding

#### Character Set

16 invisible Unicode characters used for nibble encoding:

```
Index | Unicode | Character Name
------|---------|------------------------------------------
  0   | U+200B  | Zero Width Space
  1   | U+200C  | Zero Width Non-Joiner
  2   | U+200D  | Zero Width Joiner
  3   | U+2060  | Word Joiner
  4   | U+2061  | Function Application
  5   | U+2062  | Invisible Times
  6   | U+2063  | Invisible Separator
  7   | U+2064  | Invisible Plus
  8   | U+206A  | Inhibit Symmetric Swapping
  9   | U+206B  | Activate Symmetric Swapping
 10   | U+206C  | Inhibit Arabic Form Shaping
 11   | U+206D  | Activate Arabic Form Shaping
 12   | U+206E  | National Digit Shapes
 13   | U+206F  | Nominal Digit Shapes
 14   | U+FEFF  | Zero Width No-Break Space (BOM)
 15   | U+00AD  | Soft Hyphen
```

#### Encoding Process

1. Each byte is split into high nibble (4 bits) and low nibble (4 bits)
2. Each nibble maps to one character from the table above
3. Result: 2 invisible characters per byte

**Example:**
```
Byte: 0xA7 (167 decimal)
High nibble: 0xA (10) → U+206C
Low nibble: 0x7 (7)   → U+2064
Encoded: U+206C U+2064
```

#### Signature Markers

**Start marker:** `U+2069 U+2068 U+2069 U+2068` (Pop + First Directional Isolate pattern)  
**End marker:** `U+2068 U+2069 U+2068 U+2069` (Reversed pattern)

These are NOT used in the encoding alphabet, so they uniquely delimit payloads.

### Key Storage

#### Android Keystore

Keys are stored using Android Keystore when available:

- Hardware-backed on devices with TEE/Secure Enclave
- Software-backed on older devices
- Encrypted with user's PIN-derived key
- Stored in `/data/data/[package]/files/keys/` (app-private storage)

#### PIN Protection

User PINs protect keys using:

```
salt = random(16 bytes)
iterations = 10,000
derived_key = SHA256^iterations(PIN + salt)
encrypted_key = AES-256-GCM(key_bytes, derived_key)
stored = salt || encrypted_key
```

**Why 10k iterations:** Balance between security and UX (instant unlock feel)

#### Export/Import

Exported keys use stronger derivation:

```
salt = random(16 bytes)
iterations = 100,000
derived_key = PBKDF2-HMAC-SHA256(password, salt, iterations)
encrypted_export = AES-256-GCM(key_data, derived_key)
```

**Why 100k iterations:** Exports are files (user can tolerate delay), so more iterations feasible

---

## Known Limitations

### Cryptographic Limitations

1. **Not post-quantum secure**
   - AES-256 is quantum-resistant
   - But key exchange (QR codes) is not
   - Future: Consider hybrid classical + post-quantum schemes

2. **No perfect forward secrecy**
   - Same key encrypts all messages
   - If key is compromised, all past messages are readable
   - Future: Implement key ratcheting (Signal Protocol-style)

3. **No sender authentication**
   - GCM authenticates ciphertext integrity, not sender identity
   - Relies on key fingerprint verification out-of-band
   - Future: Add digital signatures

### Steganographic Limitations

1. **Statistical detectability**
   - Large amounts of invisible characters are suspicious
   - Advanced analysis can detect non-random patterns
   - Mitigation: Keep messages short, use natural cover text

2. **Platform dependency**
   - Some apps strip invisible characters
   - Behavior varies by app version and platform
   - Mitigation: Use CompatibilityTester before relying on steganography

3. **Message length limits**
   - Encrypted payload doubles in size (nibble encoding)
   - Long messages may exceed platform limits
   - Mitigation: Show size warnings in UI

### Implementation Limitations

1. **Not professionally audited**
   - Crypto code written following best practices
   - Reviewed by contributors, not professional cryptographers
   - **Recommendation:** Don't use for high-security scenarios until audited

2. **Accessibility Service risk**
   - Requires broad permission to read screen text
   - Users must trust our implementation
   - Android can't verify we only read what we claim
   - Mitigation: Open source — anyone can audit

3. **Memory security**
   - Android doesn't allow secure memory wiping in Java/Kotlin
   - Sensitive data (keys, plaintext) may remain in RAM
   - Mitigation: Minimize lifetime of sensitive objects

4. **Timing attacks**
   - PIN verification uses constant-time comparison
   - But decryption errors may leak timing info
   - Mitigation: Decryption fails silently (no error details)

---

## Audit Status

### Current Status

**❌ NOT AUDITED**

This project has NOT undergone professional security audit by cryptographers.

### What Has Been Reviewed

- ✅ Code review by contributors
- ✅ Automated security scanning (GitHub CodeQL)
- ✅ Dependency vulnerability scanning (Dependabot)
- ❌ Professional cryptographic audit
- ❌ Penetration testing
- ❌ Formal verification

### Plans

We are seeking:

1. **Pro bono security audit** from qualified cryptographers
2. **Penetration testing** by security researchers
3. **Formal threat modeling** workshop
4. **Bug bounty program** (when funding allows)

**Security researchers:** If you're interested in auditing this project, please contact us.

---

## Best Practices for Users

### Key Management

✅ **DO:**
- Verify key fingerprints out-of-band (in person, trusted channel)
- Use strong, unique PINs for key protection
- Back up recovery phrases securely (offline, encrypted)
- Use separate keys for different contacts
- Rotate keys periodically

❌ **DON'T:**
- Share keys over the same channel you're securing
- Reuse PINs across devices/apps
- Store recovery phrases in plaintext
- Trust QR codes from untrusted sources
- Use default/weak PINs (1234, 0000, etc.)

### Operational Security

✅ **DO:**
- Test platform compatibility before relying on it
- Use natural cover messages (not random gibberish)
- Enable Exchange Mode only when needed
- Keep device locked when unattended
- Update to latest version regularly

❌ **DON'T:**
- Rely on steganography alone for high-risk communications
- Ignore size warnings (messages may get truncated)
- Use on compromised/rooted devices
- Trust devices you don't control
- Discuss sensitive operations over encrypted channels

### Threat-Specific Guidance

**Against casual surveillance:**
- ✅ Basic usage is sufficient
- ✅ Natural cover messages help

**Against corporate monitoring:**
- ⚠️ May detect invisible character patterns
- ⚠️ Use "No Cover" mode and external channels

**Against state-level adversaries:**
- ❌ This tool is NOT sufficient
- ❌ Use established tools (Signal, Tor, etc.)
- ❌ Consult security professionals

---

## Incident Response

### If You Suspect a Security Issue

1. **Stop using the affected feature** immediately
2. **Report to us** via email (see top of document)
3. **Don't disclose publicly** until coordinated disclosure
4. **Preserve evidence** (logs, screenshots)

### If Your Key Is Compromised

1. **Immediately delete the compromised key** from all devices
2. **Generate new key** with different PIN
3. **Notify contacts** via trusted out-of-band channel
4. **Exchange new keys** in person if possible
5. **Assume past messages are compromised**

### If You Find a Vulnerability

See "Reporting a Vulnerability" section above.

We appreciate responsible disclosure and will:
- Acknowledge your report promptly
- Work with you on fix timeline
- Credit you in security advisory (with permission)
- Provide acknowledgment letter for your portfolio

---

## Security Roadmap

### Short-term (v1.0)

- [ ] Professional cryptographic audit
- [ ] Penetration testing
- [ ] Formal threat modeling
- [ ] Improved key verification UX
- [ ] Security documentation expansion

### Medium-term (v1.1)

- [ ] Perfect forward secrecy
- [ ] Key rotation automation
- [ ] Deniable encryption layer
- [ ] Anti-forensics features
- [ ] Hardware security module support

### Long-term (v2.0+)

- [ ] Post-quantum encryption
- [ ] Threshold encryption
- [ ] Zero-knowledge proofs
- [ ] Decentralized key distribution
- [ ] Formal verification

---

## Responsible Disclosure

We follow responsible disclosure principles:

1. **Report privately** to us first
2. **Allow reasonable time** to fix (typically 90 days)
3. **Coordinate disclosure** timing with us
4. **We credit researchers** publicly (with permission)
5. **Publish security advisory** after fix is released

We will NOT:
- Sue or threaten legal action against good-faith researchers
- Demand you delete research or exploits
- Refuse to fix legitimate vulnerabilities

---

## Security Contact

**Primary:** [mkprogrammer01@gmail.com]  
**Backup 1:** [postbox32@zohomail.in]
**Backup 2:** [letterbox32@proton.me]

**Response time:** 48-72 hours for acknowledgment

---

## Acknowledgments

### Security Researchers

(Will be updated as vulnerabilities are found and fixed)

None yet — be the first!

### Inspiration

Security design inspired by:
- Signal Protocol (Double Ratchet, forward secrecy)
- PGP/GPG (Public key cryptography)
- Tor Project (Threat modeling)
- OWASP Mobile Security Project (Best practices)

---

## Legal

### Disclaimer

```
THIS SOFTWARE IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND.
USE AT YOUR OWN RISK.

The developers are NOT responsible for:
- Security breaches
- Data loss
- Legal consequences
- Physical harm
- Any other damages

Users are responsible for:
- Understanding limitations
- Following best practices
- Complying with local laws
- Proper key management
```

### Export Control

This software may be subject to export control regulations in some countries.

**Contains:** Strong cryptography (AES-256, SHA-256)  
**Classification:** Check your local laws before distribution

**Note:** Open source cryptography is generally exempt, but verify for your jurisdiction.

---

*Last updated: January 2025*

**Security is a process, not a product. Stay vigilant.**