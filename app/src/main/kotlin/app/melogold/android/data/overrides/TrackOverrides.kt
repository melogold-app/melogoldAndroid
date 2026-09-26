package app.melogold.android.data.overrides

import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.internal
import app.melogold.android.models.TrackOverride
import app.melogold.android.query
import app.melogold.core.ui.utils.songBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val TAG = "TrackOverrides"

/**
 * The user's own titles, artists and albums of tracks (tasks/0012), by video id. They are laid over what YouTube
 * calls a track in one place, where a [MediaItem] is made ([withOverride]): the player, the mini player, the queue,
 * the media session and "Save as file" show them without knowing. Lists of stored tracks take them from [all].
 */
object TrackOverrides {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val all: StateFlow<Map<String, TrackOverride>> by lazy {
        runCatching {
            Database.trackOverrides()
                .map { list -> list.associateBy(TrackOverride::videoId) }
                .stateIn(scope, SharingStarted.Eagerly, emptyMap())
        }.getOrElse {
            Log.w(TAG, "No database for the overrides", it)
            MutableStateFlow(emptyMap())
        }
    }

    operator fun get(videoId: String): TrackOverride? = all.value[videoId]

    /**
     * Sets the override of [videoId] as a whole (as the server does, API §4.8): a blank field has none; all blank
     * removes it.
     */
    fun set(videoId: String, title: String?, artistsText: String?, albumTitle: String?) = query {
        val override = TrackOverride(
            videoId = videoId,
            title = TrackOverride.clean(title),
            artistsText = TrackOverride.clean(artistsText),
            albumTitle = TrackOverride.clean(albumTitle),
            updatedAt = System.currentTimeMillis()
        )
        if (override.isEmpty) Database.deleteTrackOverride(videoId) else Database.upsert(override)
    }

    /** Gives every one of [videoIds] the album [albumTitle], keeping their other overrides ("Set album…"). */
    fun setAlbum(videoIds: List<String>, albumTitle: String) = query {
        val album = TrackOverride.clean(albumTitle)
        val now = System.currentTimeMillis()
        Database.internal.runInTransaction {
            videoIds.forEach { videoId ->
                val current = Database.trackOverride(videoId)
                val override = TrackOverride(
                    videoId = videoId,
                    title = current?.title,
                    artistsText = current?.artistsText,
                    albumTitle = album,
                    updatedAt = now
                )
                if (override.isEmpty) Database.deleteTrackOverride(videoId) else Database.upsert(override)
            }
        }
    }
}

/** What YouTube calls the track, whatever override shows. */
val MediaItem.originalTitle: String?
    get() = mediaMetadata.extras?.songBundle?.takeIf { it.overridden }?.originalTitle
        ?: mediaMetadata.title?.takeUnless { mediaMetadata.extras?.songBundle?.overridden == true }?.toString()

val MediaItem.originalArtist: String?
    get() = mediaMetadata.extras?.songBundle?.takeIf { it.overridden }?.originalArtist
        ?: mediaMetadata.artist?.takeUnless { mediaMetadata.extras?.songBundle?.overridden == true }?.toString()

val MediaItem.originalAlbum: String?
    get() = mediaMetadata.extras?.songBundle?.takeIf { it.overridden }?.originalAlbum
        ?: mediaMetadata.albumTitle?.takeUnless { mediaMetadata.extras?.songBundle?.overridden == true }?.toString()

/**
 * This item with [override] laid over what YouTube calls it (tasks/0012), or back to YouTube's names without one.
 * What YouTube gave stays in the extras, so the item can always go back, and what is stored of the track
 * ([Database.insert]) is YouTube's.
 */
fun MediaItem.withOverride(override: TrackOverride? = TrackOverrides[mediaId]): MediaItem {
    val metadata = mediaMetadata
    val overridden = metadata.extras?.songBundle?.overridden == true
    if (override == null && !overridden) return this

    val title = originalTitle
    val artist = originalArtist
    val album = originalAlbum
    val extras = Bundle(metadata.extras ?: Bundle()).also { bundle ->
        bundle.songBundle.apply {
            this.overridden = override != null
            originalTitle = if (override != null) title else null
            originalArtist = if (override != null) artist else null
            originalAlbum = if (override != null) album else null
        }
    }

    return buildUpon()
        .setMediaMetadata(
            metadata.buildUpon()
                .setTitle(override?.title ?: title)
                .setArtist(override?.artistsText ?: artist)
                .setAlbumTitle(override?.albumTitle ?: album)
                .setExtras(extras)
                .build()
        )
        .build()
}

/** The title to show: the override's, else [original]. */
fun TrackOverride?.title(original: String): String = this?.title ?: original

/** The artist to show: the override's, else [original]. */
fun TrackOverride?.artists(original: String?): String? = this?.artistsText ?: original
