package app.melogold.android.data.repo

import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.models.Album
import app.melogold.android.models.Artist
import app.melogold.android.models.Info
import app.melogold.android.models.SongAlbumMap
import app.melogold.android.models.SongArtistMap
import app.melogold.android.service.isLocal
import app.melogold.core.ui.utils.songBundle
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.requests.song
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"

/** Where the menu of a track leads (REWRITE §3.10.5): its album and its artists. */
data class TrackLinks(val album: Info?, val artists: List<Info>)

// What YouTube Music answered in this process, so a video (which has no album) is asked once
private val answered = ConcurrentHashMap<String, TrackLinks>()

/** The album and the artists this item carries itself, without asking anyone. */
val MediaItem.knownTrackLinks: TrackLinks
    get() {
        val extras = mediaMetadata.extras?.songBundle
        val artists = extras?.artistNames?.let { names ->
            extras.artistIds?.let { ids -> names.zip(ids) { name, id -> Info(id, name) } }
        }

        return TrackLinks(
            album = extras?.albumId?.let { Info(it, mediaMetadata.albumTitle?.toString()) },
            artists = artists.orEmpty()
        )
    }

/**
 * The album and the artists of this track: what the item carries, then Room, and what is still
 * missing from its YouTube Music watch page, saved to Room when the track is there. Local files
 * have neither.
 */
suspend fun MediaItem.trackLinks(): TrackLinks = withContext(Dispatchers.IO) {
    if (isLocal) return@withContext TrackLinks(album = null, artists = emptyList())

    val known = knownTrackLinks
    val album = known.album ?: Database.songAlbumInfo(mediaId)
    val artists = known.artists.ifEmpty { Database.songArtistInfo(mediaId) }
    if (album != null && artists.isNotEmpty()) return@withContext TrackLinks(album, artists)

    val found = answered[mediaId] ?: Innertube.song(mediaId)?.getOrNull()?.let { song ->
        TrackLinks(
            album = song.album
                ?.takeIf { (it.endpoint?.type ?: ALBUM_PAGE) == ALBUM_PAGE }
                ?.endpoint
                ?.browseId
                ?.let { id -> Info(id, song.album?.name) },
            artists = song.authors.orEmpty().mapNotNull { author ->
                author.endpoint?.browseId?.let { Info(it, author.name) }
            }
        ).also { answered[mediaId] = it }
    } ?: return@withContext TrackLinks(album, artists)

    // Saved only for a track Room has: the links need its row
    runCatching {
        if (album == null) found.album?.let {
            Database.insert(Album(id = it.id, title = it.name), SongAlbumMap(songId = mediaId, albumId = it.id, position = null))
        }
        if (artists.isEmpty() && found.artists.isNotEmpty()) Database.insert(
            found.artists.map { Artist(id = it.id, name = it.name) },
            found.artists.map { SongArtistMap(songId = mediaId, artistId = it.id) }
        )
    }

    TrackLinks(album = album ?: found.album, artists = artists.ifEmpty { found.artists })
}
