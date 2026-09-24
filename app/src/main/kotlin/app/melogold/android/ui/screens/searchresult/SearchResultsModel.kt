package app.melogold.android.ui.screens.searchresult

import app.melogold.android.ui.shell.SearchSource
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.Page
import app.melogold.android.ui.model.PagedLoader
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.classify
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.MusicShelfRenderer
import app.melogold.providers.innertube.models.bodies.ContinuationBody
import app.melogold.providers.innertube.models.bodies.SearchBody
import app.melogold.providers.innertube.requests.searchPage
import app.melogold.providers.innertube.utils.from
import app.melogold.providers.innertube.youtube.YouTubeItem
import app.melogold.providers.innertube.youtube.YouTubeSearchFilter
import app.melogold.providers.innertube.youtube.youTubeSearch
import app.melogold.providers.innertube.youtube.youTubeSearchContinuation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val ALL_SONGS = 4
private const val ALL_VIDEOS = 4

/**
 * The narrowing chips of the Music segment (REWRITE §3.1.3).
 */
enum class MusicFilter(val filter: Innertube.SearchFilter) {
    Songs(Innertube.SearchFilter.Song),
    Albums(Innertube.SearchFilter.Album),
    Artists(Innertube.SearchFilter.Artist),
    Videos(Innertube.SearchFilter.Video),
    Playlists(Innertube.SearchFilter.CommunityPlaylist)
}

/**
 * "All": the top of both sources side by side; a failed source doesn't hide the other.
 */
data class AllResults(
    val artist: Innertube.ArtistItem?,
    val songs: List<Innertube.SongItem>,
    val videos: List<YouTubeItem.Video>,
    val musicError: Loadable.Error.Kind?,
    val youTubeError: Loadable.Error.Kind?
) {
    val musicEmpty get() = musicError == null && artist == null && songs.isEmpty()
    val isEmpty get() = musicEmpty && youTubeError == null && videos.isEmpty()
}

/**
 * The results of one query (REWRITE §3.1.3): "All" and the endless lists of each segment and chip.
 * The chosen segment and chips live here, so they survive going back from a detail screen.
 */
class SearchResultsModel(
    private val query: String,
    initialSource: SearchSource = SearchSource.All
) : ScreenModel() {
    val source = MutableStateFlow(initialSource)
    val musicFilter = MutableStateFlow(MusicFilter.Songs)
    val youTubeFilter = MutableStateFlow(YouTubeSearchFilter.Videos)

    private val mutableAll = MutableStateFlow<Loadable<AllResults>>(Loadable.Loading)
    val all: StateFlow<Loadable<AllResults>> = mutableAll.asStateFlow()

    private val musicLoaders = mutableMapOf<MusicFilter, PagedLoader<Innertube.Item>>()
    private val youTubeLoaders = mutableMapOf<YouTubeSearchFilter, PagedLoader<YouTubeItem>>()

    init {
        loadAll()
    }

    fun loadAll() {
        mutableAll.value = Loadable.Loading
        scope.launch {
            coroutineScope {
                val songs = async { musicPage(MusicFilter.Songs, null) }
                val artists = async { musicPage(MusicFilter.Artists, null) }
                val videos = async { Innertube.youTubeSearch(query, YouTubeSearchFilter.Videos) }

                val songsResult = songs.await()
                val artistsResult = artists.await()
                val videosResult = videos.await()

                val songItems = songsResult?.getOrNull()?.items.orEmpty().filterIsInstance<Innertube.SongItem>()
                val musicIds = songItems.map { it.key }.toSet()
                val musicFailure = songsResult?.exceptionOrNull() ?: artistsResult?.exceptionOrNull()

                val all = AllResults(
                    artist = artistsResult?.getOrNull()?.items?.firstOrNull() as? Innertube.ArtistItem,
                    songs = songItems.take(ALL_SONGS),
                    // Re-uploads of what the catalog already has add nothing
                    videos = videosResult?.getOrNull()?.items.orEmpty()
                        .filterIsInstance<YouTubeItem.Video>()
                        .filter { it.videoId !in musicIds }
                        .take(ALL_VIDEOS),
                    musicError = musicFailure?.takeIf { songItems.isEmpty() }?.let(::classify),
                    youTubeError = videosResult?.exceptionOrNull()?.let(::classify)
                )

                mutableAll.value = if (all.musicError != null && all.youTubeError != null)
                    Loadable.Error(all.musicError)
                else Loadable.Content(all)
            }
        }
    }

    fun music(filter: MusicFilter): PagedLoader<Innertube.Item> = musicLoaders.getOrPut(filter) {
        PagedLoader(
            scope = scope,
            key = { it.key },
            first = { musicPage(filter, null) },
            next = { musicPage(filter, it) }
        )
    }

    fun youTube(filter: YouTubeSearchFilter): PagedLoader<YouTubeItem> = youTubeLoaders.getOrPut(filter) {
        PagedLoader(
            scope = scope,
            key = { it.key },
            first = {
                Innertube.youTubeSearch(query, filter)?.map { Page(it.items, it.continuation) }
            },
            next = { token ->
                Innertube.youTubeSearchContinuation(token)?.map { Page(it.items, it.continuation) }
            }
        )
    }

    private suspend fun musicPage(filter: MusicFilter, continuation: String?): Result<Page<Innertube.Item>>? {
        val mapper: (MusicShelfRenderer.Content) -> Innertube.Item? = when (filter) {
            MusicFilter.Songs -> Innertube.SongItem.Companion::from
            MusicFilter.Albums -> Innertube.AlbumItem::from
            MusicFilter.Artists -> Innertube.ArtistItem::from
            MusicFilter.Videos -> Innertube.VideoItem::from
            MusicFilter.Playlists -> Innertube.PlaylistItem::from
        }

        val result = if (continuation == null) Innertube.searchPage(
            body = SearchBody(query = query, params = filter.filter.value),
            fromMusicShelfRendererContent = mapper
        ) else Innertube.searchPage(
            body = ContinuationBody(continuation = continuation),
            fromMusicShelfRendererContent = mapper
        )

        return result?.map { page -> Page(page?.items.orEmpty(), page?.continuation) }
    }
}
