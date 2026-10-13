package com.mkcreations.cryptokeyboard.core

/**
 * Pure stale-overlay detection. On a completed screen scan the caller knows
 * which encrypted nodes are actually visible ([seenNodeIds]) and which overlay
 * ids are currently attached ([activeOverlayIds]). An overlay whose source node
 * is no longer on screen — because the message was edited (different text hash,
 * hence different nodeId) or deleted — is stale and must be removed, otherwise
 * it lingers forever at a position that no longer corresponds to anything.
 *
 * Overlay ids are namespaced: decrypt overlays use the raw nodeId, invite
 * bubbles use "invite_<nodeId>", and the corrupted-data indicator uses
 * "corrupt_<nodeId>". Staleness is decided on the BASE node id.
 *
 * Reconciliation is skipped when the scan was [scanTruncated] (the scanner hit
 * its per-tier node cap and may simply not have reached some nodes): removing
 * overlays that are still valid would flicker on low-end devices.
 */
object OverlayReconciler {

    private val PREFIXES = listOf("invite_", "corrupt_")

    /**
     * @param activeOverlayIds overlay ids currently attached to the window manager.
     * @param seenNodeIds base node ids found in the latest completed scan.
     * @param scanTruncated true when the scan hit its node cap and is not authoritative.
     * @return the subset of [activeOverlayIds] whose base node is absent from [seenNodeIds].
     */
    fun staleOverlayIds(
        activeOverlayIds: Set<String>,
        seenNodeIds: Set<String>,
        scanTruncated: Boolean = false
    ): Set<String> {
        if (scanTruncated) return emptySet()
        if (activeOverlayIds.isEmpty()) return emptySet()

        return activeOverlayIds.filter { id ->
            baseNodeId(id) !in seenNodeIds
        }.toSet()
    }

    /** Strips a known namespace prefix, leaving the raw generated node id. */
    private fun baseNodeId(overlayId: String): String {
        for (prefix in PREFIXES) {
            if (overlayId.startsWith(prefix)) return overlayId.removePrefix(prefix)
        }
        return overlayId
    }
}
