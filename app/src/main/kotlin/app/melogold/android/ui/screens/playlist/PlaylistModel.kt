package app.melogold.android.ui.screens.playlist

import app.melogold.android.Database
import app.melogold.android.data.repo.IncompletePlaylistException
import app.melogold.android.data.repo.PlaylistLinks
import app.melogold.android.models.Playlist
import app.melogold.android.models.YtLinkMode
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.Page
import app.melogold.android.ui.model.Paged
import app.melogold.android.ui.model.PagedLoader
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.classify
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.models.bodies.ContinuationBody
import app.melogold.providers.innertube.requests.playlistPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEEP_WHILE_HIDDEN_MS = 5_000L

/** What the YouTube playlist screen shows (REWRITE §3.8.2). */
data class PlaylistDetails(
    val page: Innertube.PlaylistOrAlbumPage,
    val songs: Paged<Innertube.SongItem>,
    /** The copy in the Library, if the playlist was saved. */
    val saved: Playlist?
)

/** How saving to the Library ended. */
sealed interface SaveResult {
    data class Saved(val playlistId: Long) : SaveResult
    data object Incomplete : SaveResult
    data class Failed(val kind: Loadable.Error.Kind) : SaveResult
}

/**
 * A YouTube playlist (REWRITE §3.8.2): the first page at once, the rest as the list scrolls.
 * Playing before everything is loaded starts with what is there and queues the rest as it comes.
 */
class PlaylistModel(
    private val browseId: String,
    private val params: String?,
    private val appScope: CoroutineScope
) : ScreenModel() {
    private val page = MutableStateFlow<Innertube.PlaylistOrAlbumPage?>(null)
    private var playJob: Job? = null

    val songs = PagedLoader(
        scope = scope,
        key = Innertube.SongItem::key,
        first = {
            withContext(Dispatchers.IO) { Innertube.playlistPage(BrowseBody(browseId = browseId, params = params)) }
                ?.map { first ->
                    page.value = first
                    Page(first.songsPage?.items.orEmpty(), first.songsPage?.continuation)
                }
        },
        next = { token ->
            withContext(Dispatchers.IO) { Innertube.playlistPage(ContinuationBody(continuation = token)) }
                ?.map { Page(it?.items.orEmpty(), it?.continuation) }
        }
    )

    val state: StateFlow<Loadable<PlaylistDetails>> = combine(
        page,
        songs.state,
        Database.playlistByBrowseId(browseId)
    ) { page, songs, saved ->
        when {
            page != null -> Loadable.Content(PlaylistDetails(page = page, songs = songs, saved = saved))
            songs.error != null -> Loadable.Error(songs.error)
            else -> Loadable.Loading
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), Loadable.Loading)

    /**
     * Plays the playlist from [index] (or shuffled): what is loaded now, then the rest, page by
     * page, at the end of the queue.
     */
    fun play(binder: PlayerService.Binder, index: Int = 0, shuffle: Boolean = false) {
        val loaded = songs.state.value.items
        if (loaded.isEmpty()) return

        val items = (if (shuffle) loaded.shuffled() else loaded).map { it.asMediaItem }
        binder.stopRadio()
        binder.player.forcePlayAtIndex(items = items, index = if (shuffle) 0 else index)

        // The queue is still ours while it holds our first track: something else played stops us
        val anchor = items.first().mediaId
        val player = binder.player

        playJob?.cancel()
        playJob = scope.launch {
            var queued = loaded.size
            while (songs.awaitNext()) {
                val items = songs.state.value.items
                val fresh = items.drop(queued)
                queued = items.size

                val ours = (0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId == anchor }
                if (!ours) break
                if (fresh.isNotEmpty()) player.addMediaItems((if (shuffle) fresh.shuffled() else fresh).map { it.asMediaItem })
            }
        }
    }

    /**
     * Saves the whole playlist into the Library (§3.8.2): only a complete list is saved. It runs in
     * the app's scope, so leaving the screen doesn't stop it; [onResult] comes on the main thread.
     */
    fun save(name: String, mode: YtLinkMode, onResult: (SaveResult) -> Unit) {
        appScope.launch {
            val result = PlaylistLinks.fetchWhole(browseId, params).fold(
                onSuccess = { whole -> SaveResult.Saved(PlaylistLinks.save(browseId, name, whole, mode)) },
                onFailure = { error ->
                    if (error is IncompletePlaylistException) SaveResult.Incomplete
                    else SaveResult.Failed(classify(error))
                }
            )
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }
}
