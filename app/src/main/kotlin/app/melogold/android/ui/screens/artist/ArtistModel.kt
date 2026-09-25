package app.melogold.android.ui.screens.artist

import app.melogold.android.Database
import app.melogold.android.models.Artist
import app.melogold.android.models.Song
import app.melogold.android.query
import app.melogold.android.ui.model.Fetch
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.Page
import app.melogold.android.ui.model.PagedLoader
import app.melogold.android.ui.model.STALE_AFTER_MS
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.classify
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.requests.artistPage
import app.melogold.providers.innertube.youtube.YouTubeChannelPage
import app.melogold.providers.innertube.youtube.YouTubeItem
import app.melogold.providers.innertube.youtube.youTubeChannel
import app.melogold.providers.innertube.youtube.youTubeChannelPlaylists
import app.melogold.providers.innertube.youtube.youTubeChannelVideos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEEP_WHILE_HIDDEN_MS = 5_000L

/**
 * What the artist screen shows (REWRITE §3.7.1): the [artist] (from Room, or from the page before
 * Room has it), the sections of the YouTube Music [page] (null until it loads, or offline) and the
 * artist's tracks in Favorites. A plain YouTube channel has a [channel] instead of a [page].
 */
data class ArtistDetails(
    val artist: Artist,
    val page: Innertube.ArtistPage?,
    val favorites: List<Song>,
    val channel: ChannelDetails? = null
)

/**
 * A plain YouTube channel (REWRITE §3.7.2): its header, its videos, newest first and endless, and
 * its playlists, which load after the videos.
 */
class ChannelDetails(
    val page: YouTubeChannelPage,
    val videos: PagedLoader<YouTubeItem>,
    val playlists: StateFlow<List<YouTubeItem.Playlist>>
)

// A few videos alone don't make an artist: such a channel reads better as a channel, all videos
private val Innertube.ArtistPage.hasMusic
    get() = !songs.isNullOrEmpty() || !albums.isNullOrEmpty() || !singles.isNullOrEmpty()

/**
 * An artist, read by §4.11.2: Room gives the header and "In your library" at once and offline;
 * the page is fetched on every visit for the sections and written to Room when Room has no artist
 * or it is older than a day.
 */
class ArtistModel(private val browseId: String) : ScreenModel() {
    private val page = MutableStateFlow<Innertube.ArtistPage?>(null)
    private val channel = MutableStateFlow<ChannelDetails?>(null)
    private val fetch = MutableStateFlow<Fetch>(Fetch.Running)

    val state: StateFlow<Loadable<ArtistDetails>> = combine(
        Database.artist(browseId),
        Database.artistFavorites(browseId),
        page,
        channel,
        fetch
    ) { saved, favorites, page, channel, fetch ->
        val artist = saved?.takeIf { it.timestamp != null }
            ?: page?.let {
                Artist(id = browseId, name = it.name, thumbnailUrl = it.thumbnail?.url, bookmarkedAt = saved?.bookmarkedAt)
            }
            ?: channel?.let {
                Artist(id = browseId, name = it.page.name, thumbnailUrl = it.page.avatarUrl, bookmarkedAt = saved?.bookmarkedAt)
            }

        when {
            artist != null -> Loadable.Content(
                value = ArtistDetails(artist = artist, page = page, favorites = favorites, channel = channel),
                refreshing = fetch == Fetch.Running,
                staleSince = artist.timestamp.takeIf { fetch is Fetch.Failed },
                staleReason = (fetch as? Fetch.Failed)?.kind
            )

            fetch is Fetch.Failed -> Loadable.Error(fetch.kind)
            else -> Loadable.Loading
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), Loadable.Loading)

    init {
        load()
    }

    fun load() {
        fetch.value = Fetch.Running

        scope.launch {
            val result = withContext(Dispatchers.IO) { Innertube.artistPage(BrowseBody(browseId = browseId)) }
            val newPage = result?.getOrNull()?.takeIf { it.name != null && it.hasMusic }

            if (newPage != null) {
                withContext(Dispatchers.IO) { save(name = newPage.name, thumbnailUrl = newPage.thumbnail?.url) }
                page.value = newPage
                fetch.value = Fetch.Done
                return@launch
            }

            // No music on YouTube Music: a plain channel, read from YouTube itself (§4.8.5)
            val channelResult = withContext(Dispatchers.IO) { Innertube.youTubeChannel(browseId) }
            val channelPage = channelResult?.getOrNull()

            if (channelPage == null) {
                val failure = channelResult?.exceptionOrNull() ?: result?.exceptionOrNull()
                fetch.value = Fetch.Failed(failure?.let(::classify) ?: Loadable.Error.Kind.Parser)
                return@launch
            }

            withContext(Dispatchers.IO) { save(name = channelPage.name, thumbnailUrl = channelPage.avatarUrl) }
            channel.value = channelDetails(channelPage)
            fetch.value = Fetch.Done
        }
    }

    private fun channelDetails(channelPage: YouTubeChannelPage): ChannelDetails {
        val playlists = MutableStateFlow<List<YouTubeItem.Playlist>>(emptyList())
        channelPage.playlistsParams?.let { params ->
            scope.launch {
                withContext(Dispatchers.IO) { Innertube.youTubeChannelPlaylists(browseId, params) }
                    ?.getOrNull()
                    ?.let { playlists.value = it }
            }
        }

        return ChannelDetails(
            page = channelPage,
            videos = PagedLoader(
                scope = scope,
                key = { it.key },
                first = { Result.success(Page(channelPage.videos.items, channelPage.videos.continuation)) },
                next = { token ->
                    withContext(Dispatchers.IO) { Innertube.youTubeChannelVideos(token) }
                        ?.map { Page(it.items, it.continuation) }
                }
            ),
            playlists = playlists
        )
    }

    /** Writes the artist unless Room already has a fresh copy; keeps the subscription. */
    private suspend fun save(name: String?, thumbnailUrl: String?) {
        val current = Database.artist(browseId).first()
        val fresh = current?.timestamp?.let { System.currentTimeMillis() - it < STALE_AFTER_MS } == true
        if (fresh) return

        Database.upsert(
            Artist(
                id = browseId,
                name = name,
                thumbnailUrl = thumbnailUrl,
                timestamp = System.currentTimeMillis(),
                bookmarkedAt = current?.bookmarkedAt
            )
        )
    }

    /** "Subscribe" is the artist's bookmark; "Undo" puts the old one back. */
    fun setBookmark(artist: Artist, bookmarkedAt: Long?) = query {
        Database.upsert(
            artist.copy(
                timestamp = artist.timestamp ?: System.currentTimeMillis(),
                bookmarkedAt = bookmarkedAt
            )
        )
    }
}
