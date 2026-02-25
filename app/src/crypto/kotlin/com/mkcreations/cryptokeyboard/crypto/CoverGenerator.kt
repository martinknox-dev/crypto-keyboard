package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CoverStyle
import java.util.Calendar
import kotlin.random.Random

object CoverGenerator {

    private val casualResponses = listOf(
        "ok", "ok 👍", "yeah", "yeah sure", "hmm",
        "alright", "cool", "got it", "sure", "yep",
        "fine", "okay", "kk", "right", "yup",
        "haha", "lol", "nice", "true", "same",
        "oh ok", "I see", "makes sense", "good",
        "sounds good", "no worries", "np", "ah ok",
        "haha yeah", "oh nice", "for sure", "bet"
    )

    private val emojiResponses = listOf(
        "👍", "😊", "🙂", "👌", "😂",
        "🤔", "😄", "🙌", "✌️", "😎",
        "💯", "🔥", "✅", "👏", "🤝",
        "😁", "🤷", "👀", "💪", "🎉"
    )

    private val morningMessages = listOf(
        "good morning", "morning 👋", "good morning 😊",
        "hey good morning", "morning!", "gm",
        "good morning ☀️", "hey morning"
    )

    private val afternoonMessages = listOf(
        "ok sure", "yeah", "sounds good",
        "alright 👍", "cool", "got it",
        "sure thing", "ok will do"
    )

    private val eveningMessages = listOf(
        "ok", "nice", "yeah sure",
        "alright", "cool 👍", "sounds good",
        "ok got it", "fine by me"
    )

    private val nightMessages = listOf(
        "good night", "gn", "night 😴",
        "good night 👋", "sleep well", "gn 🌙",
        "night!", "ok good night"
    )

    /**
     * Generates a cover message based on the selected style
     */
    fun generate(style: CoverStyle, customMessage: String = ""): String {
        return when (style) {
            CoverStyle.RANDOM_CASUAL -> casualResponses.random()
            CoverStyle.RANDOM_EMOJI -> emojiResponses.random()
            CoverStyle.CUSTOM_FIXED -> {
                if (customMessage.isNotEmpty()) customMessage
                else casualResponses.random()
            }
            CoverStyle.CONTEXTUAL -> generateContextual()
            CoverStyle.ALWAYS_ASK -> "" // Empty — keyboard will show cover field
            CoverStyle.NO_COVER -> ""   // Empty — no cover message
        }
    }

    /**
     * Generates a context-aware cover message based on time of day
     */
    private fun generateContextual(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        return when (hour) {
            in 6..10 -> morningMessages.random()
            in 11..14 -> afternoonMessages.random()
            in 15..18 -> eveningMessages.random()
            in 19..22 -> eveningMessages.random()
            else -> nightMessages.random() // 23-5
        }
    }

    /**
     * Generates a batch of suggestions for the user to pick from
     */
    fun getSuggestions(count: Int = 5): List<String> {
        val suggestions = mutableListOf<String>()
        val used = mutableSetOf<String>()

        while (suggestions.size < count) {
            val msg = casualResponses.random()
            if (msg !in used) {
                suggestions.add(msg)
                used.add(msg)
            }
        }

        return suggestions
    }
}