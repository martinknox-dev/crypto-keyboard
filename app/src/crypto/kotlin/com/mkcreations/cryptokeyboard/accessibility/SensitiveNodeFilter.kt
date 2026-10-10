package com.mkcreations.cryptokeyboard.accessibility

/**
 * The accessibility scanner must never read password/PIN fields.
 * node.isPassword is the primary signal; hints cover EditTexts that
 * fail to flag isPassword on some OEM keyboards/apps.
 */
object SensitiveNodeFilter {
    private val SENSITIVE_HINTS = listOf("password", "passcode", "passwd", "pin")

    fun shouldSkip(isPassword: Boolean, className: String?, hint: String?): Boolean {
        if (isPassword) return true
        val h = hint?.lowercase()?.trim() ?: return false
        if (h.isEmpty()) return false
        // Whole-word matches only: "enter passcode" yes, "pinstripe" no
        return SENSITIVE_HINTS.any {
            h == it || h.startsWith("$it ") || h.contains(" $it") || h.contains("$it:")
        }
    }
}
