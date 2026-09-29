package app.melogold.android.sync.remote

import androidx.media3.common.MediaItem
import app.melogold.android.Dependencies
import app.melogold.android.sync.api.TrackInput
import app.melogold.android.ui.kit.parseDuration
import app.melogold.core.ui.utils.songBundle

/**
 * Where "play this list from this track" goes (tasks/0018): to the device this one controls, when it controls one
 * (`play_queue`, the queue of the list and the index of the track), else to the player here.
 */
object RemoteRouting {
    /**
     * Sends [items] from [index] to the controlled device; false when none is controlled and the caller plays them
     * itself. A track that cannot play there (a file of this device) is taken, and does nothing.
     */
    fun playQueue(items: List<MediaItem>, index: Int): Boolean {
        val remote = runCatching { Dependencies.application.container.remote }.getOrNull() ?: return false
        if (!remote.active) return false
        return remote.playQueue(items.map { it.toTrackInput() }, index)
    }
}

/** A track of the queue as a state or a command carries it (API §4.1 `TrackInput`). */
fun MediaItem.toTrackInput(): TrackInput {
    val metadata = mediaMetadata
    val extras = metadata.extras?.songBundle
    val durationText = extras?.durationText
    return TrackInput(
        videoId = mediaId,
        title = metadata.title?.toString(),
        artistsText = metadata.artist?.toString(),
        albumId = extras?.albumId,
        albumTitle = metadata.albumTitle?.toString(),
        durationMs = parseDuration(durationText),
        durationText = durationText,
        thumbnailUrl = metadata.artworkUri?.toString(),
        explicit = extras?.explicit?.takeIf { it }
    )
}
