# Contributing to Crypto Keyboard

Thanks for your interest in contributing! This project needs help to become production-ready.

---

## 🎯 What We Need

### High Priority

1. **Security review** — Audit cryptographic implementation
2. **Platform testing** — Verify compatibility across messengers
3. **Battery optimization** — Reduce Accessibility Service impact
4. **UI/UX improvements** — Better onboarding and error handling
   **NOTE:** Edit Core (FlorisBoard) only when needed, keep expanding, not replacing

### Also Welcome

- Bug fixes
- Documentation improvements
- Feature suggestions
- Code optimizations
- Accessibility enhancements

---

## 🐛 Reporting Bugs

### Security Issues

**DO NOT open public issues for security vulnerabilities.**

Email: [mkprogrammer01@gmail.com]
Email: [letterbox32@proton.me]
Email: [postbox32@zohomail.in]

Include:
- Description
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

### Regular Bugs

Open an issue with:

- **Device:** Model and Android version
- **App version:** Found in Settings → About
- **Steps to reproduce**
- **Expected behavior**
- **Actual behavior**
- **Logs** (if available via `adb logcat`)

---

## 💡 Suggesting Features

Open an issue with:

1. **Problem description** — What need does this address?
2. **Proposed solution** — How would it work?
3. **Privacy implications** — Does it require new permissions?
4. **Use case** — When would you use this?

---

## 💻 Contributing Code

### Setup

```bash
# Fork and clone
git clone https://github.com/martinknox-dev/crypto-keyboard.git
cd crypto-keyboard

# Build
./gradlew assembleDebug

# Run tests
./gradlew test
```

### Code Guidelines

**General:**
- All code in English
- Follow [Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html)
- No network calls (we're completely offline)
- Add comments for complex logic

**Crypto code:**
- Discuss changes before implementing
- Never hardcode keys/IVs
- Use `SecureRandom` for all random values
- Fail silently (don't leak error details)
- Add unit tests

**UI code:**
- Use Jetpack Compose
- Follow Material Design principles
- Test on multiple screen sizes
- Ensure accessibility (TalkBack support)

### Commit Messages

```
feat: add Argon2 key derivation option
fix: prevent crash when scanning long messages
security: patch timing attack in PIN verification
docs: update installation instructions
test: add unit tests for InvisibleEncoder
```

### Pull Request Process

1. Fork the repo
2. Create feature branch (`git checkout -b feature/your-feature`)
3. Make changes
4. Test thoroughly
5. Commit with clear messages
6. Push to your fork
7. Open Pull Request

**PR Requirements:**
- Clear description
- References related issue
- Tests pass
- No security regressions
- Code follows style guide

---

## 🧪 Testing

### Manual Testing

Test these scenarios:

1. **Encryption/decryption**
   - Various message lengths
   - Special characters
   - Emoji in messages
   - Round-trip (encrypt → send → receive → decrypt)

2. **Platform compatibility**
   - WhatsApp, Telegram, Signal, SMS
   - Use CompatibilityTester
   - Document results

3. **Key management**
   - Create, import, export keys
   - QR code scanning
   - PIN protection

4. **Edge cases**
   - Empty messages
   - Very long messages (>1000 chars)
   - Wrong key decryption
   - Corrupted data

### Automated Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

---

## 🔒 Security Considerations

### When Modifying Crypto Code

⚠️ **Discuss first** — Open an issue before changing encryption  
⚠️ **Provide rationale** — Explain why current approach is wrong  
⚠️ **Reference standards** — Link to RFCs, papers, or CVEs  
⚠️ **Add tests** — Crypto changes need extensive testing

### Security Checklist

Before submitting crypto-related PRs:

- [ ] No hardcoded secrets
- [ ] Random values from `SecureRandom`
- [ ] No timing attacks (use constant-time comparison)
- [ ] Errors don't leak information
- [ ] Memory cleared after use
- [ ] No logging of sensitive data
- [ ] Follows cryptographic best practices

### Do NOT Change (Without Discussion)

- Encryption algorithm (AES-256-GCM)
- Key size (256 bits)
- Message format (breaks compatibility)
- Steganographic markers

---

## 📝 Code Review Process

1. **Automated checks** run (build, tests)
2. **Maintainer review** (1-2 weeks)
3. **Security review** if crypto-related
4. **Merge** when approved

---

## 🌍 Localization

Currently English only. If you want to contribute translations:

1. Open an issue to discuss
2. We'll set up translation infrastructure
3. Contribute via Crowdin (TBD)

---

## 📚 Documentation

Improvements welcome for:

- User guides
- Code comments
- Architecture docs
- API documentation
- Example use cases

---

## 💬 Questions?

- **General:** Open a [Discussion](https://github.com/martinknox-dev/crypto-keyboard/discussions)
- **Bugs:** Open an [Issue](https://github.com/martinknox-dev/crypto-keyboard/issues)
- **Security:** Email [YOUR_EMAIL]

---

## 🙏 Recognition

All contributors will be:
- Listed in [CONTRIBUTORS.md](CONTRIBUTORS.md)
- Credited in release notes
- Thanked in the README

Security researchers who find vulnerabilities will be credited (with permission) in [SECURITY.md](SECURITY.md).

---

Thank you for helping make Crypto Keyboard better! 🔐