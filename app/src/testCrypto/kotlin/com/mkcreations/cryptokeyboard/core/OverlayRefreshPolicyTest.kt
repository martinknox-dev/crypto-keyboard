package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class OverlayRefreshPolicyTest : FunSpec({

    val own = "com.example.keyboard"

    // --- own package: activities clear, IME repositions ---

    test("own activity (className ends in Activity) removes all overlays") {
        val d = OverlayRefreshPolicy.classify(
            pkg = own,
            className = "$own.ui.KeyInviteApprovalActivity",
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.RemoveAll>()
    }

    test("own settings activity removes all overlays") {
        val d = OverlayRefreshPolicy.classify(
            pkg = own,
            className = "dev.patrickgold.florisboard.app.FlorisAppActivity",
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.RemoveAll>()
    }

    test("own IME window (non-Activity className) refreshes, does not remove") {
        val d = OverlayRefreshPolicy.classify(
            pkg = own,
            className = "dev.patrickgold.florisboard.FlorisImeService",
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.Refresh>()
    }

    test("own window with null className refreshes (safe default for IME)") {
        val d = OverlayRefreshPolicy.classify(
            pkg = own,
            className = null,
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.Refresh>()
    }

    // --- system surfaces: ignored so the shade never flickers ---

    test("systemui (notification shade) is ignored") {
        val d = OverlayRefreshPolicy.classify(
            pkg = "com.android.systemui",
            className = "com.android.systemui.statusbar.phone.StatusBar",
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.Ignore>()
    }

    test("android framework package is ignored") {
        val d = OverlayRefreshPolicy.classify(
            pkg = "android",
            className = "com.android.internal.app.ResolverActivity",
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.Ignore>()
    }

    test("null package is ignored") {
        val d = OverlayRefreshPolicy.classify(
            pkg = null,
            className = null,
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.Ignore>()
    }

    // --- third-party apps ---

    test("switching to a different app removes overlays not from it") {
        val d = OverlayRefreshPolicy.classify(
            pkg = "com.whatsapp",
            className = "com.whatsapp.Main",
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.RemoveOthers>().pkg shouldBe "com.whatsapp"
    }

    test("same monitored app re-focus refreshes (keeps overlays, repositions)") {
        val d = OverlayRefreshPolicy.classify(
            pkg = "org.telegram.messenger",
            className = "org.telegram.messenger.DefaultAppActivity",
            ownPackage = own,
            monitoredPackage = "org.telegram.messenger"
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.Refresh>()
    }

    test("first-seen app (monitoredPackage null) removes overlays not from it") {
        val d = OverlayRefreshPolicy.classify(
            pkg = "org.telegram.messenger",
            className = "org.telegram.messenger.DefaultAppActivity",
            ownPackage = own,
            monitoredPackage = null
        )
        d.shouldBeInstanceOf<OverlayRefreshPolicy.Decision.RemoveOthers>().pkg shouldBe "org.telegram.messenger"
    }
})
