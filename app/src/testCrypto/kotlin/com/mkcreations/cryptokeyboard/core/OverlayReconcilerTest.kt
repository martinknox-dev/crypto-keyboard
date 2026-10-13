package com.mkcreations.cryptokeyboard.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OverlayReconcilerTest : FunSpec({

    // --- basic staleness: overlay whose source node is gone ---

    test("keeps overlays whose source node is still on screen") {
        val stale = OverlayReconciler.staleOverlayIds(
            activeOverlayIds = setOf("abc_10_20", "def_30_40"),
            seenNodeIds = setOf("abc_10_20", "def_30_40", "ghi_50_60")
        )
        stale shouldBe emptySet()
    }

    test("marks overlay stale when its node disappeared (message edited/deleted)") {
        val stale = OverlayReconciler.staleOverlayIds(
            activeOverlayIds = setOf("abc_10_20", "def_30_40"),
            seenNodeIds = setOf("abc_10_20") // def's node is gone
        )
        stale shouldBe setOf("def_30_40")
    }

    // --- prefixed overlays: invite_ and corrupt_ share the base node id ---

    test("invite bubble keyed invite_<nodeId> is kept when base node is seen") {
        val stale = OverlayReconciler.staleOverlayIds(
            activeOverlayIds = setOf("invite_abc_10_20"),
            seenNodeIds = setOf("abc_10_20")
        )
        stale shouldBe emptySet()
    }

    test("corrupt indicator keyed corrupt_<nodeId> is kept when base node is seen") {
        val stale = OverlayReconciler.staleOverlayIds(
            activeOverlayIds = setOf("corrupt_abc_10_20"),
            seenNodeIds = setOf("abc_10_20")
        )
        stale shouldBe emptySet()
    }

    test("invite bubble is stale when its base node is gone") {
        val stale = OverlayReconciler.staleOverlayIds(
            activeOverlayIds = setOf("invite_abc_10_20", "ghi_50_60"),
            seenNodeIds = setOf("ghi_50_60")
        )
        stale shouldBe setOf("invite_abc_10_20")
    }

    // --- truncated scans must NOT reconcile (avoid LOW-tier flicker) ---

    test("truncated scan (hit node cap) skips reconciliation entirely") {
        val stale = OverlayReconciler.staleOverlayIds(
            activeOverlayIds = setOf("abc_10_20", "def_30_40"),
            seenNodeIds = setOf("abc_10_20"),
            scanTruncated = true
        )
        stale shouldBe emptySet()
    }

    test("empty active set yields empty stale set") {
        val stale = OverlayReconciler.staleOverlayIds(
            activeOverlayIds = emptySet(),
            seenNodeIds = setOf("abc_10_20")
        )
        stale shouldBe emptySet()
    }
})
