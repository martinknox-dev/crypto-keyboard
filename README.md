<div align="center">
  <img width="120" height="120" src=".github/repo_icon.png" alt="Crypto Keyboard icon">

  # Crypto Keyboard

  ### Invisible Encrypted Messaging for Android

  **Send encrypted messages that look completely normal**

  [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
  [![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://www.android.com/)
  [![Version](https://img.shields.io/badge/Version-0.6.0--alpha03-orange.svg)](https://github.com/martinknox-dev/crypto-keyboard/releases)
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

> *"Privacy won't come easily these days."*  
> Because modern operating systems are designed to discourage sideloading advanced privacy and accessibility tools, installing Crypto Keyboard requires a few manual authorization steps. Follow the guide below carefully to get set up.

**System Requirements:** Android 8.0 or higher (~35 MB storage). Internet connection is NEVER required (the app is 100% offline).

---

### Step 1: Download & Install the APK
1. Download the latest `app-crypto-release.apk` from the [Releases Page](https://github.com/martinknox-dev/crypto-keyboard/releases/latest).
2. Open the downloaded file to install.
3. **If Google Play Protect blocks the install:**
   - Tap **"More details"** → tap **"Install anyway"**.
   - *(Why? Google Play Protect flags sideloaded APKs with Accessibility permissions that aren't downloaded directly from the Play Store.)*

*(Recommended: Turn off Play Protect from the Play Store)*
  1. Open the Play Store
  2. Click on your profile button at the top right corner
  3. Check for Play Protect and click it.
  4. Toggle both the options off.
  5. And then install.

---

### Step 2: Activate the Keyboard
1. Open the **CryptoBoard** app from your home screen / app drawer.
2. The setup wizard opens automatically on first launch. Tap **"Open system settings"** (or go to phone **Settings → System / Additional Settings → Languages & Input → On-screen keyboard**).
3. Toggle on **CryptoBoard**.
4. Select **CryptoBoard** as your default input method.
5. In the app, go to **Key Management → Generate**, name your key, and write down the **33-word recovery phrase** shown afterwards.

---

### Step 3: Enable Auto-Decrypt (Accessibility Service)
*(Optional, but required if you want automatic on-screen message decryption via Exchange Mode).*

1. Go to your phone's **Settings → Accessibility**.
2. Locate **Crypto Keyboard Decoder** (often found under **"Downloaded Apps"**, **"Installed Services"**, or **"More"**).
3. Turn the switch **ON**.

---

### ⚠️ Android 13+ "Restricted Setting" Workaround (By Brand)
If your phone runs Android 13 or newer, Android automatically locks accessibility access for sideloaded APKs and displays:  
*"Restricted setting: For your security, this setting is currently unavailable."*

Follow the instructions for your specific phone brand to unlock it:

* **Google Pixel / Motorola / Stock Android / Sony:**
  1. Go to **Settings → Apps → All Apps → CryptoBoard**.
  2. Tap the **3 vertical dots (⋮)** in the top-right corner.
  3. Tap **"Allow restricted settings"**.
  4. Authenticate using your fingerprint, face, or PIN.
  5. Return to **Settings → Accessibility → Crypto Keyboard Decoder** and toggle it ON.

* **Samsung Galaxy (One UI):**
  1. Go to **Settings → Apps → CryptoBoard**.
  2. Tap the **3 dots (⋮)** in the top-right corner → tap **"Allow restricted settings"**.
  3. Enter your phone lock code/fingerprint.
  4. Return to **Settings → Accessibility → Installed apps → Crypto Keyboard Decoder** and enable it.

* **Xiaomi / Redmi / POCO (MIUI & HyperOS):**
  1. Long-press the **CryptoBoard** app icon → tap **App info (ⓘ)**.
  2. Scroll down and ensure **"Autostart"** and **"Display pop-up windows while running in the background"** are enabled.
  3. If you see the 3 dots in the top-right corner of App Info, tap **"Allow restricted settings"**.
  4. Under **Settings → Additional Settings → Accessibility → Downloaded apps**, toggle the decoder ON and accept the 10-second security countdown.

* **OnePlus / OPPO / Realme (OxygenOS / ColorOS):**
  1. Go to **Settings → Apps → App management → CryptoBoard**.
  2. Tap the **3 dots (⋮)** in the top-right corner → select **"Allow restricted settings"**.
  3. Authenticate with your fingerprint/password.
  4. Return to **Settings → Additional Settings / System Settings → Accessibility → Crypto Keyboard Decoder** and turn it ON.

* **Vivo / iQOO (Funtouch OS / OriginOS):**
  1. Long-press the **CryptoBoard** app icon → tap **App info**.
  2. Tap the **3 dots (⋮)** in the top right → select **"Allow restricted settings"**.
  3. Verify with your fingerprint/PIN.
  4. Head back to **Settings → Shortcuts & accessibility → Accessibility → Crypto Keyboard Decoder** and toggle it ON.

---

### 🛠️ Developer: Building from Source
If you prefer building the APK yourself using Gradle:
```bash
git clone https://github.com/martinknox-dev/crypto-keyboard.git
cd crypto-keyboard
./gradlew assembleCryptoRelease
```
The APK lands in `app/build/outputs/apk/crypto/release/` (unsigned unless you provide `keystore.properties`). See **Building from Source** below for flavors and the test suite.

---

## 🎮 How to Use

**Send:** open any messaging app → tap the mode toggle (turns green = CRYPTO) → tap **Secret message…**, type your message → optionally set a cover message (**Auto Cover** generates one) → tap **🔒 Send**.

**Receive (auto):** Exchange Mode on + a supported app → decrypted text appears as an overlay above the message. Tap it to dismiss.

**Receive (manual, any app):** long-press the message → Copy → tap **📋🔓** in the keyboard. A copied key invitation opens the interactive Key Approval Dialog instead of a decrypt result.

**Key invitations:** in Key Management, use *Share via cover message* — your key travels inside a normal-looking message. The recipient gets an approval prompt either automatically (Exchange Mode + invite scanning enabled) or manually (copy the invite, tap **📋🔓**). Both paths open the interactive Key Approval Dialog; accepting saves the key.

---

## 🧪 Step-by-Step Demo Guide & Reproduction

This guide allows evaluators and judges to reproduce the entire end-to-end functionality demonstrated in the pitch video within 3 minutes.

### 📋 Prerequisites & Dependencies

* **For direct testing (Recommended):**
  * Android Device or Emulator running **Android 8.0 (API 26) or higher**.
  * Downloaded `app-crypto-release.apk` from [GitHub Releases](https://github.com/martinknox-dev/crypto-keyboard/releases/latest).
* **For building from source:**
  * **Java Development Kit:** JDK 17.
  * **Android SDK:** compileSdk 37 (Android SDK Platform 37).
  * **Build System:** Gradle 9.7.1 (bundled wrapper, downloads automatically) with Kotlin 2.4.20 and Android Gradle Plugin 9.2.1.

---

### ⚙️ Quick Environment Setup (1 Minute)

1. **Install the APK:**
   * Run `adb install -r app-crypto-release.apk` (or open the downloaded file on your phone).
2. **Enable Keyboard:**
   * Go to **Settings → System → Languages & Input → On-Screen Keyboard** → toggle **CryptoBoard** ON.
   * Set **CryptoBoard** as the active/default input method.
3. **Enable Auto-Decrypt (Accessibility Service):**
   * Go to **Settings → Accessibility → Downloaded Apps / Installed Services**.
   * Toggle **Crypto Keyboard Decoder** ON.  
     *(Note: If Android 13+ displays "Restricted Setting", go to App Info → tap the top-right 3 dots → tap "Allow restricted settings", then enable it in Accessibility).*
4. **Generate Your Identity Key:**
   * Open the **CryptoBoard** app → **Key Management** → tap **Generate**.
   * Enter a key name (e.g., "Alice") and save — the 33-word recovery phrase is shown afterwards.

---

### 🚀 Reproducing the Core Demo Workflows

#### Test 1: Sending an Invisible Encrypted Message
1. Open any messaging app (Telegram, Notes, WhatsApp, or SMS).
2. Tap the input field to open the keyboard.
3. Tap the lock icon toggle on the keyboard toolbar (turns **Green = CRYPTO MODE**).
4. Tap **"Secret message…"** and enter:  
   `"Meeting at midnight behind the warehouse."`
5. Tap **"Auto Cover"** (generates visible decoy text such as `"Sounds good to me 👍"`).
6. Tap **🔒 Send**.
7. **Verification:** Inspect the sent text. To any viewer, platform server, or screen inspector, only the innocent cover message appears.

---

#### Test 2: Receiving & Decryption (Dual Paths)

* **Path A — Live Screen Overlay (Exchange Mode):**
  1. In the CryptoBoard app, go to **Settings → Crypto Engine** and toggle **Exchange Mode** ON (the Accessibility service from Step 3 must be enabled).
  2. Open an app that exposes text nodes (e.g., Telegram).
  3. **Verification:** A floating decryption bubble appears directly over the message, showing the contact header **🔓 Alice** above the decrypted text:  
     `Meeting at midnight behind the warehouse.`
* **Path B — Manual Clipboard Decrypt (WhatsApp / Signal Fallback):**
  1. Long-press the received message and tap **Copy**.
  2. Open the keyboard in any text box and tap the **📋🔓 (Clipboard Decrypt)** icon on the keyboard toolbar.
  3. **Verification:** The decrypted message pops up instantly in the keyboard bar.

---

#### Test 3: In-Band Key Pairing (Zero Out-of-Band Setup)
1. In the CryptoBoard app, navigate to **Key Management** → tap the **👁 (View Details)** icon on your key card → tap **Share via Cover Message**.
2. Enter an optional **Greeting / note** (e.g., `"Alice's Secure Key"`) and a **Cover text** (e.g., `"Are you free for lunch?"`; blank = random).
3. Tap **📋 Copy** (or **Share…** to open the system share sheet).
4. Paste and send the message to the recipient.
5. On the recipient's phone (with CryptoBoard active), copy the message and tap **📋🔓** (or view it on-screen with Exchange Mode enabled).
6. **Verification:** An interactive **"Key Invitation Received"** dialog opens displaying the note. Enter a **Contact name / alias** and tap **Save Contact** — the key is saved encrypted (Android Keystore-backed) without scanning any QR codes.

---

#### Test 4: Panic Actions & Anti-Forensics Trigger
1. **Prerequisite:** In the CryptoBoard app, go to **Settings → Panic Settings** and turn the **master switch ON** — it is off by default. Enable the triggers you want to demo (**Shake** is on by default; **face-down** and **spacebar quick-swipe** are off by default).
2. With decrypted messages visible on screen, simulate an emergency inspection by performing any of the following:
   * **Shake the phone** vigorously, OR
   * **Flip the phone face-down** on a table, OR
   * **Swipe quickly across the spacebar**.
3. **Verification:**
   * All floating decryption bubbles vanish instantly.
   * The in-memory decryption cache and deduplication tables are completely purged.
   * The active input method switches back to your previous keyboard (e.g., Gboard / Samsung Keyboard).

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

**Now (0.6.0-alpha03):** encryption pipeline, steganographic encoding, QR key exchange, key invitations, Exchange Mode overlays, clipboard decryption, app lock, panic/wipe, QWERTY/QWERTZ layouts.

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
