package app.melogold.android.models

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Where one side of [Lyrics] (plain or synced) came from. Shown under the lyrics, and later used
 * to tell the user's own work from what a provider returned when sharing to the server.
 */
enum class LyricsSource {
    YouTubeMusic,
    LrcLib,
    KuGou,

    /** Imported by the user from a TTML/LRC/text file. */
    File,

    /** Typed or synced by the user. */
    User,

    /** Shared by another user of the Melogold server (API §4.10); never sent back as the user's own. */
    Melogold
}

/** Lyrics the user made or brought themselves: they are kept on the Melogold server and follow the user. */
val LyricsSource?.isOwn get() = this == LyricsSource.User || this == LyricsSource.File

/**
 * @param fixedSource where [fixed] came from, null for rows cached before the database kept it
 * @param syncedSource where [synced] came from, null for rows cached before the database kept it
 * @param chosen the user chose these lyrics over the ones found automatically (in "Find other lyrics", or on another
 *   device: every version of their own the server sends): they are the user's own whatever the provider, so they go
 *   to the server with their real source (API §4.10, tasks/0009-chosen-lyrics-sync.md)
 */
@Immutable
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Lyrics(
    @PrimaryKey val songId: String,
    val fixed: String?,
    val synced: String?,
    val startTime: Long? = null,
    val fixedSource: LyricsSource? = null,
    val syncedSource: LyricsSource? = null,
    @ColumnInfo(defaultValue = "0")
    val chosen: Boolean = false
) {
    /** The user's own lyrics (typed, imported or chosen): kept on the Melogold server, they follow the user. */
    val isOwn: Boolean
        get() = fixedSource.isOwn || syncedSource.isOwn || chosen
}
