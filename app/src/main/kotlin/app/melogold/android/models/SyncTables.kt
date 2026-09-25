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

/** A saved album or artist the server had after the last sync (`type` is `album` or `artist`). */
@Entity(primaryKeys = ["type", "browseId"])
data class SyncedBookmark(
    val type: String,
    val browseId: String
)
