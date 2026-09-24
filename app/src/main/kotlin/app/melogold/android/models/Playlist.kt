package app.melogold.android.models

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A playlist of the Library. One saved from YouTube keeps its [browseId]; how it follows YouTube is
 * [ytLinkMode] (REWRITE §3.8): [ytSyncedAt] is the last refresh, [ytSnapshot] the video ids YouTube
 * had then, one per line, so "append" knows which tracks are new.
 */
@Immutable
@Entity
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val browseId: String? = null,
    val thumbnail: String? = null,
    val ytLinkMode: YtLinkMode? = null,
    val ytSyncedAt: Long? = null,
    val ytSnapshot: String? = null
)

/** How a playlist saved from YouTube follows it (REWRITE §3.8.1). */
enum class YtLinkMode {
    /** The list is always YouTube's: local edits get overwritten. */
    Mirror,

    /** New YouTube tracks are added at the end; local edits stay. */
    Append,

    /** Not updated from YouTube any more. */
    Off
}
