## Description

<!-- 
Describe your changes clearly:
- What does this PR do?
- Why is this change needed?
- Related issue: Fixes #XXX
-->

## Type of Change

<!-- Check all that apply -->

- [ ] 🐛 Bug fix (non-breaking change that fixes an issue)
- [ ] ✨ New feature (non-breaking change that adds functionality)
- [ ] 🔒 Security improvement
- [ ] 💥 Breaking change (fix or feature that would cause existing functionality to change)
- [ ] 📝 Documentation update
- [ ] 🔧 Refactoring (no functional changes)
- [ ] 🧪 Tests (adding or updating tests)

## Changes Made

<!-- List the main changes in this PR -->

- 
- 
- 

## Testing

<!-- How did you test these changes? -->

- [ ] Tested on physical device (Model: _______)
- [ ] Tested on emulator (API level: _______)
- [ ] Tested encryption/decryption round-trip
- [ ] Tested Exchange Mode (if applicable)
- [ ] Tested with multiple keys (if applicable)
- [ ] Unit tests pass (`./gradlew test`)

## Screenshots / Recordings

<!-- If applicable, add screenshots or recordings of UI changes -->

## APK Testing

For each pull request, a CI workflow builds a debug APK automatically.

**To test:**
1. Go to **Checks** tab → **Crypto Keyboard CI**
2. Download `app-debug.apk` artifact
3. Install on your device (uses debug namespace, won't conflict with existing install)

## Security Considerations

<!-- For crypto/security-related changes -->

- [ ] No hardcoded secrets or keys
- [ ] Uses `SecureRandom` for random values
- [ ] Errors don't leak sensitive information
- [ ] No logging of sensitive data
- [ ] Follows existing security patterns

## Checklist

- [ ] I have read the [Contributing Guidelines](https://github.com/martinknox-dev/crypto-keyboard/blob/main/CONTRIBUTING.md)
- [ ] My code follows the project's coding style
- [ ] I have added/updated documentation (if needed)
- [ ] I have added/updated tests (if applicable)
- [ ] All existing tests pass
- [ ] I have tested on a real device
- [ ] This PR does not introduce security regressions

## Related Issues

<!-- Link related issues: Fixes #123, Relates to #456 -->