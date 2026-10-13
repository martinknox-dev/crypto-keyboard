<div align="center">
  <img width="120" height="120" src=".github/repo_icon.png" alt="Crypto Keyboard icon">

  # Crypto Keyboard

  ### Invisible Encrypted Messaging for Android

  **Send encrypted messages that look completely normal**

  [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
  [![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://www.android.com/)
  [![Version](https://img.shields.io/badge/Version-0.6.0--alpha02-orange.svg)](https://github.com/martinknox-dev/crypto-keyboard/releases)
  [![Based on FlorisBoard](https://img.shields.io/badge/Based%20on-FlorisBoard-purple.svg)](https://github.com/florisboard/florisboard)

  [How It Works](#-how-it-works) • [Compatibility](#-app-compatibility) • [Features](#-features) • [Install](#-installation) • [Security](#-security)

</div>

---

## 🎯 What Is This?

An Android keyboard that hides **AES-256-GCM encrypted messages** inside innocent-looking text, using invisible Unicode characters.

To everyone else your message is:
```
"ok 👍"
```
Your recipient's keyboard reveals the hidden message automatically:
```
"Meeting moved to the safehouse at midnight. Bring the documents."
```

---

## 🔄 How It Works

**Sending:** you type the secret message → keyboard encrypts it → embeds it as invisible characters → wraps it in a visible cover message → sends.

**Receiving — two paths, depending on the app:**

| Path | How |
|------|-----|
| **Auto (Exchange Mode)** | Accessibility service reads on-screen text, detects hidden data, decrypts it, and shows an overlay above the message |
| **Manual (clipboard)** | Copy the message → tap **📋🔓** in the keyboard → decrypted text appears |

---

## 📱 App Compatibility — read this

The **automatic overlay only works in apps that let accessibility services read their text** (e.g. Telegram). Some apps don't expose message text — for those, use the **clipboard path**: copy the message, tap **📋🔓**, done. If you can copy it, you can decrypt it.

| App | Overlay (Exchange Mode) | Clipboard path |
|-----|------------------------|----------------|
| Telegram | ✅ Works | ✅ |
| Arattai | ⚠️ Works, some UI quirks with overlays | ✅ |
| WhatsApp | ❌ Doesn't expose text | ✅ Use this |
| Signal | ❌ | ✅ |
| Discord | ❌ | ✅ |
| Messenger | ❌ | ✅ |
| SMS | ❌ | ✅ |

Some platforms strip invisible characters entirely — test with a quick message to yourself first.

---

## ✨ Features

**Messaging**
- AES-256-GCM, random IV per message, header authenticated via AAD (tamper-evident)
- No key identifiers on the wire (v2) — receiver trial-decrypts against stored contacts
- Legacy v1 messages still accepted (receive-only)
- Best-effort replay deduplication (in-memory; window follows the cache-duration setting)
- Cover messages: auto-generated (casual/emoji), custom, or none

**Keys**
- QR code key exchange with fingerprint verification
- 33-word recovery phrase (last word is a checksum)
- Multiple keys for different contacts; tap the key pill to switch
- Keys encrypted at rest via Android Keystore
- Password-protected backup/restore (JSON export)
- **In-band key invitations** — share your key inside an ordinary-looking cover message; the recipient approves it with one tap

**Exchange Mode (auto-decrypt)**
- Scans on-screen text, shows decrypted overlay with the sender's key label
- Tracks message position while scrolling
- Overlays are removed on app switch; scanning stops after inactivity
- Device-tier aware scan limits to keep low-RAM phones fast

**Privacy extras**
- App lock (PIN) gating the keyboard UI
- Panic mode — triggers: shake, face-down tilt, spacebar gesture; actions: switch keyboard, wipe caches, remove overlays, optional key wipe
- Disguise the launcher icon (calculator, notes, file manager)
- Keyboard layout selection: QWERTY (default) or QWERTZ

---

## 📥 Installation

**Requirements:** Android 8.0+, ~35 MB storage. The Accessibility permission is optional — needed only for Exchange Mode.

```bash
git clone https://github.com/martinknox-dev/crypto-keyboard.git
cd crypto-keyboard
./gradlew assembleCryptoDebug
adb install app/build/outputs/apk/crypto/debug/app-crypto-debug.apk
```

**Setup:**
1. Enable the keyboard: Settings → System → On-screen keyboard → enable **Crypto Keyboard**
2. Open the app → Key Management → **Create New Key** → back up the 33-word recovery phrase
3. Exchange keys: show your QR code, scan theirs, compare the first 8 characters of the fingerprint in person
4. Optional: Settings → Accessibility → enable **Crypto Keyboard** for auto-decrypt

---

## 🎮 How to Use

**Send:** open any messaging app → tap the mode toggle (turns green = CRYPTO) → tap **Secret message…**, type your message → optionally set a cover message (**Auto Cover** generates one) → tap **🔒 Send**.

**Receive (auto):** Exchange Mode on + a supported app → decrypted text appears as an overlay above the message. Tap it to dismiss.

**Receive (manual, any app):** long-press the message → Copy → tap **📋🔓** in the keyboard. A copied key invitation opens the interactive Key Approval Dialog instead of a decrypt result.

**Key invitations:** in Key Management, use *Share via cover message* — your key travels inside a normal-looking message. The recipient gets an approval prompt either automatically (Exchange Mode + invite scanning enabled) or manually (copy the invite, tap **📋🔓**). Both paths open the interactive Key Approval Dialog; accepting saves the key.

---

## 🔐 Security

| Component | Implementation |
|-----------|----------------|
| Encryption | AES-256-GCM, 256-bit keys, 96-bit random IV, 128-bit tag |
| Wire format (v2) | `[1B version][12B IV][ciphertext+tag]` — header authenticated via AAD, no fingerprint on the wire |
| Wire format (v1, receive-only) | `[1B version][4B key fingerprint][12B IV][ciphertext+tag]` |
| Steganography | 16 invisible Unicode characters (U+200B–U+200D, U+2060–U+2064, U+206A–U+206F, U+FEFF, U+00AD); payload wrapped in start/end isolate markers |
| PIN protection | PBKDF2-HMAC-SHA256, 600k iterations, constant-time verify, exponential backoff after 5 failures |
| Backup export | PBKDF2-HMAC-SHA256, 100k iterations |
| Replay protection | Per-device, in-memory dedup — best-effort only; window follows the cache-duration setting (default 5 min) and is cleared on app switch, Exchange Mode toggle, key changes, and panic |
| App lock | PIN gate on keyboard UI (never engages without a configured PIN) |

**Protects against:** passive surveillance, server-side inspection, message tampering, shoulder surfing, casual forensics (keys encrypted at rest).

**Does NOT protect against:** compromised devices, screen-recording malware, metadata analysis, steganalysis by sophisticated adversaries, long-term replay (dedup is per-device and in-memory — a second device that never saw the original accepts a replay), quantum attacks.

**Limitations:** not professionally audited — don't use for life-or-death situations. Some platforms strip invisible characters. Cover text is sanitized (zero-width chars stripped) so it can't corrupt the payload. Sending replaces existing text in the input field. Verify fingerprints in person — QR codes can be intercepted. Full threat model: [SECURITY.md](SECURITY.md).

---

## 🛠️ Building from Source

**Prerequisites:** JDK 17, Android SDK 37, Git (or Android Studio Hedgehog+).

```bash
# Debug (two flavors: crypto = this app, floris = upstream UI)
./gradlew assembleCryptoDebug

# Release (signed if keystore.properties + keystore/ exist, otherwise unsigned)
./gradlew assembleCryptoRelease

# Tests
./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest
./gradlew connectedAndroidTest   # requires a device
```

Release signing is configured through a gitignored `keystore.properties` — see `app/build.gradle.kts`.

---

## 📚 Documentation

- [Security Policy](SECURITY.md) — threat model, audit status, responsible disclosure
- [Contributing](CONTRIBUTING.md) • [Code of Conduct](CODE_OF_CONDUCT.md)
- [Roadmap](ROADMAP.md) — development plans

---

## 🗺️ Roadmap

**Now (0.6.0-alpha02):** encryption pipeline, steganographic encoding, QR key exchange, key invitations, Exchange Mode overlays, clipboard decryption, app lock, panic/wipe, QWERTY/QWERTZ layouts.

**v1.0 goals:** professional security audit · compatibility verified on the top 10 messengers · pre-built signed releases · better key-verification UX.

**Future:** perfect forward secrecy, post-quantum encryption, group messaging, file encryption, iOS. See [ROADMAP.md](ROADMAP.md).

---

## 🤝 Contributing & Bugs

Contributions welcome — especially security review, messenger testing, and UI polish. See [CONTRIBUTING.md](CONTRIBUTING.md).

Found a bug? Open an issue with your device model, Android version, app version, and steps to reproduce.

**Security vulnerabilities:** email **martinknox.dev@gmail.com** instead of opening a public issue.

---

## 📜 License

```
Copyright 2025 Crypto Keyboard Contributors
Copyright 2020-2026 The FlorisBoard Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 🙏 Acknowledgments

Built on **[FlorisBoard](https://github.com/florisboard/florisboard)** by Patrick Goldinger. QR codes via **[ZXing](https://github.com/zxing/zxing)**. Inspired by **[Stegcloak](https://github.com/KuroLabs/stegcloak)**, the Signal Protocol, and PGP/GPG.

---

## 🔗 Links

**[Repository](https://github.com/martinknox-dev/crypto-keyboard)** • **[Releases](https://github.com/martinknox-dev/crypto-keyboard/releases)** • **[Issues](https://github.com/martinknox-dev/crypto-keyboard/issues)** • **[Discussions](https://github.com/martinknox-dev/crypto-keyboard/discussions)**

<div align="center">

*"The best place to hide a message is where no one thinks to look."*

</div>
