package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CoverStyle
import java.security.SecureRandom
import java.util.Calendar

object CoverGenerator {

    // Covers are not secret, but they must not be *predictable*: using a
    // non-cryptographic RNG would let an observer who knows the app predict
    // the next cover. SecureRandom also avoids the tiny modulo bias of
    // kotlin.random.Random.
    private val secureRandom = SecureRandom()

    private fun <T> List<T>.securePick(): T = get(secureRandom.nextInt(size))

    // Larger, more varied pool: a small fixed set of covers is itself a
    // statistical fingerprint ("this contact always sends 'ok 👍'").
    private val casualResponses = listOf(
        "ok", "ok 👍", "yeah", "yeah sure", "hmm",
        "alright", "cool", "got it", "sure", "yep",
        "fine", "okay", "kk", "right", "yup",
        "haha", "lol", "nice", "true", "same",
        "oh ok", "I see", "makes sense", "good",
        "sounds good", "no worries", "np", "ah ok",
        "haha yeah", "oh nice", "for sure", "bet",
        "ok then", "alrighty", "roger that", "copy that",
        "you got it", "will do", "on it", "done",
        "👍", "👌", "😄", "😂", "🙂",
        "hmm ok", "fair enough", "whatever works", "as you say",
        "no problem", "anytime", "gladly", "count me in",
        "maybe", "not sure yet", "let me think", "give me a sec",
        "brb", "afk for a bit", "busy atm", "later",
        "see ya", "talk soon", "catch you later", "nite",
        "gm", "gn", "morning", "evening",
        "hbu", "wyd", "nm", "all good",
        "same here", "agreed", "exactly", "precisely",
        "makes sense to me", "I can do that", "should be fine", "let's do it"
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
        "good morning ☀️", "hey morning",
        "rise and shine", "have a good one today",
        "good morning everyone", "morning ☀️"
    )

    private val afternoonMessages = listOf(
        "ok sure", "yeah", "sounds good",
        "alright 👍", "cool", "got it",
        "sure thing", "ok will do",
        "lunch later?", "sounds like a plan",
        "on my way", "be there soon",
        "afternoon", "good afternoon"
    )

    private val eveningMessages = listOf(
        "ok", "nice", "yeah sure",
        "alright", "cool 👍", "sounds good",
        "ok got it", "fine by me",
        "good evening", "hey, evening",
        "wrap up soon", "almost done here",
        "see you tomorrow", "talk tomorrow"
    )

    private val nightMessages = listOf(
        "good night", "gn", "night 😴",
        "good night 👋", "sleep well", "gn 🌙",
        "night!", "ok good night",
        "rest well", "see you in the morning",
        "good night everyone", "lights out"
    )

    /**
     * Generates a cover message based on the selected style
     */
    fun generate(style: CoverStyle, customMessage: String = ""): String {
        return when (style) {
            CoverStyle.RANDOM_CASUAL -> casualResponses.securePick()
            CoverStyle.RANDOM_EMOJI -> emojiResponses.securePick()
            CoverStyle.CUSTOM_FIXED -> {
                if (customMessage.isNotEmpty()) customMessage
                else casualResponses.securePick()
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
            in 6..10 -> morningMessages.securePick()
            in 11..14 -> afternoonMessages.securePick()
            in 15..18 -> eveningMessages.securePick()
            in 19..22 -> eveningMessages.securePick()
            else -> nightMessages.securePick() // 23-5
        }
    }

    /**
     * Generates a batch of suggestions for the user to pick from
     */
    fun getSuggestions(count: Int = 5): List<String> {
        val suggestions = mutableListOf<String>()
        val used = mutableSetOf<String>()

        while (suggestions.size < count && used.size < casualResponses.size) {
            val msg = casualResponses.securePick()
            if (msg !in used) {
                suggestions.add(msg)
                used.add(msg)
            }
        }

        return suggestions
    }
}
