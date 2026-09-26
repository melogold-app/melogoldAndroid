package app.melogold.android.models

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Longest title, artist and album of a track (API §11, UTF-16 units). */
const val TRACK_TEXT_MAX = 500

/**
 * The user's own title, artist and album of a track over what YouTube calls it (tasks/0012): a lost album collected
 * from fan uploads looks like one album on every device. A field without an override is `null` (YouTube's shows); a
 * track without any override has no row.
 */
@Immutable
@Entity
data class TrackOverride(
    @PrimaryKey val videoId: String,
    val title: String? = null,
    val artistsText: String? = null,
    val albumTitle: String? = null,
    val updatedAt: Long
) {
    val isEmpty get() = title == null && artistsText == null && albumTitle == null

    companion object {
        /** A field as the server keeps it (API §4.8): trimmed, at most 500 UTF-16 units, blank → no override. */
        fun clean(value: String?): String? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (text.length <= TRACK_TEXT_MAX) return text
            val end = if (Character.isHighSurrogate(text[TRACK_TEXT_MAX - 1])) TRACK_TEXT_MAX - 1 else TRACK_TEXT_MAX
            return text.substring(0, end).trimEnd()
        }
    }
}

/**
 * Lyrics found automatically that the user kept (tasks/0013): a reference to them at their provider, the same on
 * every device of the account. The server keeps the reference, not the text.
 *
 * @param source `youtube_music`, `lrclib` or `kugou` (the server's words)
 * @param ref the id of the lyrics at the provider: the LrcLib record, the YouTube Music browse id (`MPLYt…`), or
 *   `<id>:<accesskey>` of KuGou
 * @param startTimeMs the "later" shift of the synced lyrics
 */
@Immutable
@Entity
data class LyricsPin(
    @PrimaryKey val videoId: String,
    val source: String,
    val ref: String,
    val startTimeMs: Long? = null,
    val updatedAt: Long
)
