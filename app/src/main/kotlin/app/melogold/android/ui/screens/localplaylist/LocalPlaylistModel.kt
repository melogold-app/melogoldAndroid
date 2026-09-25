package app.melogold.android.ui.screens.localplaylist

import app.melogold.android.Database
import app.melogold.android.data.repo.PlaylistLinks
import app.melogold.android.data.repo.RefreshResult
import app.melogold.android.data.repo.PendingMutation
import app.melogold.android.data.repo.applying
import app.melogold.android.data.repo.applyingTo
import app.melogold.android.data.repo.withPending
import app.melogold.android.models.Playlist
import app.melogold.android.models.Song
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEEP_WHILE_HIDDEN_MS = 5_000L
private const val COVERS = 4

/** A linked playlist is refreshed on opening when its last refresh is older than this. */
private const val AUTO_REFRESH_AFTER_MS = 12 * 60 * 60 * 1000L

/**
 * An own playlist (REWRITE §3.8.1): its tracks in their order (or newest first), its covers, and
 * the edits — reorder, rename, the YouTube link and its refresh. Removing a track and deleting the
 * playlist wait for "Undo" ([PendingMutation]); the flows hide what they delete meanwhile.
 */
class LocalPlaylistModel(
    private val playlistId: Long,
    private val appScope: CoroutineScope
) : ScreenModel() {
    val playlist: StateFlow<Playlist?> = Database.playlist(playlistId)
        .withPending { applying(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val songs: StateFlow<List<Song>?> = Database.playlistSongs(playlistId)
        .withPending { applyingTo(playlistId, it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val songsByDateAdded: StateFlow<List<Song>?> = Database.playlistSongsByDateAdded(playlistId)
        .withPending { applyingTo(playlistId, it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    /** The artwork of the first four tracks, from the tracks as shown. */
    val covers: StateFlow<List<String>> = songs
        .map { songs -> songs.orEmpty().mapNotNull { it.thumbnailUrl }.take(COVERS) }
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

    /**
     * Moves the track at [from] in the own order to [to], both indices in the list as shown: Room
     * may still hold tracks being taken out, so the move goes by the tracks, not by the indices.
     */
    fun move(from: Int, to: Int) {
        if (from == to) return
        val shown = songs.value ?: return
        val moved = shown.getOrNull(from) ?: return
        val target = shown.getOrNull(to) ?: return

        transaction {
            val fromPosition = Database.positionIn(moved.id, playlistId) ?: return@transaction
            val toPosition = Database.positionIn(target.id, playlistId) ?: return@transaction
            Database.move(playlistId, fromPosition, toPosition)
        }
    }
}
