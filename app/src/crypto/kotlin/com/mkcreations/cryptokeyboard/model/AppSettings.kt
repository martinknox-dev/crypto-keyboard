package com.mkcreations.cryptokeyboard.model

data class AppSettings(
    // Security
    val appLockEnabled: Boolean = false,
    val lockMethod: LockMethod = LockMethod.NONE,
    val lockTimeoutSeconds: Int = 0,         // 0 = immediate
    val pinHash: String = "",                // Stored as hash, never plain

    // Disguise
    val disguiseEnabled: Boolean = false,
    val disguiseIcon: DisguiseIcon = DisguiseIcon.DEFAULT,
    val disguiseAppName: String = "Cipher",
    val fakeCalculatorEnabled: Boolean = false,

    // Messaging
    val defaultCoverStyle: CoverStyle = CoverStyle.RANDOM_CASUAL,
    val customCoverMessage: String = "",
    val autoEnableExchange: Boolean = false,
    val smartKeySelection: Boolean = true,

    // Panic
    val panicEnabled: Boolean = false,
    val panicTrigger: PanicTrigger = PanicTrigger.SHAKE,
    val panicSwitchKeyboard: Boolean = true,
    val panicDisableExchange: Boolean = true,
    val panicRemoveOverlays: Boolean = true,
    val panicClearCache: Boolean = true,
    val panicLockApp: Boolean = false,
    val panicClearKeys: Boolean = false,

    // Battery
    val idleTimeoutMinutes: Int = 3,
    val maxOverlays: Int = 15,
    val cacheDurationMinutes: Int = 5,
    val animationLevel: AnimationLevel = AnimationLevel.FULL
)

enum class LockMethod {
    NONE, PIN, BIOMETRIC, BOTH
}

enum class DisguiseIcon {
    DEFAULT, CALCULATOR, NOTES, SYSTEM_UTILITY, FILE_MANAGER
}

enum class PanicTrigger {
    SHAKE, SCREEN_DOWN, POWER_BUTTON, VOLUME_UP
}

enum class AnimationLevel {
    FULL, REDUCED, NONE
}