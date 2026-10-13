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
