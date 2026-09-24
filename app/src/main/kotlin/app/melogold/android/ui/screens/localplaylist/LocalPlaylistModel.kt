package app.melogold.android.ui.screens.localplaylist

import app.melogold.android.Database
import app.melogold.android.data.repo.PlaylistLinks
import app.melogold.android.data.repo.RefreshResult
import app.melogold.android.internal
import app.melogold.android.models.Playlist
import app.melogold.android.models.Song
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.models.YtLinkMode
import app.melogold.android.query
import app.melogold.android.transaction
import app.melogold.android.ui.model.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEEP_WHILE_HIDDEN_MS = 5_000L

/** A linked playlist is refreshed on opening when its last refresh is older than this. */
private const val AUTO_REFRESH_AFTER_MS = 12 * 60 * 60 * 1000L

/** What deleting a playlist removed, to put it back on "Undo". */
class DeletedPlaylist internal constructor(
    val playlist: Playlist,
    val maps: List<SongPlaylistMap>
)

/**
 * An own playlist (REWRITE §3.8.1): its tracks in their order (or newest first), its covers, and
 * the edits — reorder, remove, rename, delete, the YouTube link and its refresh.
 */
class LocalPlaylistModel(
    private val playlistId: Long,
    private val appScope: CoroutineScope
) : ScreenModel() {
    val playlist: StateFlow<Playlist?> = Database.playlist(playlistId)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val songs: StateFlow<List<Song>?> = Database.playlistSongs(playlistId)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val songsByDateAdded: StateFlow<List<Song>?> = Database.playlistSongsByDateAdded(playlistId)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val covers: StateFlow<List<String>> = Database.playlistThumbnailUrls(playlistId)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), emptyList())

    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    /** Refreshes a linked playlist whose last refresh is old, once, when the screen opens. */
    fun refreshIfStale(onResult: (RefreshResult) -> Unit) {
        scope.launch {
            val current = playlist.first { it != null } ?: return@launch
            val linked = current.ytLinkMode == YtLinkMode.Mirror || current.ytLinkMode == YtLinkMode.Append
            val stale = current.ytSyncedAt?.let { System.currentTimeMillis() - it > AUTO_REFRESH_AFTER_MS } ?: true
            if (linked && stale) refresh(onResult)
        }
    }

    /** "Refresh from YouTube" (§3.8.1): applied only when YouTube gave the whole list. */
    fun refresh(onResult: (RefreshResult) -> Unit) {
        val current = playlist.value ?: return
        if (mutableRefreshing.value) return

        mutableRefreshing.value = true
        appScope.launch {
            val result = PlaylistLinks.refresh(current)
            withContext(Dispatchers.Main) {
                mutableRefreshing.value = false
                onResult(result)
            }
        }
    }

    fun setLinkMode(mode: YtLinkMode) = query { Database.setYtLinkMode(playlistId, mode) }

    fun rename(name: String) = query {
        playlist.value?.let { Database.update(it.copy(name = name)) }
    }

    /** Moves a track in the own order. */
    fun move(from: Int, to: Int) {
        if (from == to) return
        transaction { Database.move(playlistId, from, to) }
    }

    /** Removes the track at [position]; the result puts it back on "Undo". */
    fun remove(song: Song, position: Int): () -> Unit {
        transaction {
            Database.move(playlistId, position, Int.MAX_VALUE)
            Database.delete(SongPlaylistMap(song.id, playlistId, Int.MAX_VALUE))
        }
        return {
            transaction {
                val count = Database.songCountOf(playlistId)
                Database.insert(SongPlaylistMap(song.id, playlistId, count))
                if (position < count) Database.move(playlistId, count, position)
            }
        }
    }

    /** Deletes the playlist; what it returns restores it, tracks and order included. */
    suspend fun delete(): DeletedPlaylist? = withContext(Dispatchers.IO) {
        val current = playlist.value ?: return@withContext null
        Database.internal.runInTransaction<DeletedPlaylist> {
            val maps = Database.songPlaylistMaps(playlistId)
            Database.delete(current)
            DeletedPlaylist(current, maps)
        }
    }

    companion object {
        fun restore(deleted: DeletedPlaylist) = transaction {
            Database.insert(deleted.playlist)
            Database.insertSongPlaylistMaps(deleted.maps)
        }
    }
}
