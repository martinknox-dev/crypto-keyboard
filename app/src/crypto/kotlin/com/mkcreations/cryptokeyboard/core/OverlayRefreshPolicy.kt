package com.mkcreations.cryptokeyboard.core

/**
 * Pure decision logic for how the accessibility service should react to a
 * foreground window change. Exists so the "which app is in front and what does
 * that mean for overlay lifecycle" rules are unit-testable without a device.
 *
 * The overlays are TYPE_ACCESSIBILITY_OVERLAY, which draw ABOVE normal activity
 * windows. So when one of our OWN activities (settings, invite approval, app
 * lock) comes to the foreground, any decrypt/invite overlay from the chat app
 * below would otherwise float on top of it. That case must clear everything.
 *
 * Transient system surfaces (notification shade, status bar) are [Decision.Ignore]
 * so overlays never flicker when the user merely pulls the shade down.
 */
object OverlayRefreshPolicy {

    sealed class Decision {
        /** Remove every overlay — an own activity now covers the screen. */
        object RemoveAll : Decision()

        /** Remove overlays not produced by [pkg] — a real app switch occurred. */
        data class RemoveOthers(val pkg: String) : Decision()

        /** Same app kept foreground — keep overlays but schedule a reposition rescan. */
        object Refresh : Decision()

        /** Transient system surface — do nothing to avoid flicker. */
        object Ignore : Decision()
    }

    private const val SYSTEM_UI = "com.android.systemui"
    private const val ANDROID = "android"

    /**
     * @param pkg the window's package name, or null when unknown.
     * @param className the window's class name (an Activity's FQCN, an IME
     *   service class, or null). Only consulted when [pkg] is our own package.
     * @param ownPackage our own applicationId.
     * @param monitoredPackage the third-party app currently tracked for overlays.
     */
    fun classify(
        pkg: String?,
        className: String?,
        ownPackage: String,
        monitoredPackage: String?
    ): Decision {
        if (pkg == null) return Decision.Ignore
        if (pkg == SYSTEM_UI || pkg == ANDROID) return Decision.Ignore

        if (pkg == ownPackage) {
            // Our own package hosts both activities (settings/approval/lock) and
            // the IME. Only activities fully cover the chat below; the IME and
            // other windows should keep the overlays and just reposition them.
            return if (className != null && className.endsWith("Activity")) {
                Decision.RemoveAll
            } else {
                // IME (keyboard show/hide) or an unknown own window: reposition.
                Decision.Refresh
            }
        }

        // A real third-party app is in front.
        return if (pkg != monitoredPackage) {
            Decision.RemoveOthers(pkg)
        } else {
            // Same app re-focused (e.g. returning from recents) — reposition.
            Decision.Refresh
        }
    }
}
