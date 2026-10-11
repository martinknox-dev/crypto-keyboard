package com.mkcreations.cryptokeyboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.mkcreations.cryptokeyboard.core.PanicConfig
import com.mkcreations.cryptokeyboard.core.PanicHandler
import com.mkcreations.cryptokeyboard.core.SharedState
import com.mkcreations.cryptokeyboard.core.WindowedCounter
import com.mkcreations.cryptokeyboard.crypto.DecryptCache
import com.mkcreations.cryptokeyboard.crypto.MessageProcessor
import com.mkcreations.cryptokeyboard.model.AppSettings
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore
import com.mkcreations.cryptokeyboard.storage.SettingsStore

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

    // Panic trigger counters (see onKeyEvent / onKeyTapped)
    private val volumeDownCounter = WindowedCounter(requiredCount = 3, windowMs = 1500L)
    private val spacebarTapCounter = WindowedCounter(requiredCount = 3, windowMs = 700L)

    // Hot paths (per-key-tap, volume presses) must not decrypt the whole
    // preference store per event — cached here, refreshed on connect and on
    // every refreshPanicTriggers() from the settings UI.
    @Volatile
    private var settingsSnapshot: AppSettings? = null

    private fun currentSettings(): AppSettings =
        settingsSnapshot ?: SettingsStore.getInstance(this).loadSettings().also {
            settingsSnapshot = it
        }

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
                "spacebar=${settings.panicTriggerSpacebar} " +
                "volume=${settings.panicTriggerVolumeSequence}] " +
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

    private var consecutiveEmptyScans = 0
    private val MAX_EMPTY_SCANS_BEFORE_SLEEP = 5

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

        // Panic trigger: observe hardware key events (volume sequence).
        // Events are never consumed — onKeyEvent always passes them through.
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }

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
        settingsSnapshot = settings
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
     * Panic trigger: triple-press of Volume Down within 1.5 s.
     * [super.onKeyEvent] passes the event through unchanged — this service
     * observes but never consumes hardware keys.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            val settings = currentSettings()
            val armed = PanicConfig.isArmed(settings)
            val triggerOn = settings.panicTriggerVolumeSequence
            val fired = armed && triggerOn && volumeDownCounter.record(System.currentTimeMillis())
            Log.d(
                PANIC_TAG,
                "volume-down seen (armed=$armed triggerOn=$triggerOn fired=$fired)"
            )
            if (fired) {
                armedPanicHandler(settings)?.triggerPanic()
            }
        }
        return super.onKeyEvent(event)
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

    /** Re-evaluates panic triggers after a settings change (settings UI calls this). */
    fun refreshPanicTriggers() {
        panicHandler?.stopListening()
        panicHandler = null
        volumeDownCounter.reset()
        spacebarTapCounter.reset()
        val settings = SettingsStore.getInstance(this).loadSettings()
        settingsSnapshot = settings
        logPanicConfig(settings, "refresh")
        if (PanicConfig.isArmed(settings)) {
            panicHandler = PanicHandler(this).also { it.startListening(settings) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!SharedState.isExchangeModeActive) return

        if (isModifyingOverlays) return
        val timeSinceModification = System.currentTimeMillis() - lastOverlayModificationTime
        if (timeSinceModification < OVERLAY_MODIFICATION_GRACE_MS) return

        if (SharedState.isIdle(10)) {
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
                // Don't process content changes while scrolling
                if (!isScrolling) {
                    scheduleUpdate(CONTENT_CHANGE_DEBOUNCE_MS)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                onScrollDetected()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != monitoredPackage) {
                    monitoredPackage = pkg
                    SharedState.currentAppPackage = pkg
                    safeRemoveAllOverlays()
                    DecryptCache.clearAll()
                    consecutiveEmptyScans = 0
                }
                scheduleUpdate(CONTENT_CHANGE_DEBOUNCE_MS)
            }
        }
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
            if (!ScreenScanner.quickCheck(rootNode)) {
                consecutiveEmptyScans++
                if (consecutiveEmptyScans > MAX_EMPTY_SCANS_BEFORE_SLEEP) {
                    safeRemoveAllOverlays()
                }
                return
            }

            consecutiveEmptyScans = 0
            SharedState.recordActivity()

            val encryptedNodes = ScreenScanner.findEncryptedNodes(rootNode)
            val currentNodeIds = mutableSetOf<String>()

            for (node in encryptedNodes) {
                currentNodeIds.add(node.nodeId)
                processNode(node)
            }

            // Remove overlays for nodes no longer present
            beginOverlayModification()
            val existingIds = overlayManager?.getActiveOverlayIds() ?: emptySet()
            for (id in existingIds) {
                if (id !in currentNodeIds && !id.startsWith("corrupt_")) {
                    overlayManager?.removeOverlay(id)
                }
            }
            overlayManager?.removeOverlaysOutsideBounds(screenHeight, screenWidth)
            endOverlayModification()

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

        if (overlayManager?.hasOverlay(node.nodeId) == true) {
            beginOverlayModification()
            overlayManager?.updateOverlayPosition(node.nodeId, node.bounds)
            endOverlayModification()
            return
        }

        // Cache first: re-displaying an already-decrypted message (e.g. on
        // scroll) must keep working; a cache hit is not a decryption.
        val cached = DecryptCache.get(node.fullText)
        if (cached != null) {
            beginOverlayModification()
            overlayManager?.showOverlay(
                overlayId = node.nodeId,
                decryptedText = cached.secretMessage,
                keyLabel = cached.keyLabel,
                bounds = node.bounds
            )
            endOverlayModification()
            return
        }

        // Replays reach no decryption attempt (spec §2: rejected before
        // decryption); skips the trial-decrypt loop for known payloads.
        if (MessageProcessor.isKnownReplay(node.fullText)) {
            return
        }

        when (val result = MessageProcessor.detectAndDecrypt(node.fullText, store.getAllKeys())) {
            is MessageProcessor.DecryptResult.Success -> {
                DecryptCache.put(node.fullText, result.secretMessage, result.keyLabel)
                beginOverlayModification()
                overlayManager?.showOverlay(
                    overlayId = node.nodeId,
                    decryptedText = result.secretMessage,
                    keyLabel = result.keyLabel,
                    bounds = node.bounds
                )
                endOverlayModification()
            }
            is MessageProcessor.DecryptResult.CorruptedData -> {
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
                    bounds = indicatorBounds
                )
                endOverlayModification()
            }
            is MessageProcessor.DecryptResult.NoMatchingKey -> {}
            is MessageProcessor.DecryptResult.DecryptionFailed -> {}
            is MessageProcessor.DecryptResult.NotEncrypted -> {}
            is MessageProcessor.DecryptResult.Replayed -> {}
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
        consecutiveEmptyScans = 0
        isScrolling = false
        processVisibleContent()
    }

    fun onExchangeModeChanged(enabled: Boolean) {
        if (!enabled) {
            safeRemoveAllOverlays()
            DecryptCache.clearAll()
            consecutiveEmptyScans = 0
        } else {
            forceScan()
        }
    }

    fun panicClear() {
        safeRemoveAllOverlays()
        DecryptCache.clearAll()
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