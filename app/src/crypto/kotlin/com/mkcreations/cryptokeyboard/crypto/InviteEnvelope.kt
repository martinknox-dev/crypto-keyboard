package com.mkcreations.cryptokeyboard.crypto

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Thrown when an invite envelope is malformed, truncated, or unsupported. */
class InviteFormatException(message: String) : Exception(message)

/**
 * In-band key invitation envelope — a versioned byte container that carries a
 * raw AES-256 pairing key and an optional sender note. It rides the existing
 * InvisibleEncoder cover channel like any other payload, but is recognized by
 * its magic bytes before any decryption is attempted.
 *
 * Wire format (big-endian):
 *   [0..1]   MAGIC 0x53 0x4B ("SK")
 *   [2]      VERSION 0x01
 *   [3]      TYPE 0x01 (key invite)
 *   [4..35]  raw 32-byte AES key
 *   [36..37] note length (unsigned short, BE)
 *   [38..]   note, UTF-8, capped at 128 bytes
 */
data class InviteEnvelope(
    val keyBytes: ByteArray,
    val note: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is InviteEnvelope && note == other.note && keyBytes.contentEquals(other.keyBytes))

    override fun hashCode(): Int = 31 * keyBytes.contentHashCode() + note.hashCode()

    companion object {
        val MAGIC = byteArrayOf(0x53, 0x4B)
        const val VERSION: Byte = 0x01
        const val TYPE_KEY_INVITE: Byte = 0x01
        const val KEY_SIZE = 32
        const val MAX_NOTE_BYTES = 128
        const val FIXED_SIZE = 4 + KEY_SIZE + 2 // magic+ver+type + key + noteLen

        fun serialize(keyBytes: ByteArray, note: String): ByteArray {
            require(keyBytes.size == KEY_SIZE) { "key must be $KEY_SIZE bytes" }
            val noteBytes = note.truncateUtf8(MAX_NOTE_BYTES)
            val out = ByteArray(FIXED_SIZE + noteBytes.size)
            var i = 0
            for (b in MAGIC) out[i++] = b
            out[i++] = VERSION
            out[i++] = TYPE_KEY_INVITE
            keyBytes.copyInto(out, i); i += KEY_SIZE
            out[i++] = ((noteBytes.size shr 8) and 0xFF).toByte()
            out[i++] = (noteBytes.size and 0xFF).toByte()
            noteBytes.copyInto(out, i)
            return out
        }

        fun deserialize(bytes: ByteArray): Result<InviteEnvelope> = runCatching {
            if (bytes.size < FIXED_SIZE) throw InviteFormatException("too short: ${bytes.size}")
            if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1]) throw InviteFormatException("bad magic")
            if (bytes[2] != VERSION) throw InviteFormatException("unsupported version ${bytes[2]}")
            if (bytes[3] != TYPE_KEY_INVITE) throw InviteFormatException("unsupported type ${bytes[3]}")
            val keyBytes = bytes.copyOfRange(4, 4 + KEY_SIZE)
            val noteLen = ((bytes[36].toInt() and 0xFF) shl 8) or (bytes[37].toInt() and 0xFF)
            if (noteLen > MAX_NOTE_BYTES) throw InviteFormatException("note too long: $noteLen")
            if (bytes.size != FIXED_SIZE + noteLen) {
                throw InviteFormatException("length mismatch: have ${bytes.size}, want ${FIXED_SIZE + noteLen}")
            }
            val note = strictUtf8(bytes, FIXED_SIZE, noteLen)
            InviteEnvelope(keyBytes, note)
        }

        /** Strict UTF-8 decode — malformed bytes become a failure, not U+FFFD. */
        private fun strictUtf8(bytes: ByteArray, offset: Int, length: Int): String =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()

        /** UTF-8 bytes of the note, truncated to [maxBytes] on a char boundary. */
        private fun String.truncateUtf8(maxBytes: Int): ByteArray {
            val bytes = toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            var end = maxBytes
            // Walk back off any continuation byte (10xxxxxx) so we never split a codepoint.
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return bytes.copyOf(end)
        }
    }
}
