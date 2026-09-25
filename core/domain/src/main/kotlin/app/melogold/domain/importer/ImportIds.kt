package app.melogold.domain.importer

import java.security.MessageDigest
import java.util.UUID

/**
 * Ids of what a backup brings (REWRITE §4.5.3). A play of a ViTune or ViMusic backup gets the same id on every
 * device that imports that backup: UUIDv5 of `"$videoId|$timestampMs|$playTimeMs"` in [NAMESPACE]. With random ids,
 * a phone and a tablet importing the same backup would each send their copy of every play, and the history on the
 * server and on both devices would double; with these the server keeps one. Vectors: `docs/spec/import-ids.vectors.json`.
 */
object ImportIds {
    /** `NS_MELOGOLD_IMPORT` (REWRITE §4.5.3). */
    val NAMESPACE: UUID = UUID.fromString("4a3b8c8a-1d9c-48f2-938b-3077d54ab4fb")

    /** The id of a play; [playTimeMs] as it is kept (already clamped to 1..86 400 000). */
    fun eventId(videoId: String, timestampMs: Long, playTimeMs: Long): String =
        uuid5(NAMESPACE, "$videoId|$timestampMs|$playTimeMs").toString()

    /** RFC 9562 version 5: SHA-1 of the namespace and the name. */
    fun uuid5(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1").run {
            update(namespace.toBytes())
            update(name.toByteArray(Charsets.UTF_8))
            digest()
        }
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val most = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (digest[i].toLong() and 0xff) }
        val least = (8 until 16).fold(0L) { acc, i -> (acc shl 8) or (digest[i].toLong() and 0xff) }
        return UUID(most, least)
    }

    private fun UUID.toBytes() = ByteArray(16).also { bytes ->
        for (i in 0 until 8) bytes[i] = (mostSignificantBits ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) bytes[8 + i] = (leastSignificantBits ushr (56 - 8 * i)).toByte()
    }
}
