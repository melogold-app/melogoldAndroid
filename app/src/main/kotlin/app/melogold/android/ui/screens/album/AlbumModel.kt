package app.melogold.android.ui.screens.album

import app.melogold.android.Database
import app.melogold.android.models.Album
import app.melogold.android.models.Song
import app.melogold.android.models.SongAlbumMap
import app.melogold.android.query
import app.melogold.android.transaction
import app.melogold.android.ui.model.Fetch
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.STALE_AFTER_MS
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.classify
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.completed
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.requests.albumPage
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

/** A name in "Artist · 2024 · …": a link when [id] is known, plain text (" & ") otherwise. */
data class Credit(val name: String, val id: String?)

/**
 * What the album screen shows (REWRITE §3.6). The [related] albums (titled by YouTube Music:
 * "Other versions", "Releases for you"…) and the artist links come from the network only:
 * without it the sections are simply not there.
 */
data class AlbumDetails(
    val album: Album,
    val songs: List<Song>,
    val credits: List<Credit>,
    val related: List<Innertube.AlbumItem>,
    val relatedTitle: String?
)

/**
 * An album, read by §4.11.2: Room first; the page is fetched on every visit for the sections only
 * the network has, and written to Room when Room has no album yet or it is older than a day.
 */
class AlbumModel(private val browseId: String) : ScreenModel() {
    private val page = MutableStateFlow<Innertube.PlaylistOrAlbumPage?>(null)
    private val fetch = MutableStateFlow<Fetch>(Fetch.Running)

    val state: StateFlow<Loadable<AlbumDetails>> = combine(
        Database.album(browseId),
        Database.albumSongs(browseId),
        page,
        fetch
    ) { album, songs, page, fetch ->
        val saved = album?.takeIf { it.timestamp != null && songs.isNotEmpty() }

        when {
            saved != null -> Loadable.Content(
                value = AlbumDetails(
                    album = saved,
                    songs = songs,
                    credits = page?.authors
                        ?.map { Credit(name = it.name.orEmpty(), id = it.endpoint?.browseId) }
                        ?.takeIf { credits -> credits.any { it.name.isNotBlank() } }
                        ?: listOfNotNull(saved.authorsText?.let { Credit(name = it, id = null) }),
                    related = page?.relatedAlbums.orEmpty().filter { it.info?.endpoint?.browseId != null },
                    relatedTitle = page?.relatedAlbumsTitle
                ),
                refreshing = fetch == Fetch.Running,
                staleSince = saved.timestamp.takeIf { fetch is Fetch.Failed },
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
            val result = withContext(Dispatchers.IO) {
                Innertube.albumPage(BrowseBody(browseId = browseId))?.completed()
            }
            val newPage = result?.getOrNull()?.takeIf { !it.songsPage?.items.isNullOrEmpty() }

            if (newPage == null) {
                fetch.value = Fetch.Failed(
                    result?.exceptionOrNull()?.let(::classify) ?: Loadable.Error.Kind.Parser
                )
                return@launch
            }

            withContext(Dispatchers.IO) { save(newPage) }
            page.value = newPage
            fetch.value = Fetch.Done
        }
    }

    /** Writes [newPage] unless Room already has a fresh copy; keeps the bookmark. */
    private suspend fun save(newPage: Innertube.PlaylistOrAlbumPage) {
        val current = Database.album(browseId).first()
        val fresh = current?.timestamp?.let { System.currentTimeMillis() - it < STALE_AFTER_MS } == true
        if (fresh && Database.albumSongs(browseId).first().isNotEmpty()) return

        transaction {
            Database.clearAlbum(browseId)
            Database.upsert(
                album = Album(
                    id = browseId,
                    title = newPage.title,
                    description = newPage.description,
                    thumbnailUrl = newPage.thumbnail?.url,
                    year = newPage.year,
                    authorsText = newPage.authors?.joinToString("") { it.name.orEmpty() },
                    shareUrl = newPage.url,
                    timestamp = System.currentTimeMillis(),
                    bookmarkedAt = current?.bookmarkedAt,
                    otherInfo = newPage.otherInfo
                ),
                songAlbumMaps = newPage.songsPage?.items.orEmpty()
                    .map { it.asMediaItem }
                    .onEach { Database.insert(it) }
                    .mapIndexed { position, mediaItem ->
                        SongAlbumMap(songId = mediaItem.mediaId, albumId = browseId, position = position)
                    }
            )
        }
    }

    /** "Save to library" is the album's bookmark; "Undo" puts the old one back. */
    fun setBookmark(album: Album, bookmarkedAt: Long?) = query {
        Database.update(album.copy(bookmarkedAt = bookmarkedAt))
    }
}
