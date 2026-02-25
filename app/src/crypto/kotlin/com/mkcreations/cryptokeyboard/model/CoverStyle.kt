package com.mkcreations.cryptokeyboard.model

enum class CoverStyle(val displayName: String, val description: String) {
    RANDOM_CASUAL(
        "Random Casual",
        "Auto-generates casual responses like 'ok', 'yeah', 'hmm'"
    ),
    RANDOM_EMOJI(
        "Random Emoji",
        "Uses random common emojis like 👍 😊 🙂"
    ),
    CUSTOM_FIXED(
        "Custom Fixed",
        "Always uses your custom message"
    ),
    CONTEXTUAL(
        "Contextual",
        "Changes based on time of day"
    ),
    ALWAYS_ASK(
        "Always Ask",
        "Always shows the cover message field"
    ),
    NO_COVER(
        "No Cover",
        "Sends encrypted data as visible text"
    )
}