# Permanent Invite Scan + Settings Toggles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the standalone 5-minute invite-scan window entirely, make invite scanning a permanent Exchange-Mode-piggybacked feature, and rework the Crypto Engine settings rows (Exchange Mode, Scan for Active Invites) into Switch toggles with state-aware descriptions that also mention the clipboard-paste fallback path.

**Architecture:** The standalone window is deleted from the pure `InviteScanController` gate (and its test rewritten TDD-first), from the accessibility service's event/refresh gates, and from the Import dropdown UI. Because the old `accepted` flag was reset only by the standalone window, it is replaced by per-message acceptance tracking so one accepted invite no longer kills the permanent scanner. The settings screen rows gain `trailingContent = { Switch(...) }` (the jetpref `Preference` already supports it — see `ExtensionEditFilesScreen.kt:159`).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), jetpref `Preference`, kotest (plain-JVM tests in `app/src/testCrypto/`), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-11-in-band-key-invitation.md` — this plan **deviates** from two spec behaviors deliberately (documented below), at the user's request:
- *Removed:* the standalone 5-minute window (spec §"standalone window") — user wants only the permanent Exchange-Mode scanner.
- *Changed:* "single accepted invite ends the session" — replaced with per-message suppression, because without the standalone window's reset the flag would permanently disable a scanner the user explicitly wants permanent.

**Why (root cause being fixed):** standalone mode scans with Exchange Mode off, and every decrypt path in `processVisibleContent` gates on `exchangeOn` (`CryptoAccessibilityService.kt:474,487,510,523`). Result: **only invite bubbles showed, never decrypted messages**. Tying scanning to Exchange Mode makes invite-only scans impossible.

## Global Constraints

- **NO COMMITS until the user explicitly says so** (standing rule — all commit steps are deliberately omitted; work stays uncommitted for on-device verification).
- All code, comments, and UI strings in **English**.
- TDD: write the failing test first, verify it fails for the **right reason** (RED), then implement (GREEN).
- Plain-JVM tests live in `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/core/`.
- Full verify command: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug :app:assembleFlorisDebug`
- Baseline before this plan: **crypto 217 / floris 82, 0 failures**. `InviteScanControllerTest` currently has 10 tests; the rewritten file has 7 → expected final crypto count **214**.
- `CryptoBarHook` lives in `app/src/main` and may reach crypto-flavor classes only via reflection (not touched by this plan, but no new direct cross-set references).
- Nothing pushed; branch `crypto-board`.

## Review Focus

1. **Leftover standalone references** — a missed `startInviteScanWindow`/`isStandaloneWindowActive` call compiles nowhere (service + UI are crypto-flavor), so the compile step catches it; additionally grep for `standalone|Standalone|windowMs` in Task 2 as an explicit step.
2. **Invite nag after acceptance** — accepting one on-screen invite must suppress that bubble without disabling scanning for other invites; pinned by tests "accepted invite message no longer shows a bubble", "other invite messages still show after one accept", "accepting an invite does not stop the permanent scan" (Task 1).
3. **Scanner dead after settings change** — toggling the invite switch must refresh the service's settings snapshot; the existing `refreshPanicTriggers()` call does this (`settingsSnapshot = settings`, `CryptoAccessibilityService.kt:230`); kept in Task 4 and verified on-device (no unit test possible — Compose + Android service).
4. **Exchange toggle without accessibility service** — must still show the "Enable Accessibility Service first" toast and not flip state; guarded in Task 4's `toggleExchangeMode`, device-verified.
5. **Persisted toggle survives restart** — `scanInvitesEnabled` is persisted by `SettingsStore` (`SettingsStore.kt:55,91`), untouched by this plan; device-verify after reboot/re-open in Task 6.

---

### Task 1: InviteScanController — piggyback-only gate + per-message acceptance (TDD)

**Files:**
- Modify: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/core/InviteScanControllerTest.kt` (full rewrite)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/InviteScanController.kt` (full rewrite)

**Interfaces:**
- Consumes: nothing (pure class).
- Produces (replacing the old API — Task 2 depends on these exact names):
  - `class InviteScanController` — no constructor parameters (old `windowMs`/`nowMs` removed).
  - `fun shouldScan(exchangeModeActive: Boolean, scanInvitesEnabled: Boolean): Boolean`
  - `fun markInviteAccepted(rawMessage: String): Unit`
  - `fun shouldShowInvite(rawMessage: String): Boolean`
- **Removed** (Task 2 must delete all call sites): `startStandaloneWindow()`, `isStandaloneWindowActive()`, `onInviteAccepted()`, constructor params `windowMs`, `nowMs`.

- [ ] **Step 1: Rewrite the test file with the new behavior**

Overwrite `InviteScanControllerTest.kt` with:

```kotlin
package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InviteScanControllerTest : FunSpec({

    // --- permanent scan gate: Exchange Mode + user's invite toggle ---

    test("scans when exchange mode on and invite toggle on") {
        val c = InviteScanController()
        c.shouldScan(exchangeModeActive = true, scanInvitesEnabled = true) shouldBe true
    }

    test("does not scan when exchange mode off") {
        val c = InviteScanController()
        c.shouldScan(exchangeModeActive = false, scanInvitesEnabled = true) shouldBe false
    }

    test("does not scan when invite toggle off") {
        val c = InviteScanController()
        c.shouldScan(exchangeModeActive = true, scanInvitesEnabled = false) shouldBe false
    }

    // --- per-message acceptance: never re-nag, never kill the scanner ---

    test("invite bubble shown before any invite is accepted") {
        val c = InviteScanController()
        c.shouldShowInvite("cover text with invite payload") shouldBe true
    }

    test("accepted invite message no longer shows a bubble") {
        val c = InviteScanController()
        c.markInviteAccepted("cover text with invite payload")
        c.shouldShowInvite("cover text with invite payload") shouldBe false
    }

    test("other invite messages still show after one accept") {
        val c = InviteScanController()
        c.markInviteAccepted("invite A cover")
        c.shouldShowInvite("different cover with invite B") shouldBe true
    }

    test("accepting an invite does not stop the permanent scan") {
        val c = InviteScanController()
        c.markInviteAccepted("invite A cover")
        c.shouldScan(exchangeModeActive = true, scanInvitesEnabled = true) shouldBe true
    }
})
```

- [ ] **Step 2: Run the tests to verify RED for the right reason**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.core.InviteScanControllerTest"`
Expected: COMPILATION FAIL — unresolved references to `markInviteAccepted`/`shouldShowInvite` (the old class has `onInviteAccepted`/`startStandaloneWindow`). This is the correct RED: the new API doesn't exist yet.

- [ ] **Step 3: Rewrite `InviteScanController.kt`**

Overwrite with:

```kotlin
package com.mkcreations.cryptokeyboard.core

/**
 * Pure session gate for invite scanning.
 *
 * Scanning is permanent while Exchange Mode is on and the user's invite
 * toggle is enabled (piggybacks on the screen scan the accessibility service
 * already performs for decryption — near-zero extra cost). There is no
 * time-bounded standalone window: scanning only ever runs alongside
 * Exchange Mode, so decrypted messages and invite bubbles are always
 * detected together.
 *
 * Accepted invites are suppressed per message: once the user accepts an
 * invite carried by a given raw message, that exact message never shows a
 * bubble again, while scanning continues for new invites.
 */
class InviteScanController {
    private val acceptedInvites = mutableSetOf<String>()

    /** Scan when Exchange Mode is on and the user's invite toggle is enabled. */
    fun shouldScan(exchangeModeActive: Boolean, scanInvitesEnabled: Boolean): Boolean =
        exchangeModeActive && scanInvitesEnabled

    /** Records that the user accepted the invite carried by [rawMessage]. */
    fun markInviteAccepted(rawMessage: String) {
        acceptedInvites.add(rawMessage)
    }

    /** False once the user has accepted the invite carried by [rawMessage]. */
    fun shouldShowInvite(rawMessage: String): Boolean =
        rawMessage !in acceptedInvites
}
```

- [ ] **Step 4: Run the tests to verify GREEN**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.core.InviteScanControllerTest"`
Expected: PASS — 7 tests, 0 failures.

---

### Task 2: Service + approval activity wiring (remove standalone gate)

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/CryptoAccessibilityService.kt` (lines ~58-63, ~206-222, ~249, ~256-257, ~343, ~483-484, ~544-557)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyInviteApprovalActivity.kt:72`

**Interfaces:**
- Consumes: Task 1's `InviteScanController()` no-arg constructor, `markInviteAccepted(rawMessage)`, `shouldShowInvite(rawMessage)`, `shouldScan(...)`.
- Produces: `fun onInviteAccepted(rawMessage: String)` on the service (called by `KeyInviteApprovalActivity`; changes from no-arg to one-arg).

- [ ] **Step 1: Rewrite `inviteScanActive()` doc and the service's public API**

In `CryptoAccessibilityService.kt`:

1. Replace the `inviteScanActive()` KDoc (lines 58-59) with:

```kotlin
    /** True when invite scanning is active: Exchange Mode on + the user's invite toggle. */
```

(the body `inviteScan.shouldScan(...)` stays unchanged).

2. Replace `onInviteAccepted()` (lines 206-212) with:

```kotlin
    /**
     * Called by the invite approval flow once a contact is saved — suppresses
     * bubbles for that exact raw message so the accepted invite never re-nags,
     * while scanning continues for new invites.
     */
    fun onInviteAccepted(rawMessage: String) {
        inviteScan.markInviteAccepted(rawMessage)
    }
```

3. **Delete** the whole `startInviteScanWindow()` function and its KDoc (lines 214-222).

- [ ] **Step 2: Remove the standalone gate from the event + refresh paths**

1. Line 249 — replace:

```kotlin
        val scanActive = SharedState.isExchangeModeActive || inviteScan.isStandaloneWindowActive()
```

with:

```kotlin
        val scanActive = SharedState.isExchangeModeActive
```

2. Lines 256-257 — replace the comment:

```kotlin
        // Idle auto-off applies to Exchange Mode only — it must never kill an
        // in-progress standalone invite window (that window is time-bounded itself).
```

with:

```kotlin
        // Idle auto-off applies to Exchange Mode only.
```

3. Line 343 in `requestOverlayRefresh()` — replace:

```kotlin
        if (!SharedState.isExchangeModeActive && !inviteScan.isStandaloneWindowActive()) return
```

with:

```kotlin
        if (!SharedState.isExchangeModeActive) return
```

- [ ] **Step 3: Per-message suppression in the invite-bubble branch**

1. Lines 483-484 — replace the comment:

```kotlin
        // Decrypt cache: only surface a cached plaintext when Exchange Mode is on.
        // Invite-only (standalone) mode must never expose decrypted content.
```

with:

```kotlin
        // Decrypt cache: only surface a cached plaintext when Exchange Mode is on.
```

2. Lines 544-546 — replace the comment and the branch condition:

```kotlin
            // In-band key invite: surface a tappable bubble whenever invite
            // scanning is active (Exchange Mode piggyback OR standalone window).
            is MessageProcessor.DecryptResult.KeyInvite -> {
                if (inviteScanActive()) {
```

with:

```kotlin
            // In-band key invite: surface a tappable bubble while invite
            // scanning is active, unless the user already accepted the invite
            // carried by this exact message.
            is MessageProcessor.DecryptResult.KeyInvite -> {
                if (inviteScanActive() && inviteScan.shouldShowInvite(node.fullText)) {
```

- [ ] **Step 4: Update `KeyInviteApprovalActivity`**

Line 72 — replace:

```kotlin
                    CryptoAccessibilityService.instance?.onInviteAccepted()
```

with:

```kotlin
                    raw?.let { CryptoAccessibilityService.instance?.onInviteAccepted(it) }
```

(`raw` is the same `node.fullText` string passed into the activity by the bubble tap, so the suppression key matches exactly.)

- [ ] **Step 5: Grep for leftovers**

Run: `grep -rn "Standalone\|standalone\|startInviteScanWindow\|windowMs\|nowMs" app/src/crypto/`
Expected: no matches.

- [ ] **Step 6: Compile both flavors**

Run: `./gradlew :app:compileCryptoDebugKotlin :app:compileFlorisDebugKotlin`
Expected: BUILD SUCCESSFUL, no unresolved references.

---

### Task 3: Remove the "Scan for Invites (5 min)" Import-menu item

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyManagementScreen.kt` (dropdown item at lines 257-271; unused import at line 27)

**Interfaces:**
- Consumes: nothing (pure UI deletion).
- Produces: nothing.

- [ ] **Step 1: Delete the dropdown item**

Remove this entire block from the `DropdownMenu` (lines 257-271):

```kotlin
                    DropdownMenuItem(
                        text = { Text("Scan for Invites (5 min)") },
                        onClick = {
                            showImportDropdown = false
                            CryptoAccessibilityService.instance?.startInviteScanWindow()
                            Toast.makeText(
                                context,
                                "Listening for key invites for 5 minutes\u2026",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Share, contentDescription = null)
                        }
                    )
```

The `DropdownMenu` then ends after the "From File" item followed by the closing `}`.

- [ ] **Step 2: Remove the now-unused import**

Delete line 27:

```kotlin
import androidx.compose.material.icons.filled.Share
```

Verify it is unused first: `grep -n "Icons.Default.Share" app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyManagementScreen.kt` → expected: no matches after the deletion.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileCryptoDebugKotlin`
Expected: BUILD SUCCESSFUL (an unused import is only a warning, so the grep in Step 2 is the real gate).

---

### Task 4: Crypto Engine settings — Switch toggles + state-aware descriptions

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/CryptoSettingsScreen.kt` (lines 42, 72-152)

**Interfaces:**
- Consumes: `SharedState.isExchangeModeActive`, `CryptoAccessibilityService.instance?.onExchangeModeChanged(Boolean)`, `SettingsStore.saveSettings`, `refreshPanicTriggers()`, jetpref `Preference(icon, title, summary, onClick, trailingContent)` (already imported at line 30; `trailingContent` pattern proven at `ExtensionEditFilesScreen.kt:159`).
- Produces: nothing (leaf UI).

- [ ] **Step 1: Add the two toggle handlers**

Insert after `val inviteSettings by remember { ... }` (after line 57), before `val coroutineScope`:

```kotlin
    val toggleExchangeMode: () -> Unit = {
        if (!isAccessibilityEnabled) {
            Toast.makeText(
                context,
                "Enable Accessibility Service first",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val newState = !isExchangeMode
            SharedState.isExchangeModeActive = newState
            isExchangeMode = newState
            CryptoAccessibilityService.instance?.onExchangeModeChanged(newState)
            Toast.makeText(
                context,
                if (newState) "Exchange Mode enabled" else "Exchange Mode disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val toggleInviteScan: () -> Unit = {
        if (!isAccessibilityEnabled) {
            Toast.makeText(
                context,
                "Enable Accessibility Service first",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val next = !inviteSettings.scanInvitesEnabled
            val updated = inviteSettings.copy(scanInvitesEnabled = next)
            inviteSettings = updated
            SettingsStore.getInstance(context).saveSettings(updated)
            // Refreshes the service's settings snapshot so the new gate value applies.
            CryptoAccessibilityService.instance?.refreshPanicTriggers()
            Toast.makeText(
                context,
                if (next) "Invite scanning on" else "Invite scanning off",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
```

- [ ] **Step 2: Replace the Exchange Mode row**

Replace the `Preference` at lines 73-102 (inside `PreferenceGroup(title = "Exchange Mode")`) with:

```kotlin
            Preference(
                icon = Icons.Default.SwapHoriz,
                title = "Exchange Mode",
                summary = if (isExchangeMode)
                    "On — screen scanning active, encrypted messages decrypt automatically"
                else
                    "Off — turn on to decrypt on-screen messages automatically",
                onClick = { toggleExchangeMode() },
                trailingContent = {
                    Switch(
                        checked = isExchangeMode,
                        onCheckedChange = { toggleExchangeMode() },
                    )
                }
            )
```

(`Switch` comes from the existing wildcard import `androidx.compose.material3.*` at line 15.)

- [ ] **Step 3: Replace the invite scan row with new title + descriptions**

Replace the `Preference` at lines 124-151 (inside `PreferenceGroup(title = "Key Invitations")`) with:

```kotlin
            Preference(
                icon = Icons.Default.Share,
                title = "Scan for Active Invites",
                summary = if (inviteSettings.scanInvitesEnabled)
                    "On — while Exchange Mode is on, key invitations in on-screen " +
                        "messages are detected and shown as a tappable bubble"
                else
                    "Off — invites won't be detected on screen. Copy the invite " +
                        "message, then tap the clipboard paste button on the keyboard " +
                        "bar to open it instead",
                onClick = { toggleInviteScan() },
                trailingContent = {
                    Switch(
                        checked = inviteSettings.scanInvitesEnabled,
                        onCheckedChange = { toggleInviteScan() },
                    )
                }
            )
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileCryptoDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

### Task 5: Full verification + ledger

**Files:**
- Modify: `.superpowers/sdd/2026-10-12-permanent-invite-scan-toggles/progress.md` (create)

- [ ] **Step 1: Run the full verify command**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug :app:assembleFlorisDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm counts**

Run:

```bash
grep -rho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testCryptoDebugUnitTest/*.xml | awk -F'"' '{t+=$2;f+=$6;e+=$8} END {print "crypto tests=" t " failures=" f " errors=" e}'
grep -rho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testFlorisDebugUnitTest/*.xml | awk -F'"' '{t+=$2;f+=$6;e+=$8} END {print "floris tests=" t " failures=" f " errors=" e}'
ls -l --time-style=+%H:%M:%S app/build/outputs/apk/crypto/debug/app-crypto-debug.apk
```

Expected: `crypto tests=214 failures=0 errors=0` (217 − 10 old + 7 new), `floris tests=82 failures=0 errors=0`, APK timestamp fresh. If crypto is not 214, account for the difference before proceeding (a missing test is a bug).

- [ ] **Step 3: Write the ledger entry (NO commit)**

Create `.superpowers/sdd/2026-10-12-permanent-invite-scan-toggles/progress.md` with a short entry recording: tasks completed, test counts, root-cause note (standalone mode suppressed decryption → invite-only overlays), and the line "No commits — standing rule: commit only after on-device verification."

---

### Task 6: On-device verification checklist (NO commit until user approves)

- [ ] **Step 1: Install the APK**

Run: `adb install -r app/build/outputs/apk/crypto/debug/app-crypto-debug.apk` (or hand the APK path to the user if no device is connected).

- [ ] **Step 2: Walk the checklist**

1. Import menu shows only: Scan QR Code, From Phrase, From File — **no** "Scan for Invites (5 min)".
2. Crypto Engine page: both rows show a **Switch**; title no longer says "ON/OFF"; flipping either switch works from both the row and the switch.
3. Invite toggle OFF → summary mentions the clipboard paste button on the keyboard bar; ON → summary mentions the tappable bubble behavior.
4. With Exchange Mode ON + invite toggle ON: an on-screen invite shows its bubble; accepting it hides that bubble and it **does not come back** on rescan; other invites still show.
5. The original bug: with Exchange Mode ON, decrypted messages **and** invite bubbles both display (never invites alone).
6. Toggling invite scanning off/on takes effect immediately without restarting the service (bubble appears/disappears).
7. Reopen the app → toggles retained (persistence).
8. Re-run the standing overlay checklist (page switch clears overlays, shade pull no flicker, tap bubble → instant removal).

- [ ] **Step 3: Await explicit user approval before any commit**

No `git add`/`git commit` until the user says so. Nothing pushed.
