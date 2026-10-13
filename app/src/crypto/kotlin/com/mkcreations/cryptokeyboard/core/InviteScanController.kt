package com.mkcreations.cryptokeyboard.core

import com.mkcreations.cryptokeyboard.crypto.InviteEnvelope
import com.mkcreations.cryptokeyboard.crypto.MessageProcessor
import java.util.Base64

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
 * Accepted invites are suppressed per invite identity: the raw message is
 * normalized to its invite envelope (the raw 32-byte key), so accepting an
 * invite from the clipboard suppresses the same invite under any cover text
 * on screen — and no other invite. Messages carrying no extractable invite
 * fall back to exact raw-string matching.
 */
class InviteScanController {
    private val acceptedInvites = mutableSetOf<String>()

    /** Scan when Exchange Mode is on and the user's invite toggle is enabled. */
    fun shouldScan(exchangeModeActive: Boolean, scanInvitesEnabled: Boolean): Boolean =
        exchangeModeActive && scanInvitesEnabled

    /** Records that the user accepted the invite carried by [rawMessage]. */
    fun markInviteAccepted(rawMessage: String) {
        acceptedInvites.add(inviteIdentity(rawMessage))
    }

    /** False once the user has accepted the invite carried by [rawMessage]. */
    fun shouldShowInvite(rawMessage: String): Boolean =
        inviteIdentity(rawMessage) !in acceptedInvites

    /**
     * Normalizes a raw message to its suppression key: "invite:<base64 key>"
     * when an envelope extracts (identical across cover texts and the bare
     * Base64 form), otherwise the exact raw string.
     */
    private fun inviteIdentity(rawMessage: String): String {
        val envelope = MessageProcessor.extractInvite(rawMessage) ?: return rawMessage
        return "invite:" + Base64.getEncoder().encodeToString(envelope.keyBytes)
    }
}
