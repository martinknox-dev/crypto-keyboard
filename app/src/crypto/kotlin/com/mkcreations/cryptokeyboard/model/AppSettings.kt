package com.mkcreations.cryptokeyboard.model

data class AppSettings(
    // Security
    val appLockEnabled: Boolean = false,
    val lockMethod: LockMethod = LockMethod.NONE,
    val lockTimeoutSeconds: Int = 300,     // seconds; SettingsStore defaults to 300 (<= 0 would disable relock)
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

    // Panic — master switch (off = whole panic system disarmed)
    val panicEnabled: Boolean = false,
    // Panic — triggers (independent; at least one must be on when master is on)
    val panicTriggerShake: Boolean = true,
    val panicTriggerFaceDown: Boolean = false,
    val panicTriggerSpacebar: Boolean = false,
    val panicTriggerVolumeSequence: Boolean = false,
    // Panic — actions
    val panicActionSwitchIme: Boolean = true,
    val panicActionRemoveOverlays: Boolean = true,   // also disables exchange mode
    val panicActionClearCache: Boolean = true,       // also relocks the app
    val panicActionWipeKeys: Boolean = false,

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

enum class AnimationLevel {
    FULL, REDUCED, NONE
}
