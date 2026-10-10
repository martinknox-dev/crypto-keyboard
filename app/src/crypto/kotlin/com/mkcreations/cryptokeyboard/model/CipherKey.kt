package com.mkcreations.cryptokeyboard.model

import org.json.JSONObject

data class CipherKey(
    val id: String,                    // Unique fingerprint hash (8 hex chars)
    val label: String,                 // User-friendly name ("Alice", "Work")
    val keyBytes: ByteArray,           // Raw 256-bit key
    val createdAt: Long,               // Timestamp of creation
    val lastUsedAt: Long,              // Timestamp of last use
    val coverStyle: CoverStyle,        // Default cover message style
    val compatibleChars: List<Int>     // Unicode code points that work with their messenger
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CipherKey) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("label", label)
            put("keyBytes", java.util.Base64.getEncoder().encodeToString(keyBytes))
            put("createdAt", createdAt)
            put("lastUsedAt", lastUsedAt)
            put("coverStyle", coverStyle.name)
            put("compatibleChars", org.json.JSONArray(compatibleChars))
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CipherKey {
            val charsArray = json.getJSONArray("compatibleChars")
            val chars = mutableListOf<Int>()
            for (i in 0 until charsArray.length()) {
                chars.add(charsArray.getInt(i))
            }
            return CipherKey(
                id = json.getString("id"),
                label = json.getString("label"),
                keyBytes = java.util.Base64.getDecoder().decode(json.getString("keyBytes")),
                createdAt = json.getLong("createdAt"),
                lastUsedAt = json.getLong("lastUsedAt"),
                coverStyle = CoverStyle.valueOf(json.optString("coverStyle", CoverStyle.RANDOM_CASUAL.name)),
                compatibleChars = chars
            )
        }
    }
}