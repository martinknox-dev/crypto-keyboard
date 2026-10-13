# Overlay Cleanup + Invite Scan Decoupling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two on-device bugs — (1) `TYPE_ACCESSIBILITY_OVERLAY` decrypt/invite overlays are orphaned and never dismissed after the user closes or switches away from the encrypted-message app, and (2) the in-band key-invite bubble (Path A) never appears because the entire accessibility scan pipeline is gated behind `SharedState.isExchangeModeActive`, which the "Scan for key invitations" toggle does not enable — while the clipboard path (Path B) has no such gate and works.

**Architecture:** Decouple invite scanning from Exchange Mode via a two-path gate in a reworked `InviteScanController`: (a) *piggyback* — surface invites while Exchange Mode + the invite toggle are both on (near-zero extra cost, same node traversal, `extractInvite` already runs first inside `detectAndDecrypt`), and (b) *standalone* — a fresh 5-minute window opened on demand from a new "Scan for Invites (5 min)" item in the Import Keys menu, which surfaces invites **without** Exchange Mode and **never** decrypts. For overlay cleanup, stamp every overlay with its producing package (`sourcePackage`) and remove overlays whose package differs from the new foreground app on `TYPE_WINDOW_STATE_CHANGED` — handled **unconditionally, before the exchange-mode gate** and before the modification-grace early-returns, filtering only `systemui`/`android`/self so a notification-shade pull never flickers but a real app-switch/Home always clears. Add a `DeviceTier` (via `ActivityManager.isLowRamDevice`) that lengthens the scan debounce and lowers scan depth/nodes on low-RAM devices to prevent lag.

**Tech Stack:** Kotlin, Android AccessibilityService, WindowManager overlay windows, Jetpack Compose (import menu + settings), kotest + plain-JVM unit tests (`app/src/testCrypto/`).

**Spec:** Derived from the approved in-conversation design (user confirmed: piggyback + standalone 5-min from Import Keys; shade must NOT clear overlays but app-switch/Home/recents MUST; DeviceTier to prevent lag on low-end). Implements the invite feature added in `docs/superpowers/plans/2026-10-11-in-band-key-invitation.md` (Tasks 6-8) and the overlay lifecycle in `CryptoAccessibilityService`.

## Global Constraints

- All code and comments in **English**.
- Plain-JVM unit tests live in `app/src/testCrypto/kotlin/` (kotest `FunSpec`). Tests touching `ActivityManager`/`WindowManager`/`AccessibilityNodeInfo` are Android-only — verify those tasks by **compile + existing suite**, not plain-JVM tests.
- **Full verify command (run at Task 7 and after every compile-touching task):** `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug :app:assembleFlorisDebug`
- **Baseline test count before this plan:** crypto 188 / floris 82 = 270 tests, 0 failures. New tests only ADD to crypto; floris must stay 82.
- **NO COMMITS until on-device verification passes** (user standing rule). Per-task commit steps are **deferred** — progress is tracked in the SDD ledger; a single commit (or split) happens in Task 8 only after the user signs off on device. Nothing is pushed.
- `CryptoBarHook.kt` (main source set) reaches crypto-flavor classes **only via reflection** — any new crypto-class call from it must use `Class.forName`/`getMethod` so the floris flavor still compiles.
- Test APK path for on-device runs: `app/build/outputs/apk/crypto/debug/app-crypto-debug.apk`.

## Review Focus

Failure modes the spec implies but no single task's tests fully exercise — verify each on-device in Task 8:

1. **Invite bubble taps must still launch the approval Activity.** The bubble drops `FLAG_NOT_TOUCHABLE` but keeps `FLAG_NOT_FOCUSABLE`; adding `sourcePackage` to the entry must not break the `setOnClickListener`. *Test: task 4 compile + task 8 tap-on-device.*
2. **Standalone invite mode must NOT decrypt/expose messages.** With Exchange Mode off + standalone window open, a decryptable message must show NO decrypted overlay; only the invite bubble may appear. *Test: task 4 gate restructure + task 8.*
3. **Notification-shade pull must NOT dismiss overlays (no flicker).** A `systemui` window-state-change must be filtered out of the removal path. *Test: task 4 filter + task 8 pull-shade-on-device.*
4. **Overlays must re-appear when returning to the app.** After an app-switch clears them, coming back must re-scan and re-show. *Test: task 5 `scheduleUpdate` on same-package + task 8.*
5. **Low-RAM tier must not suppress overlays that should display.** Reduced debounce (800ms) and depth (10) must still find top-of-tree message nodes. *Test: task 3 classify + task 8 on a low-end device or emulator.*

---

### Task 1: InviteScanController rework (two-path gate + restartable window)

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/InviteScanController.kt` (entire file, 31 lines)
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/core/InviteScanControllerTest.kt` (rewrite, 43 lines)

**Interfaces:**
- Consumes: nothing (pure Kotlin, no Android).
- Produces: `class InviteScanController(windowMs: Long = 300000, nowMs: () -> Long)` with `fun startStandaloneWindow()`, `fun isStandaloneWindowActive(): Boolean`, `fun shouldScan(exchangeModeActive: Boolean, scanInvitesEnabled: Boolean): Boolean`, `fun onInviteAccepted()`. **Breaking change:** old `beginWindow()` and single-arg `shouldScan(inviteScanEnabled)` are removed — Task 5 updates the service call sites.

- [ ] **Step 1: Rewrite the test file to the new two-path interface**

Replace all of `InviteScanControllerTest.kt` with:

```kotlin
package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InviteScanControllerTest : FunSpec({

    // --- piggyback path: Exchange Mode on + invite toggle on ---

    test("piggyback scans when exchange mode on and toggle on") {
        val c = InviteScanController(nowMs = { 0L })
        c.shouldScan(exchangeModeActive = true, scanInvitesEnabled = true) shouldBe true
    }

    test("piggyback does not scan when exchange on but toggle off") {
        val c = InviteScanController(nowMs = { 0L })
        c.shouldScan(exchangeModeActive = true, scanInvitesEnabled = false) shouldBe false
    }

    test("piggyback does not scan when toggle on but exchange off") {
        val c = InviteScanController(nowMs = { 0L })
        c.shouldScan(exchangeModeActive = false, scanInvitesEnabled = true) shouldBe false
    }

    // --- standalone path: 5-minute window, independent of exchange mode ---

    test("standalone window is inactive before start") {
        val c = InviteScanController(nowMs = { 0L })
        c.isStandaloneWindowActive() shouldBe false
    }

    test("standalone window is active after start even with exchange off and toggle off") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.startStandaloneWindow()
        now = 1000L
        c.isStandaloneWindowActive() shouldBe true
        c.shouldScan(exchangeModeActive = false, scanInvitesEnabled = false) shouldBe true
    }

    test("standalone window expires after 5 minutes") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.startStandaloneWindow()
        now = 300_000L // exactly at boundary -> expired
        c.isStandaloneWindowActive() shouldBe false
        c.shouldScan(exchangeModeActive = false, scanInvitesEnabled = false) shouldBe false
    }

    test("standalone window is active just before 5 minutes") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.startStandaloneWindow()
        now = 299_999L
        c.isStandaloneWindowActive() shouldBe true
    }

    // --- one-accept auto-shutoff applies to BOTH paths ---

    test("accepting an invite stops both piggyback and standalone") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.startStandaloneWindow()
        c.onInviteAccepted()
        c.isStandaloneWindowActive() shouldBe false
        c.shouldScan(exchangeModeActive = true, scanInvitesEnabled = true) shouldBe false
    }

    // --- the one-shot window bug fix: restart resets the timer and accepted ---

    test("restart after expiry opens a fresh window (one-shot fix)") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.startStandaloneWindow()
        now = 300_000L // expired
        c.isStandaloneWindowActive() shouldBe false
        c.startStandaloneWindow() // re-open
        now = 300_500L
        c.isStandaloneWindowActive() shouldBe true
    }

    test("restart clears the accepted flag") {
        var now = 0L
        val c = InviteScanController(nowMs = { now })
        c.startStandaloneWindow()
        c.onInviteAccepted()
        c.isStandaloneWindowActive() shouldBe false
        c.startStandaloneWindow() // re-open clears accepted
        c.isStandaloneWindowActive() shouldBe true
    }
})
```

- [ ] **Step 2: Run the tests to verify they fail on the old API**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.core.InviteScanControllerTest" 2>&1 | grep -iE "unresolved|shouldScan|startStandaloneWindow|isStandaloneWindowActive|BUILD"`
Expected: FAIL — `Unresolved reference 'startStandaloneWindow'` / `isStandaloneWindowActive`, and `shouldScan` arity mismatch (old takes 1 arg, test passes 2).

- [ ] **Step 3: Rewrite InviteScanController to the two-path interface**

Replace all of `InviteScanController.kt` with:

```kotlin
package com.mkcreations.cryptokeyboard.core

/**
 * Pure session gate for invite scanning. Two independent paths:
 *
 *  (a) Piggyback — scan while Exchange Mode is active and the user's invite
 *      toggle is on. Near-zero extra cost: it reuses the screen scan the
 *      accessibility service already performs for decryption.
 *
 *  (b) Standalone — a fresh [windowMs] (5-minute) window opened on demand from
 *      the Import Keys menu. Lets a user receive an invite WITHOUT enabling
 *      full Exchange Mode, and self-expires so a forgotten scan cannot drain
 *      battery.
 *
 * Both paths stop after one invite is accepted ([onInviteAccepted]), so a
 * single accepted invite ends the session.
 */
class InviteScanController(
    private val windowMs: Long = 5 * 60 * 1000L,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    private val nowMs = nowMs
    private var standaloneStartMs: Long? = null
    private var accepted = false

    /**
     * Opens a fresh standalone window. Always resets the timer (fixes the old
     * one-shot bug where the window could never re-open) and clears the
     * accepted flag so a new session can begin.
     */
    fun startStandaloneWindow() {
        standaloneStartMs = nowMs()
        accepted = false
    }

    /** True while the standalone window is open and no invite has been accepted. */
    fun isStandaloneWindowActive(): Boolean {
        if (accepted) return false
        val start = standaloneStartMs ?: return false
        return nowMs() - start < windowMs
    }

    /**
     * Master scan gate: scan when EITHER the piggyback path (Exchange Mode +
     * toggle) OR the standalone window is active. Always false after an invite
     * is accepted.
     */
    fun shouldScan(exchangeModeActive: Boolean, scanInvitesEnabled: Boolean): Boolean {
        if (accepted) return false
        if (exchangeModeActive && scanInvitesEnabled) return true
        return isStandaloneWindowActive()
    }

    /** Called once a contact is saved — ends the current scan session. */
    fun onInviteAccepted() {
        accepted = true
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.core.InviteScanControllerTest" 2>&1 | tail -25`
Expected: all 10 tests PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify the full suite still passes (call site not yet updated — expect the SERVICE task to fix; this step documents the current state)**

Run: `./gradlew :app:testCryptoDebugUnitTest 2>&1 | grep -iE "unresolved|shouldScan|BUILD"`
Note: the crypto-flavor `CryptoAccessibilityService.kt` still calls `inviteScan.shouldScan(settings.scanInvitesEnabled)` (1-arg) and `inviteScan.beginWindow()`. Those symbols no longer exist, so **compile of the crypto main source will now FAIL** until Task 5 updates the service. This is expected — Task 1 intentionally lands the interface first. **Do not be alarmed; Task 5 restores compilation.** Record the failure reason in the ledger.

---

### Task 2: DeviceTier (low-RAM detection + scan-cost knobs)

**Files:**
- Create: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/DeviceTier.kt`
- Test: `app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/core/DeviceTierTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin; the `ActivityManager` call lives in the service, Task 5).
- Produces: `object DeviceTier` with `enum class Tier { LOW, HIGH }`, `fun classify(isLowRamDevice: Boolean, totalMemBytes: Long): Tier`, `fun debounceMs(tier: Tier): Long`, `fun maxScanDepth(tier: Tier): Int`, `fun maxNodes(tier: Tier): Int`, `fun maxQuickCheckDepth(tier: Tier): Int`.

- [ ] **Step 1: Write the failing test**

Create `DeviceTierTest.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.mkcreations.cryptokeyboard.core.DeviceTier.Tier

class DeviceTierTest : FunSpec({

    val GIB = 1024L * 1024 * 1024

    test("isLowRamDevice always classifies LOW regardless of RAM") {
        DeviceTier.classify(isLowRamDevice = true, totalMemBytes = 8 * GIB) shouldBe Tier.LOW
    }

    test("RAM under 4 GiB classifies LOW") {
        DeviceTier.classify(isLowRamDevice = false, totalMemBytes = 3 * GIB) shouldBe Tier.LOW
    }

    test("RAM at exactly 4 GiB classifies HIGH (threshold is exclusive)") {
        DeviceTier.classify(isLowRamDevice = false, totalMemBytes = 4 * GIB) shouldBe Tier.HIGH
    }

    test("RAM over 4 GiB classifies HIGH") {
        DeviceTier.classify(isLowRamDevice = false, totalMemBytes = 8 * GIB) shouldBe Tier.HIGH
    }

    test("LOW debounce is longer than HIGH debounce (fewer scans)") {
        DeviceTier.debounceMs(Tier.LOW) shouldBe 800L
        DeviceTier.debounceMs(Tier.HIGH) shouldBe 400L
    }

    test("LOW scan depth and node caps are lower than HIGH") {
        (DeviceTier.maxScanDepth(Tier.LOW) < DeviceTier.maxScanDepth(Tier.HIGH)) shouldBe true
        (DeviceTier.maxNodes(Tier.LOW) < DeviceTier.maxNodes(Tier.HIGH)) shouldBe true
        (DeviceTier.maxQuickCheckDepth(Tier.LOW) < DeviceTier.maxQuickCheckDepth(Tier.HIGH)) shouldBe true
    }

    test("HIGH caps match the current hardcoded defaults") {
        DeviceTier.maxScanDepth(Tier.HIGH) shouldBe 20
        DeviceTier.maxNodes(Tier.HIGH) shouldBe 15
        DeviceTier.maxQuickCheckDepth(Tier.HIGH) shouldBe 10
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.core.DeviceTierTest" 2>&1 | grep -iE "unresolved|BUILD"`
Expected: FAIL with `Unresolved reference 'DeviceTier'`.

- [ ] **Step 3: Write DeviceTier**

Create `DeviceTier.kt`:

```kotlin
package com.mkcreations.cryptokeyboard.core

/**
 * Device-capability tier used to bound screen-scanning cost so low-end / low-RAM
 * devices are not asked to do per-frame work that causes lag, while modern devices
 * keep accurate live updates. The tier is decided once at service connect from
 * [android.app.ActivityManager.isLowRamDevice] plus total RAM; all knobs only ever
 * REDUCE work on LOW and match the previous hardcoded defaults on HIGH.
 */
object DeviceTier {

    enum class Tier { LOW, HIGH }

    /** 4 GiB — at-or-above is HIGH; below is LOW. */
    private const val LOW_RAM_THRESHOLD_BYTES = 4L * 1024 * 1024 * 1024

    fun classify(isLowRamDevice: Boolean, totalMemBytes: Long): Tier =
        if (isLowRamDevice || totalMemBytes < LOW_RAM_THRESHOLD_BYTES) Tier.LOW else Tier.HIGH

    /** Content-change debounce: LOW scans less often to save CPU/battery. */
    fun debounceMs(tier: Tier): Long = when (tier) {
        Tier.LOW -> 800L
        Tier.HIGH -> 400L
    }

    /** Max accessibility-tree depth traversed when finding encrypted nodes. */
    fun maxScanDepth(tier: Tier): Int = when (tier) {
        Tier.LOW -> 10
        Tier.HIGH -> 20
    }

    /** Max encrypted nodes collected per scan. */
    fun maxNodes(tier: Tier): Int = when (tier) {
        Tier.LOW -> 10
        Tier.HIGH -> 15
    }

    /** Max depth for the quick "does this screen have hidden data" check. */
    fun maxQuickCheckDepth(tier: Tier): Int = when (tier) {
        Tier.LOW -> 7
        Tier.HIGH -> 10
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testCryptoDebugUnitTest --tests "com.mkcreations.cryptokeyboard.core.DeviceTierTest" 2>&1 | tail -20`
Expected: all 7 tests PASS, `BUILD SUCCESSFUL`.

---

### Task 3: OverlayManager source-package tracking + targeted removal

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/OverlayManager.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `data class OverlayEntry` gains `val sourcePackage: String`; `fun showOverlay(overlayId: String, decryptedText: String, keyLabel: String, bounds: Rect, sourcePackage: String)` (**breaking** — adds a required trailing param); `fun showInviteBubble(overlayId: String, rawMessage: String, bounds: Rect, sourcePackage: String, onTap: (String) -> Unit)` (**breaking** — inserts `sourcePackage` before `onTap`); new `fun removeOverlaysNotFrom(pkg: String)` that removes entries (from BOTH `activeOverlays` and `activeInviteBubbles`) whose `sourcePackage != pkg`.

- [ ] **Step 1: Add sourcePackage to OverlayEntry**

In `OverlayManager.kt`, change the `OverlayEntry` data class (lines 46-51) to:

```kotlin
    data class OverlayEntry(
        val view: View,
        val params: WindowManager.LayoutParams,
        val bounds: Rect,
        val timestamp: Long,
        val sourcePackage: String
    )
```

- [ ] **Step 2: Thread sourcePackage through showOverlay and stamp the entry**

Change the `showOverlay` signature (line 57) to add `sourcePackage: String` after `bounds`, and update the `activeOverlays[overlayId] = OverlayEntry(...)` call (line 81) to pass it:

```kotlin
    fun showOverlay(
        overlayId: String,
        decryptedText: String,
        keyLabel: String,
        bounds: Rect,
        sourcePackage: String
    ) {
        if (activeOverlays.size >= maxOverlays && !activeOverlays.containsKey(overlayId)) {
            removeOldestOverlay()
        }

        val existing = activeOverlays[overlayId]
        if (existing != null) {
            if (existing.bounds != bounds) {
                updateOverlayPosition(overlayId, bounds)
            }
            return
        }

        try {
            val overlayView = createOverlayView(decryptedText, keyLabel, bounds)
            val params = createLayoutParams(bounds)

            windowManager.addView(overlayView, params)

            activeOverlays[overlayId] = OverlayEntry(
                view = overlayView,
                params = params,
                bounds = Rect(bounds),
                timestamp = System.currentTimeMillis(),
                sourcePackage = sourcePackage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: $overlayId", e)
        }
    }
```

- [ ] **Step 3: Thread sourcePackage through showInviteBubble and stamp the entry**

Change the `showInviteBubble` signature (line 184) to insert `sourcePackage: String` before `onTap`, and stamp the entry (line 203):

```kotlin
    fun showInviteBubble(
        overlayId: String,
        rawMessage: String,
        bounds: Rect,
        sourcePackage: String,
        onTap: (String) -> Unit
    ) {
        if (activeInviteBubbles.containsKey(overlayId)) return

        val bubble = TextView(context).apply {
            text = "\uD83D\uDD11 Key invite \u2014 tap to review"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xF56A1B9A.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 13f
            setOnClickListener { onTap(rawMessage) }
        }
        val params = createInviteLayoutParams(bounds)
        try {
            windowManager.addView(bubble, params)
            activeInviteBubbles[overlayId] = OverlayEntry(
                view = bubble,
                params = params,
                bounds = Rect(bounds),
                timestamp = System.currentTimeMillis(),
                sourcePackage = sourcePackage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show invite bubble: $overlayId", e)
        }
    }
```

- [ ] **Step 4: Add removeOverlaysNotFrom**

Add this method after `removeAllInviteBubbles()` (line 226):

```kotlin
    /**
     * Removes every overlay (decrypt and invite) that was NOT produced by [pkg].
     * Called on a foreground app switch so the old app's overlays can never be
     * orphaned over a new app or the home screen. A no-op when all overlays
     * already belong to [pkg].
     */
    fun removeOverlaysNotFrom(pkg: String) {
        val staleOverlays = activeOverlays.entries.filter { it.value.sourcePackage != pkg }.map { it.key }
        staleOverlays.forEach { removeOverlay(it) }

        val staleBubbles = activeInviteBubbles.entries.filter { it.value.sourcePackage != pkg }.map { it.key }
        staleBubbles.forEach { removeInviteBubble(it) }
    }
```

- [ ] **Step 5: Verify the crypto flavor compiles (expect failures at the two CALL SITES in the service, fixed in Task 5)**

Run: `./gradlew :app:compileCryptoDebugKotlin 2>&1 | grep -iE "sourcePackage|showOverlay|showInviteBubble|error:|BUILD"`
Expected: the two service call sites (`CryptoAccessibilityService.kt` lines ~372/392/408/426) now miss the new `sourcePackage` arg → compile errors. Record them; Task 5 fixes them. Do NOT proceed to Task 4 assuming compilation.

---

### Task 4: Service gate rework — invite decoupling + unconditional app-switch cleanup + idle guard

This is the core fix. It updates `CryptoAccessibilityService` to (1) use the new `InviteScanController.shouldScan(exchange, toggle)` gate in three places, (2) handle `TYPE_WINDOW_STATE_CHANGED` cleanup unconditionally before the exchange gate, (3) gate the *decrypt* branches on Exchange Mode while letting *invites* through on the standalone path, and (4) guard the idle auto-off so it never kills a standalone window. It also fixes the Task 3 call sites.

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/CryptoAccessibilityService.kt`

**Interfaces:**
- Consumes: `InviteScanController.shouldScan(exchangeModeActive, scanInvitesEnabled)` / `isStandaloneWindowActive()` (Task 1); `OverlayManager.showOverlay(..., sourcePackage)` / `showInviteBubble(..., sourcePackage, onTap)` / `removeOverlaysNotFrom(pkg)` (Task 3).
- Produces: no new public API except reusing `currentSettings()` / `monitoredPackage`. Task 5 adds the tier field.

- [ ] **Step 1: Extract a private scan-active helper and rewire the cache/live decrypt branches**

Add a private helper near `currentSettings()` and update `processNode` (lines 357-436). First add the helper:

```kotlin
    /** True when invite scanning is active via piggyback (Exchange Mode + toggle) or the standalone window. */
    private fun inviteScanActive(): Boolean =
        inviteScan.shouldScan(
            exchangeModeActive = SharedState.isExchangeModeActive,
            scanInvitesEnabled = currentSettings().scanInvitesEnabled
        )
```

- [ ] **Step 2: Replace processNode with the restructured version**

Replace the whole `processNode` method (lines 357-436) with:

```kotlin
    private fun processNode(node: ScreenScanner.EncryptedNode) {
        val store = keyStore ?: return
        val exchangeOn = SharedState.isExchangeModeActive

        if (overlayManager?.hasOverlay(node.nodeId) == true) {
            beginOverlayModification()
            overlayManager?.updateOverlayPosition(node.nodeId, node.bounds)
            endOverlayModification()
            return
        }

        // Decrypt cache: only surface a cached plaintext when Exchange Mode is on.
        // Invite-only (standalone) mode must never expose decrypted content.
        val cached = DecryptCache.get(node.fullText)
        if (cached != null) {
            if (exchangeOn) {
                beginOverlayModification()
                overlayManager?.showOverlay(
                    overlayId = node.nodeId,
                    decryptedText = cached.secretMessage,
                    keyLabel = cached.keyLabel,
                    bounds = node.bounds,
                    sourcePackage = monitoredPackage ?: ""
                )
                endOverlayModification()
            }
            return
        }

        if (MessageProcessor.isKnownReplay(node.fullText)) {
            return
        }

        when (val result = MessageProcessor.detectAndDecrypt(node.fullText, store.getAllKeys())) {
            is MessageProcessor.DecryptResult.Success -> {
                // Decryption requires Exchange Mode; invite-only mode skips it.
                if (!exchangeOn) return
                DecryptCache.put(node.fullText, result.secretMessage, result.keyLabel)
                beginOverlayModification()
                overlayManager?.showOverlay(
                    overlayId = node.nodeId,
                    decryptedText = result.secretMessage,
                    keyLabel = result.keyLabel,
                    bounds = node.bounds,
                    sourcePackage = monitoredPackage ?: ""
                )
                endOverlayModification()
            }
            is MessageProcessor.DecryptResult.CorruptedData -> {
                if (!exchangeOn) return
                val indicatorBounds = Rect(
                    node.bounds.right - 20,
                    node.bounds.top,
                    node.bounds.right,
                    node.bounds.top + 20
                )
                beginOverlayModification()
                overlayManager?.showOverlay(
                    overlayId = "corrupt_${node.nodeId}",
                    decryptedText = "\uD83D\uDD34",
                    keyLabel = "",
                    bounds = indicatorBounds,
                    sourcePackage = monitoredPackage ?: ""
                )
                endOverlayModification()
            }
            is MessageProcessor.DecryptResult.NoMatchingKey -> {}
            is MessageProcessor.DecryptResult.DecryptionFailed -> {}
            is MessageProcessor.DecryptResult.NotEncrypted -> {}
            is MessageProcessor.DecryptResult.Replayed -> {}
            // In-band key invite: surface a tappable bubble whenever invite
            // scanning is active (Exchange Mode piggyback OR standalone window).
            is MessageProcessor.DecryptResult.KeyInvite -> {
                if (inviteScanActive()) {
                    val bubbleId = "invite_${node.nodeId}"
                    overlayManager?.showInviteBubble(
                        overlayId = bubbleId,
                        rawMessage = node.fullText,
                        bounds = node.bounds,
                        sourcePackage = monitoredPackage ?: ""
                    ) { raw ->
                        KeyInviteApprovalActivity.start(this, raw)
                    }
                }
            }
        }
    }
```

- [ ] **Step 3: Rework onAccessibilityEvent — unconditional app-switch cleanup before the gate**

Replace the whole `onAccessibilityEvent` method (lines 208-248) with:

```kotlin
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // App-switch overlay cleanup runs UNCONDITIONALLY, before the exchange-mode
        // gate and before the modification-grace early-returns, so overlays can never
        // be orphaned by a filtered or missed event. Only a real app switch (a new
        // non-system package) removes the old app's overlays; a notification-shade
        // pull (systemui) is filtered out so overlays do not flicker.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged(event)
        }

        val scanActive = SharedState.isExchangeModeActive || inviteScan.isStandaloneWindowActive()
        if (!scanActive) return

        if (isModifyingOverlays) return
        val timeSinceModification = System.currentTimeMillis() - lastOverlayModificationTime
        if (timeSinceModification < OVERLAY_MODIFICATION_GRACE_MS) return

        // Idle auto-off applies to Exchange Mode only — it must never kill an
        // in-progress standalone invite window (that window is time-bounded itself).
        if (SharedState.isExchangeModeActive && SharedState.isIdle(10)) {
            SharedState.isExchangeModeActive = false
            safeRemoveAllOverlays()
            DecryptCache.clearAll()
            return
        }

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (monitoredPackage != null && pkg != monitoredPackage) return
                if (!isScrolling) {
                    scheduleUpdate(currentDebounceMs())
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                onScrollDetected()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Handled above by handleWindowStateChanged; schedule a re-scan so
                // returning to an app re-detects and re-shows its overlays.
                scheduleUpdate(currentDebounceMs())
            }
        }
    }

    /**
     * On a foreground app switch, remove the previous app's overlays (decrypt and
     * invite) so they can never linger over the new app or the home screen. Same-app
     * re-focus and transient system surfaces (shade/status bar) are ignored to avoid
     * flicker.
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // Transient / self surfaces are not real app switches.
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return
        if (pkg == monitoredPackage) return // same app re-focused; keep overlays

        monitoredPackage = pkg
        SharedState.currentAppPackage = pkg
        beginOverlayModification()
        overlayManager?.removeOverlaysNotFrom(pkg)
        endOverlayModification()
        DecryptCache.clearAll()
        consecutiveEmptyScans = 0
    }
```

Note: `currentDebounceMs()` is introduced in Task 5; until then it does not exist. To keep Task 4 self-contained and compiling after Task 3, **temporarily** keep using the `CONTENT_CHANGE_DEBOUNCE_MS` constant in Step 3 and let Task 5 swap it to `currentDebounceMs()`. Replace `currentDebounceMs()` with `CONTENT_CHANGE_DEBOUNCE_MS` in this step if Task 5 has not run yet.

- [ ] **Step 4: Remove the now-dead beginWindow() calls in startPanicHandlerIfEnabled and refreshPanicTriggers**

In `startPanicHandlerIfEnabled` (line 135) delete:
```kotlin
        if (settings.scanInvitesEnabled) inviteScan.beginWindow()
```
In `refreshPanicTriggers` (line 202) delete:
```kotlin
        if (settings.scanInvitesEnabled) inviteScan.beginWindow()
```
(The piggyback path needs no explicit window — it is gated by Exchange Mode + toggle. The standalone window is opened only from the Import Keys menu in Task 6.)

- [ ] **Step 5: Verify crypto compile is now green (Task 3 call sites fixed by the sourcePackage args added above)**

Run: `./gradlew :app:compileCryptoDebugKotlin 2>&1 | grep -iE "sourcePackage|showOverlay|showInviteBubble|unresolved|error:|BUILD"`
Expected: `BUILD SUCCESSFUL` (or only the `currentDebounceMs` symbol if you chose to defer it — then run Task 5 before this passes).

---

### Task 5: Apply DeviceTier — tier-aware debounce + ScreenScanner depth/nodes

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/CryptoAccessibilityService.kt` (add tier field, compute at connect, use in debounce + scanner calls)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/ScreenScanner.kt` (parametrize depth/nodes)

**Interfaces:**
- Consumes: `DeviceTier.classify/debounceMs/maxScanDepth/maxNodes/maxQuickCheckDepth` (Task 2).
- Produces: `ScreenScanner.findEncryptedNodes(rootNode, maxDepth, maxNodes)` and `ScreenScanner.quickCheck(rootNode, maxDepth)` (**breaking** — new required params). Service keeps a `deviceTier` field and a `currentDebounceMs(): Long`.

- [ ] **Step 1: Parametrize ScreenScanner depth/nodes**

In `ScreenScanner.kt`, change `findEncryptedNodes` (line 22) and thread the caps through `scanNode`; change `quickCheck` (line 129) and thread through `quickCheckNode`. Replace the relevant methods with:

```kotlin
    fun findEncryptedNodes(
        rootNode: AccessibilityNodeInfo?,
        maxDepth: Int = 20,
        maxNodes: Int = 15
    ): List<EncryptedNode> {
        if (rootNode == null) return emptyList()

        val pkg = rootNode.packageName?.toString()
        if (pkg == "com.android.systemui" || pkg == "android") return emptyList()

        val results = mutableListOf<EncryptedNode>()
        scanNode(rootNode, results, 0, maxDepth, maxNodes)
        return results
    }

    private fun scanNode(
        node: AccessibilityNodeInfo,
        results: MutableList<EncryptedNode>,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int
    ) {
        if (depth > maxDepth) return
        if (results.size >= maxNodes) return

        try {
            val hint = node.hintText?.toString()
            val skip = SensitiveNodeFilter.shouldSkip(node.isPassword, node.className?.toString(), hint)

            if (!skip) {
                val text = node.text?.toString()
                if (!text.isNullOrEmpty() && text.length > 8) {
                    if (InvisibleEncoder.containsHiddenData(text)) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)

                        if (bounds.width() > 0 && bounds.height() > 0) {
                            val parentBounds = getParentBounds(node)
                            val nodeId = generateNodeId(text, bounds)

                            results.add(
                                EncryptedNode(
                                    nodeId = nodeId,
                                    fullText = text,
                                    bounds = bounds,
                                    parentBounds = parentBounds
                                )
                            )
                        }
                    }
                }

                val contentDesc = node.contentDescription?.toString()
                if (!contentDesc.isNullOrEmpty() && contentDesc.length > 8) {
                    if (InvisibleEncoder.containsHiddenData(contentDesc)) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)

                        if (bounds.width() > 0 && bounds.height() > 0) {
                            val parentBounds = getParentBounds(node)
                            val nodeId = generateNodeId(contentDesc, bounds)

                            results.add(
                                EncryptedNode(
                                    nodeId = nodeId,
                                    fullText = contentDesc,
                                    bounds = bounds,
                                    parentBounds = parentBounds
                                )
                            )
                        }
                    }
                }
            }

            val childCount = node.childCount.coerceAtMost(30)
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    scanNode(child, results, depth + 1, maxDepth, maxNodes)
                } finally {
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error at depth $depth", e)
        }
    }
```

And change `quickCheck` (line 129) + `quickCheckNode` (line 134):

```kotlin
    fun quickCheck(rootNode: AccessibilityNodeInfo?, maxDepth: Int = 10): Boolean {
        if (rootNode == null) return false
        return quickCheckNode(rootNode, 0, maxDepth)
    }

    private fun quickCheckNode(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): Boolean {
        if (depth > maxDepth) return false

        try {
            val hint = node.hintText?.toString()
            val skip = SensitiveNodeFilter.shouldSkip(node.isPassword, node.className?.toString(), hint)
            if (!skip) {
                val text = node.text?.toString()
                if (!text.isNullOrEmpty() && text.length > 8) {
                    if (InvisibleEncoder.containsHiddenData(text)) return true
                }

                val contentDesc = node.contentDescription?.toString()
                if (!contentDesc.isNullOrEmpty() && contentDesc.length > 8) {
                    if (InvisibleEncoder.containsHiddenData(contentDesc)) return true
                }
            }

            for (i in 0 until node.childCount.coerceAtMost(15)) {
                val child = node.getChild(i) ?: continue
                try {
                    if (quickCheckNode(child, depth + 1, maxDepth)) return true
                } finally {
                    child.recycle()
                }
            }
        } catch (e: Exception) {}

        return false
    }
```

- [ ] **Step 2: Add the deviceTier field + currentDebounceMs() to the service and compute it at connect**

In `CryptoAccessibilityService.kt`, add a field near the other state (after `inviteScan`):
```kotlin
    // Device capability tier: bounds scan cost on low-RAM hardware to avoid lag.
    private var deviceTier: DeviceTier.Tier = DeviceTier.Tier.HIGH
```
Add the import:
```kotlin
import com.mkcreations.cryptokeyboard.core.DeviceTier
```
In `onServiceConnected` (after line 113, before `startCacheCleanup()`), compute the tier:
```kotlin
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        deviceTier = DeviceTier.classify(am.isLowRamDevice, memInfo.totalMem)
        Log.d(TAG, "Device tier: $deviceTier (lowRam=${am.isLowRamDevice}, totalMem=${memInfo.totalMem})")
```
Add the helper:
```kotlin
    /** Tier-aware content-change debounce (longer on low-RAM devices). */
    private fun currentDebounceMs(): Long = DeviceTier.debounceMs(deviceTier)
```

- [ ] **Step 3: Swap the debounce constant for currentDebounceMs() and pass tier caps to the scanner**

Replace `scheduleUpdate(CONTENT_CHANGE_DEBOUNCE_MS)` (the three remaining call sites in `onAccessibilityEvent` and `onScrollEnded`/`scheduleUpdate` callers) with `scheduleUpdate(currentDebounceMs())`. In `processVisibleContent`, change:
```kotlin
            if (!ScreenScanner.quickCheck(rootNode)) {
```
to:
```kotlin
            if (!ScreenScanner.quickCheck(rootNode, DeviceTier.maxQuickCheckDepth(deviceTier))) {
```
and change:
```kotlin
            val encryptedNodes = ScreenScanner.findEncryptedNodes(rootNode)
```
to:
```kotlin
            val encryptedNodes = ScreenScanner.findEncryptedNodes(
                rootNode,
                DeviceTier.maxScanDepth(deviceTier),
                DeviceTier.maxNodes(deviceTier)
            )
```

- [ ] **Step 4: Verify crypto compile + full suite**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:assembleCryptoDebug 2>&1 | grep -iE "unresolved|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`; crypto suite passes (existing tests unaffected — scanner params have defaults).

---

### Task 6: UI — "Scan for Invites (5 min)" menu item + settings relabel

**Files:**
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyManagementScreen.kt` (add menu item after "From File", line 254)
- Modify: `app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/CryptoSettingsScreen.kt` (relabel toggle, lines 126-130)

**Interfaces:**
- Consumes: `CryptoAccessibilityService.instance` (to open the standalone window), `InviteScanController.startStandaloneWindow()` exposed via a new service method.
- Produces: new `fun CryptoAccessibilityService.startInviteScanWindow()` (or reuse an existing public entry). Add to the service (in `CryptoAccessibilityService.kt`):
    ```kotlin
    /** Opens a fresh 5-minute standalone invite-scan window (Import Keys menu). */
    fun startInviteScanWindow() {
        inviteScan.startStandaloneWindow()
        SharedState.recordActivity()
    }
    ```

- [ ] **Step 1: Add startInviteScanWindow() to the service**

Add the method above (near `onInviteAccepted()`, line ~190). Import `SharedState` is already present.

- [ ] **Step 2: Add the Import Keys menu item**

In `KeyManagementScreen.kt`, after the "From File" `DropdownMenuItem` (ends line 254, before the closing `}` of `DropdownMenu` at line 255), insert:

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
Add the import at the top:
```kotlin
import com.mkcreations.cryptokeyboard.accessibility.CryptoAccessibilityService
```
(`Icons.Default.Share` and `Toast`/`context` are already imported/available in this file.)

- [ ] **Step 3: Relabel the settings toggle to reflect the piggyback role**

In `CryptoSettingsScreen.kt`, change the toggle (lines 126-130) summary to:
```kotlin
                summary = if (inviteSettings.scanInvitesEnabled)
                    "On — while Exchange Mode is on, also detect key invitations"
                else
                    "Off — detect key invitations shared in cover messages during Exchange Mode",
```

- [ ] **Step 4: Verify both flavors compile + full suite**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug :app:assembleFlorisDebug 2>&1 | grep -iE "unresolved|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`; crypto + floris suites pass.

---

### Task 7: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full verify command**

Run: `./gradlew :app:testCryptoDebugUnitTest :app:testFlorisDebugUnitTest :app:assembleCryptoDebug :app:assembleFlorisDebug 2>&1 | tail -8`
Expected: `BUILD SUCCESSFUL`. Both APKs produced.

- [ ] **Step 2: Confirm test counts**

Run:
```bash
grep -rho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testCryptoDebugUnitTest/*.xml | awk -F'"' '{t+=$2;f+=$6;e+=$8} END {print "crypto tests="t" failures="f" errors="e}'
grep -rho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testFlorisDebugUnitTest/*.xml | awk -F'"' '{t+=$2;f+=$6;e+=$8} END {print "floris tests="t" failures="f" errors="e}'
```
Expected: crypto 270+ (188 baseline + 10 InviteScanController + 7 DeviceTier = 205 minimum, but the old 5 InviteScanController tests were replaced by 10, so net +5+7 = 200 crypto), floris exactly 82, both `failures=0 errors=0`. Update the ledger with actuals.

- [ ] **Step 3: Install the crypto APK and hand off for on-device verification**

APK: `app/build/outputs/apk/crypto/debug/app-crypto-debug.apk`. Ask the user to verify, then STOP and wait for sign-off (per the no-commit standing rule):
1. **Invite bubble (standalone):** Import Keys → "Scan for Invites (5 min)" with Exchange Mode OFF → receive an invite cover message → purple "Key invite — tap to review" bubble appears → tap → approval Activity opens → Save saves the contact.
2. **Invite bubble (piggyback):** Exchange Mode ON + "Scan for key invitations" ON → invite bubble appears.
3. **No decrypt in standalone:** with Exchange Mode OFF + standalone window, a normal encrypted message shows NO decrypted overlay (only invites surface).
4. **Overlay cleanup:** open an app showing a decrypted overlay → switch to another app → overlay gone; go Home → gone; pull the notification shade → overlay STAYS (no flicker); switch via recents → gone; return to the app → overlay re-appears.
5. **No stuck overlays:** close the encrypted app entirely → no overlays linger over the launcher.
6. **Low-end (if available):** on a low-RAM device/emulator, overlays still display (not suppressed) and scanning does not lag.

---

### Task 8: Commit (only after on-device sign-off)

**Files:** none.

- [ ] **Step 1: Stage and commit after user confirms on-device pass**

Ask the user to confirm all six on-device checks passed. Only then commit. Suggested split (or single commit, per user preference):
```bash
git add app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/InviteScanController.kt \
        app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/core/InviteScanControllerTest.kt \
        app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/core/DeviceTier.kt \
        app/src/testCrypto/kotlin/com/mkcreations/cryptokeyboard/core/DeviceTierTest.kt \
        app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/OverlayManager.kt \
        app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/ScreenScanner.kt \
        app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/accessibility/CryptoAccessibilityService.kt \
        app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/KeyManagementScreen.kt \
        app/src/crypto/kotlin/com/mkcreations/cryptokeyboard/ui/CryptoSettingsScreen.kt
git commit -m "fix: decouple invite scanning from Exchange Mode and tie overlay cleanup to producing app"
```
Do **not** push.

---

## Self-Review

- **Spec coverage:** Piggyback + standalone (Task 1/4/6) ✓; 5-min standalone from Import Keys (Task 1/6) ✓; no-decrypt in invite-only (Task 4 step 2 branches) ✓; shade no-flicker vs app-switch-clears (Task 4 `handleWindowStateChanged` systemui filter) ✓; re-appear on return (Task 4 `TYPE_WINDOW_STATE_CHANGED` schedules re-scan) ✓; one-shot window fix (Task 1 `startStandaloneWindow` resets) ✓; DeviceTier low-RAM (Task 2/5) ✓; settings relabel (Task 6) ✓.
- **Placeholder scan:** No TBD/TODO; every step has concrete code. The one intentional deferral (`currentDebounceMs` introduced in Task 5, temporarily `CONTENT_CHANGE_DEBOUNCE_MS` in Task 4) is explicitly flagged in Task 4 Step 3.
- **Type consistency:** `shouldScan(exchangeModeActive, scanInvitesEnabled)` matches Task 1 def ↔ Task 4 `inviteScanActive()`; `startStandaloneWindow()` matches Task 1 ↔ Task 6 `startInviteScanWindow()`; `showOverlay(..., sourcePackage)` and `showInviteBubble(..., sourcePackage, onTap)` match Task 3 def ↔ Task 4 call sites; `findEncryptedNodes(rootNode, maxDepth, maxNodes)` / `quickCheck(rootNode, maxDepth)` match Task 5 def ↔ call sites.
- **Review Focus:** each of the 5 has an owning task + on-device step (Task 7 step 3): taps (T3/T7-1), no-decrypt (T4/T7-3), shade no-flicker (T4/T7-4), re-appear (T4/T7-4), low-end display (T2/T7-6).
