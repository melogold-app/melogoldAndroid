package app.melogold.android.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One value of the sync engine (DESIGN §3.13.1): the binding, the cursor, the last sync. */
@Entity
data class SyncState(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * What the server had of the likes after the last sync: the next sync sends what the Favorites changed since (a track
 * liked is here, one unliked is gone).
 */
@Entity
data class SyncedLike(@PrimaryKey val videoId: String)

/** What the server had of a playlist after the last sync: its name, cover and tracks in order. */
@Entity
data class SyncedPlaylist(
    @PrimaryKey val syncId: String,
    val name: String,
    val thumbnailUrl: String?,
    /** The video ids in order, one per line. */
    val videoIds: String
) {
    val items: List<String> get() = if (videoIds.isEmpty()) emptyList() else videoIds.split('\n')
}

/**
 * The user's own lyrics of a track as the server had them after the last sync (API §4.10): the next sync sends what
 * changed here since, and a track played for the first time on this device finds them here. Sources and the format
 * are the server's words (`user`, `lrc`, …).
 */
@Entity
data class SyncedLyrics(
    @PrimaryKey val videoId: String,
    val rev: Long,
    /** A hash of the lyrics as the server has them ([app.melogold.android.sync.LyricsSync]), to tell a local change. */
    val hash: String,
    val plain: String?,
    val plainSource: String?,
    val synced: String?,
    val syncedFormat: String?,
    val syncedSource: String?,
    val startTimeMs: Long?
)

/**
 * "Forget this track" or, with [ALL], "clear the history", done here and not sent to the server yet (API §4.8
 * `history.forget`, `history.clear`): the plays before [eventsBefore] go on every device.
 */
@Entity
data class HistoryForget(
    @PrimaryKey val videoId: String,
    val eventsBefore: Long,
    val resetTotal: Boolean
) {
    companion object {
        const val ALL = "*"
    }
}

/** A saved album or artist the server had after the last sync (`type` is `album` or `artist`). */
@Entity(primaryKeys = ["type", "browseId"])
data class SyncedBookmark(
    val type: String,
    val browseId: String
)
