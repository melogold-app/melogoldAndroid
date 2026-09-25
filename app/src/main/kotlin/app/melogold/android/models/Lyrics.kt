package app.melogold.android.models

import androidx.compose.runtime.Immutable
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
    User
}

/**
 * @param fixedSource where [fixed] came from, null for rows cached before the database kept it
 * @param syncedSource where [synced] came from, null for rows cached before the database kept it
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
    val syncedSource: LyricsSource? = null
)
