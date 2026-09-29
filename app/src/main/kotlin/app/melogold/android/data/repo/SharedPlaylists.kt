package app.melogold.android.data.repo

import app.melogold.android.Database
import app.melogold.android.internal
import app.melogold.android.models.Album
import app.melogold.android.models.Artist
import app.melogold.android.models.Playlist
import app.melogold.android.models.Song
import app.melogold.android.models.SongAlbumMap
import app.melogold.android.models.SongArtistMap
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.sync.api.ShareDto
import app.melogold.android.sync.api.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tracks of a shared playlist as the songs the app plays; nothing is stored. */
fun ShareDto.songs(): List<Song> = tracks.distinctBy { it.videoId }.map(TrackDto::toSong)

/** A track of a snapshot as a song: a track without metadata (a stub) is named by its id until it is played. */
fun TrackDto.toSong() = Song(
    id = videoId,
    title = title.takeIf { it.isNotBlank() && !metadataStub } ?: videoId,
    artistsText = artistsText,
    durationText = durationText,
    thumbnailUrl = thumbnailUrl,
    explicit = explicit
)

/** "Save to Library" of a playlist by a link (tasks/0017). */
object SharedPlaylists {
    /**
     * Makes an own playlist [name] of the tracks of [share] in their order, with what the snapshot knows of them: the
     * songs with their titles, artists and covers, their albums and artists. A track the Library has keeps what it has
     * and gets what it lacks. Returns the id of the playlist.
     */
    suspend fun save(share: ShareDto, name: String = share.name): Long? = withContext(Dispatchers.IO) {
        var playlistId: Long? = null
        Database.internal.runInTransaction {
            val id = Database.insert(Playlist(name = name.trim().ifEmpty { share.name })).takeIf { it != -1L } ?: return@runInTransaction
            share.tracks.distinctBy { it.videoId }.forEachIndexed { position, track ->
                val song = track.toSong()
                if (Database.insert(song) == -1L) {
                    Database.fillMissing(song.id, song.title, song.thumbnailUrl, song.artistsText, song.durationText)
                }
                track.albumId?.let { albumId ->
                    Database.insert(Album(id = albumId, title = track.albumTitle), SongAlbumMap(song.id, albumId, position = null))
                }
                val artists = track.artists.filter { it.id != null }
                if (artists.isNotEmpty()) Database.insert(
                    artists.map { Artist(id = it.id!!, name = it.name) },
                    artists.map { SongArtistMap(song.id, it.id!!) }
                )
                Database.insert(SongPlaylistMap(song.id, id, position))
            }
            playlistId = id
        }
        playlistId
    }
}
