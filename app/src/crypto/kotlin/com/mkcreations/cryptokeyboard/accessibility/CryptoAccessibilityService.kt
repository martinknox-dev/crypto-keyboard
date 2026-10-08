package com.mkcreations.cryptokeyboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.mkcreations.cryptokeyboard.core.SharedState
import com.mkcreations.cryptokeyboard.crypto.DecryptCache
import com.mkcreations.cryptokeyboard.crypto.MessageProcessor
import com.mkcreations.cryptokeyboard.crypto.ReplayGuard
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore

class CryptoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CryptoA11y"
        var instance: CryptoAccessibilityService? = null
            private set
    }

    private var overlayManager: OverlayManager? = null
    private var keyStore: SecureKeyStore? = null

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
        overlayManager?.removeAllOverlays()
        handler.removeCallbacksAndMessages(null)
        DecryptCache.clearAll()
        ReplayGuard.clear()
        super.onDestroy()
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

        when (val result = MessageProcessor.detectAndDecrypt(node.fullText, store)) {
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
            is MessageProcessor.DecryptResult.Replayed -> {
                // Decryption succeeded but this exact ciphertext was already
                // seen in a different message — flag it as a possible replay.
                DecryptCache.put(node.fullText, result.secretMessage, result.keyLabel)
                beginOverlayModification()
                overlayManager?.showOverlay(
                    overlayId = node.nodeId,
                    decryptedText = "⚠️ Possible replay — this exact message was already seen.\n\n" + result.secretMessage,
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
        ReplayGuard.clear()
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