package com.mkcreations.cryptokeyboard.crypto

import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle

/** Turns an approved invite into a storable contact key. */
object InviteApproval {
    fun toCipherKey(envelope: InviteEnvelope, alias: String): CipherKey {
        val now = System.currentTimeMillis()
        return CipherKey(
            id = KeyGenerator.fingerprint(envelope.keyBytes),
            label = alias.trim().ifEmpty { "Contact ${KeyGenerator.shortPhrase(envelope.keyBytes)}" },
            keyBytes = envelope.keyBytes,
            createdAt = now,
            lastUsedAt = now,
            coverStyle = CoverStyle.RANDOM_CASUAL,
            compatibleChars = emptyList(),
        )
    }
}
