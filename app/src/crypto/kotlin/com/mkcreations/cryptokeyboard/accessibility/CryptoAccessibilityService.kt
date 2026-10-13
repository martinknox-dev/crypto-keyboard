package com.mkcreations.cryptokeyboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.mkcreations.cryptokeyboard.core.DeviceTier
import com.mkcreations.cryptokeyboard.core.InviteScanController
import com.mkcreations.cryptokeyboard.core.OverlayRefreshPolicy
import com.mkcreations.cryptokeyboard.core.OverlayReconciler
import com.mkcreations.cryptokeyboard.core.PanicConfig
import com.mkcreations.cryptokeyboard.core.PanicHandler
import com.mkcreations.cryptokeyboard.core.SharedState
import com.mkcreations.cryptokeyboard.core.WindowedCounter
import com.mkcreations.cryptokeyboard.crypto.CryptoSession
import com.mkcreations.cryptokeyboard.crypto.DecryptCache
import com.mkcreations.cryptokeyboard.crypto.MessageProcessor
import com.mkcreations.cryptokeyboard.model.AppSettings
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore
import com.mkcreations.cryptokeyboard.storage.SettingsStore
import com.mkcreations.cryptokeyboard.ui.KeyInviteApprovalActivity

class CryptoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CryptoA11y"
        private const val PANIC_TAG = "CryptoPanic"
        var instance: CryptoAccessibilityService? = null
            private set
    }

    private var overlayManager: OverlayManager? = null
    private var keyStore: SecureKeyStore? = null
    private var panicHandler: PanicHandler? = null

    // Panic trigger counter (see onKeyTapped)
    private val spacebarTapCounter = WindowedCounter(requiredCount = 3, windowMs = 700L)

    // In-band invite scan gate: opt-in toggle + 5-minute window + stop-on-accept.
    private val inviteScan = InviteScanController()

    // Device capability tier: bounds scan cost on low-RAM hardware to avoid lag.
    private var deviceTier: DeviceTier.Tier = DeviceTier.Tier.HIGH

    // Hot paths (per-key-tap) must not decrypt the whole
    // preference store per event — cached here, refreshed on connect and on
    // every refreshPanicTriggers() from the settings UI.
    @Volatile
    private var settingsSnapshot: AppSettings? = null

    private fun currentSettings(): AppSettings =
        settingsSnapshot ?: SettingsStore.getInstance(this).loadSettings().also {
            updateSettingsSnapshot(it)
        }

    /**
     * Publishes fresh settings: the hot-path snapshot plus the session-memory
     * TTLs, so both in-memory stores follow [AppSettings.cacheDurationMinutes].
     */
    private fun updateSettingsSnapshot(settings: AppSettings) {
        settingsSnapshot = settings
        CryptoSession.applySettings(settings)
    }

    /** True when invite scanning is active: Exchange Mode on + the user's invite toggle. */
    private fun inviteScanActive(): Boolean =
        inviteScan.shouldScan(
            exchangeModeActive = SharedState.isExchangeModeActive,
            scanInvitesEnabled = currentSettings().scanInvitesEnabled
        )

    /** Tier-aware content-change debounce (longer on low-RAM devices). */
    private fun currentDebounceMs(): Long = DeviceTier.debounceMs(deviceTier)

    /**
     * One-line snapshot of the panic trigger config, logged on service start and
     * settings refresh so on-device logs show exactly which triggers are armed.
     */
    private fun logPanicConfig(settings: AppSettings, source: String) {
        Log.d(
            PANIC_TAG,
            "panic config ($source): master=${settings.panicEnabled} " +
                "armed=${PanicConfig.isArmed(settings)} " +
                "triggers[shake=${settings.panicTriggerShake} " +
                "faceDown=${settings.panicTriggerFaceDown} " +
                "spacebar=${settings.panicTriggerSpacebar}] " +
                "actions[prevIme=${settings.panicActionSwitchIme} " +
                "dismiss=${settings.panicActionRemoveOverlays} " +
                "clearCache=${settings.panicActionClearCache} " +
                "wipe=${settings.panicActionWipeKeys}]"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingUpdate: Runnable? = null
    private var pendingScrollEnd: Runnable? = null
    private var cacheCleanupRunnable: Runnable? = null

    private val SCROLL_END_DELAY_MS = 300L
    private val CONTENT_CHANGE_DEBOUNCE_MS = 400L
    private val CACHE_CLEANUP_INTERVAL_MS = 60_000L

    private var screenWidth = 0
    private var screenHeight = 0

    @Volatile
    private var isModifyingOverlays = false
    @Volatile
    private var lastOverlayModificationTime = 0L
    private val OVERLAY_MODIFICATION_GRACE_MS = 500L

    private var monitoredPackage: String? = null

    // Scroll state tracking
    @Volatile
    private var isScrolling = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SharedState.init(this)
        SharedState.isAccessibilityConnected = true
        keyStore = SecureKeyStore.getInstance(this)
        overlayManager = OverlayManager(this)
        OverlayManager.onManagerCreated(overlayManager!!)
        startPanicHandlerIfEnabled()

        // Decide the scan-cost tier once per connection (low-RAM devices get a
        // longer debounce and reduced depth/node caps to prevent lag).
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        deviceTier = DeviceTier.classify(am.isLowRamDevice, memInfo.totalMem)
        Log.d(TAG, "Device tier: $deviceTier (lowRam=${am.isLowRamDevice}, totalMem=${memInfo.totalMem})")

        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        startCacheCleanup()
        Log.d(TAG, "Service connected, screen: ${screenWidth}x${screenHeight}")
    }

    override fun onDestroy() {
        instance = null
        SharedState.isAccessibilityConnected = false
        panicHandler?.stopListening()
        panicHandler = null
        overlayManager?.removeAllOverlays()
        overlayManager?.let { OverlayManager.onManagerDestroyed(it) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Panic mode was previously never instantiated — start it when armed. */
    private fun startPanicHandlerIfEnabled() {
        val settings = SettingsStore.getInstance(this).loadSettings()
        updateSettingsSnapshot(settings)
        logPanicConfig(settings, "connect")
        if (armedPanicHandler(settings) == null) return
        Log.d(TAG, "Panic handler started (armed)")
    }

    /**
     * Returns the panic handler, starting it when the system is armed but not
     * yet running (e.g. settings changed while the service was already up).
     * Null while disarmed — external triggers no-op.
     */
    private fun armedPanicHandler(settings: AppSettings): PanicHandler? {
        if (!PanicConfig.isArmed(settings)) return null
        return panicHandler ?: PanicHandler(this).also {
            it.startListening(settings)
            panicHandler = it
        }
    }

    /**
     * Spacebar triple-tap trigger, called from the IME via [CryptoBarHook]'s
     * reflection bridge for every completed key tap. Consecutive space taps
     * within 0.7 s fire panic; any other key resets the burst, so fast typing
     * can never accumulate into an accidental trigger.
     */
    fun onKeyTapped(isSpacebar: Boolean) {
        if (!isSpacebar) {
            spacebarTapCounter.reset()
            return
        }
        val settings = currentSettings()
        if (!PanicConfig.isArmed(settings) || !settings.panicTriggerSpacebar) {
            spacebarTapCounter.reset()
            return
        }
        if (spacebarTapCounter.record(System.currentTimeMillis())) {
            Log.d(PANIC_TAG, "spacebar triple-tap fired")
            armedPanicHandler(settings)?.triggerPanic()
        }
    }

    /** Spacebar quick-swipe trigger; true when consumed as a panic gesture. */
    fun onSpacebarPanicGesture(): Boolean {
        val settings = currentSettings()
        val armed = PanicConfig.isArmed(settings)
        val triggerOn = settings.panicTriggerSpacebar
        Log.d(PANIC_TAG, "spacebar swipe-down (armed=$armed triggerOn=$triggerOn)")
        if (!armed || !triggerOn) return false
        armedPanicHandler(settings)?.triggerPanic()
        return true
    }

    /**
     * Called by the invite approval flow once a contact is saved — suppresses
     * bubbles for that exact raw message so the accepted invite never re-nags,
     * while scanning continues for new invites.
     */
    fun onInviteAccepted(rawMessage: String) {
        inviteScan.markInviteAccepted(rawMessage)
        // Clear any visible bubble for the accepted invite immediately — it
        // may still be on screen when acceptance came via the clipboard path.
        overlayManager?.removeInviteBubblesWhere { !inviteScan.shouldShowInvite(it) }
    }

    /** Re-evaluates panic triggers after a settings change (settings UI calls this). */
    fun refreshPanicTriggers() {
        panicHandler?.stopListening()
        panicHandler = null
        spacebarTapCounter.reset()
        val settings = SettingsStore.getInstance(this).loadSettings()
        updateSettingsSnapshot(settings)
        logPanicConfig(settings, "refresh")
        if (PanicConfig.isArmed(settings)) {
            panicHandler = PanicHandler(this).also { it.startListening(settings) }
        }
    }

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

        val scanActive = SharedState.isExchangeModeActive
        if (!scanActive) return

        if (isModifyingOverlays) return
        val timeSinceModification = System.currentTimeMillis() - lastOverlayModificationTime
        if (timeSinceModification < OVERLAY_MODIFICATION_GRACE_MS) return

        // Idle auto-off applies to Exchange Mode only.
        if (SharedState.isExchangeModeActive && SharedState.isIdle(10)) {
            SharedState.isExchangeModeActive = false
            safeRemoveAllOverlays()
            CryptoSession.forgetAll()
            return
        }

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (monitoredPackage != null && pkg != monitoredPackage) return
                // Don't process content changes while scrolling
                if (!isScrolling) {
                    scheduleUpdate(currentDebounceMs())
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Any button press in the monitored app: recalculate overlay
                // positions and drop stale ones (message may have been edited).
                // Debounced per DeviceTier, so scan cost stays bounded.
                if (monitoredPackage != null && pkg != monitoredPackage) return
                if (!isScrolling) {
                    scheduleUpdate(currentDebounceMs())
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                onScrollDetected()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Cleanup already ran in handleWindowStateChanged; schedule a re-scan
                // so returning to an app re-detects and re-shows its overlays.
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
        val pkg = event.packageName?.toString()
        val className = event.className?.toString()
        val decision = OverlayRefreshPolicy.classify(
            pkg = pkg,
            className = className,
            ownPackage = packageName,
            monitoredPackage = monitoredPackage
        )
        when (decision) {
            is OverlayRefreshPolicy.Decision.RemoveAll -> {
                // One of our own activities (settings / invite approval / app
                // lock) now covers the screen. Overlays are TYPE_ACCESSIBILITY_
                // OVERLAY and draw above activities, so clear them all.
                beginOverlayModification()
                overlayManager?.removeAllOverlays()
                endOverlayModification()
            }
            is OverlayRefreshPolicy.Decision.RemoveOthers -> {
                monitoredPackage = decision.pkg
                SharedState.currentAppPackage = decision.pkg
                beginOverlayModification()
                overlayManager?.removeOverlaysNotFrom(decision.pkg)
                endOverlayModification()
                CryptoSession.forgetAll()
            }
            is OverlayRefreshPolicy.Decision.Refresh -> {
                // Same app re-focused or IME window changed: reposition overlays
                // via a debounced rescan (respects the DeviceTier interval).
                scheduleUpdate(currentDebounceMs())
            }
            is OverlayRefreshPolicy.Decision.Ignore -> { /* shade / system / null */ }
        }
    }

    /**
     * Public entry point for "a known button was pressed" — kicks a debounced
     * rescan so overlay positions and messages are recalculated. Safe to call
     * from any thread; the scan itself runs on the handler.
     */
    fun requestOverlayRefresh() {
        if (!SharedState.isExchangeModeActive) return
        scheduleUpdate(currentDebounceMs())
    }

    override fun onInterrupt() {
        safeRemoveAllOverlays()
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Called IMMEDIATELY on every scroll event.
     * Hides all overlays instantly, then waits for scrolling to stop
     * before rescanning and showing them again.
     */
    private fun onScrollDetected() {
        // First scroll event — hide everything immediately
        if (!isScrolling) {
            isScrolling = true
            // Instant hide — no animation, no delay
            beginOverlayModification()
            overlayManager?.hideAllOverlays()
            endOverlayModification()
        }

        // Cancel any pending scroll-end callback
        pendingScrollEnd?.let { handler.removeCallbacks(it) }

        // Schedule scroll-end: if no more scroll events arrive within 300ms,
        // scrolling has stopped — rescan and show overlays
        pendingScrollEnd = Runnable {
            isScrolling = false
            onScrollEnded()
        }
        handler.postDelayed(pendingScrollEnd!!, SCROLL_END_DELAY_MS)
    }

    /**
     * Called when scrolling has stopped (no scroll events for 300ms).
     * Removes all stale overlays and does a fresh scan.
     */
    private fun onScrollEnded() {
        if (!SharedState.isExchangeModeActive) return

        // Clear all existing overlays — positions are all stale now
        beginOverlayModification()
        overlayManager?.removeAllOverlays()
        endOverlayModification()

        // Fresh scan after a tiny delay to let the modification grace period pass
        handler.postDelayed({
            processVisibleContent()
        }, OVERLAY_MODIFICATION_GRACE_MS + 100)
    }

    private fun scheduleUpdate(delayMs: Long) {
        pendingUpdate?.let { handler.removeCallbacks(it) }
        pendingUpdate = Runnable { processVisibleContent() }
        handler.postDelayed(pendingUpdate!!, delayMs)
    }

    private fun processVisibleContent() {
        if (!SharedState.isExchangeModeActive) return
        if (isModifyingOverlays) return
        if (isScrolling) return // Don't scan while scrolling

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return

        try {
            if (!ScreenScanner.quickCheck(rootNode, DeviceTier.maxQuickCheckDepth(deviceTier))) {
                // This screen has no hidden/encrypted content at all. Any
                // overlay still showing belongs to a message that is no longer
                // on screen (page switched, message deleted, screen opened), so
                // clear it immediately. This is the "locate the message, show
                // nothing if there's nothing" behavior.
                val anyActive = overlayManager?.activeOverlayIds()?.isNotEmpty() == true
                if (anyActive) {
                    beginOverlayModification()
                    overlayManager?.removeAllOverlays()
                    endOverlayModification()
                }
                return
            }

            SharedState.recordActivity()

            val encryptedNodes = ScreenScanner.findEncryptedNodes(
                rootNode,
                DeviceTier.maxScanDepth(deviceTier),
                DeviceTier.maxNodes(deviceTier)
            )
            val currentNodeIds = mutableSetOf<String>()

            for (node in encryptedNodes) {
                currentNodeIds.add(node.nodeId)
                processNode(node)
            }

            // Reconcile: drop overlays (decrypt AND invite) whose source node is
            // no longer on screen. Skipped when the scan hit its node cap, since
            // it may simply not have reached some nodes (would cause flicker).
            val scanTruncated = encryptedNodes.size >= DeviceTier.maxNodes(deviceTier)
            val activeIds = overlayManager?.activeOverlayIds() ?: emptySet()
            val staleIds = OverlayReconciler.staleOverlayIds(
                activeOverlayIds = activeIds,
                seenNodeIds = currentNodeIds,
                scanTruncated = scanTruncated
            )
            if (staleIds.isNotEmpty()) {
                beginOverlayModification()
                overlayManager?.removeOverlaysByIds(staleIds)
                overlayManager?.removeOverlaysOutsideBounds(screenHeight, screenWidth)
                endOverlayModification()
            } else {
                overlayManager?.removeOverlaysOutsideBounds(screenHeight, screenWidth)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing content", e)
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {}
        }
    }

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

        // Replays reach no decryption attempt (spec §2: rejected before
        // decryption); skips the trial-decrypt loop for known payloads.
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
                    decryptedText = "🔴",
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
            // In-band key invite: surface a tappable bubble while invite
            // scanning is active, unless the user already accepted the invite
            // carried by this exact message.
            is MessageProcessor.DecryptResult.KeyInvite -> {
                if (inviteScanActive() && inviteScan.shouldShowInvite(node.fullText)) {
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

    private fun beginOverlayModification() {
        isModifyingOverlays = true
        lastOverlayModificationTime = System.currentTimeMillis()
    }

    private fun endOverlayModification() {
        handler.postDelayed({
            isModifyingOverlays = false
            lastOverlayModificationTime = System.currentTimeMillis()
        }, 100)
    }

    private fun safeRemoveAllOverlays() {
        beginOverlayModification()
        overlayManager?.removeAllOverlays()
        handler.postDelayed({
            isModifyingOverlays = false
            lastOverlayModificationTime = System.currentTimeMillis()
        }, 300)
    }

    fun clearAllOverlays() {
        safeRemoveAllOverlays()
    }

    fun forceScan() {
        isScrolling = false
        processVisibleContent()
    }

    /**
     * Exchange Mode toggling works like a reboot in both directions: session
     * memory (decrypt cache + replay history) is forgotten first, then messages
     * are re-located live on enable or removed on disable — the previous
     * session's cache is never reused.
     */
    fun onExchangeModeChanged(enabled: Boolean) {
        CryptoSession.forgetAll()
        if (!enabled) {
            safeRemoveAllOverlays()
        } else {
            forceScan()
        }
    }

    /**
     * Key set changed (import / delete / save / invite accept / active-key
     * switch): reboot semantics — forget session memory, then re-locate
     * messages live so they decrypt with the current key set immediately.
     */
    fun onKeysChanged() {
        CryptoSession.forgetAll()
        if (SharedState.isExchangeModeActive) forceScan()
    }

    fun panicClear() {
        safeRemoveAllOverlays()
        CryptoSession.forgetAll()
        SharedState.isExchangeModeActive = false
    }

    private fun startCacheCleanup() {
        cacheCleanupRunnable = object : Runnable {
            override fun run() {
                DecryptCache.cleanExpired()
                handler.postDelayed(this, CACHE_CLEANUP_INTERVAL_MS)
            }
        }
        handler.postDelayed(cacheCleanupRunnable!!, CACHE_CLEANUP_INTERVAL_MS)
    }
}