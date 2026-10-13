package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.mkcreations.cryptokeyboard.crypto.InviteEnvelope
import com.mkcreations.cryptokeyboard.crypto.InvisibleEncoder

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

    // --- envelope identity: the same invite under different cover texts ---

    test("accepting via one cover text suppresses the same invite in a different cover") {
        val envelopeBytes = InviteEnvelope.serialize(ByteArray(32) { it.toByte() }, "hey")
        val onScreen = InvisibleEncoder.embedInCover(envelopeBytes, "Lunch at noon?")
        val clipboard = InvisibleEncoder.embedInCover(envelopeBytes, "Running late")
        val c = InviteScanController()
        c.markInviteAccepted(clipboard)
        c.shouldShowInvite(onScreen) shouldBe false
    }

    test("bare Base64 form of the same invite is suppressed too") {
        val envelopeBytes = InviteEnvelope.serialize(ByteArray(32) { (it + 1).toByte() }, "hi")
        val bare = java.util.Base64.getEncoder().encodeToString(envelopeBytes)
        val covered = InvisibleEncoder.embedInCover(envelopeBytes, "different cover")
        val c = InviteScanController()
        c.markInviteAccepted(bare)
        c.shouldShowInvite(covered) shouldBe false
    }

    test("a different invite still shows after accepting a look-alike") {
        val accepted = InvisibleEncoder.embedInCover(
            InviteEnvelope.serialize(ByteArray(32) { it.toByte() }, "same note"),
            "cover A"
        )
        val other = InvisibleEncoder.embedInCover(
            InviteEnvelope.serialize(ByteArray(32) { (it + 1).toByte() }, "same note"),
            "cover A"
        )
        val c = InviteScanController()
        c.markInviteAccepted(accepted)
        c.shouldShowInvite(other) shouldBe true
    }

    test("message without an extractable invite falls back to raw-message identity") {
        val c = InviteScanController()
        c.markInviteAccepted("plain text no invite")
        c.shouldShowInvite("plain text no invite") shouldBe false
        c.shouldShowInvite("other plain text") shouldBe true
    }
})
