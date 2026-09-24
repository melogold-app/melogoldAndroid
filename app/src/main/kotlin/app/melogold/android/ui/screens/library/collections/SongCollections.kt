package app.melogold.android.ui.screens.library.collections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.melogold.android.Database
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.preferences.ListSort
import app.melogold.android.preferences.SortPreferences
import app.melogold.android.preferences.toListSort
import app.melogold.android.models.Song
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.CollectionFilterField
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.PlayShuffleButtons
import app.melogold.android.ui.kit.SortChip
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.formatListeningTime
import app.melogold.android.ui.kit.parseDuration
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import app.melogold.core.data.enums.SongSortBy
import app.melogold.core.data.enums.SortOrder
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val KEEP_WHILE_HIDDEN_MS = 5_000L

/** How a list of tracks is ordered (REWRITE §3.2.2): the date means liked or cached. */
enum class TrackSort(@param:StringRes val label: Int) {
    DateAdded(R.string.sort_date_added),
    Title(R.string.sort_title),
    Artist(R.string.sort_artist),
    Duration(R.string.sort_duration)
}

/** What a list of tracks is sorted by until the user picks something else. */
private val DefaultTrackSort = ListSort(TrackSort.DateAdded, descending = true)

/** Favorites, newest like first; the screen sorts and filters it. */
class FavoritesModel : ScreenModel() {
    val songs: StateFlow<List<Song>?> = Database
        .favorites(SongSortBy.DateAdded, SortOrder.Descending)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)
}

/** Tracks the cache holds in full, newest first. */
class CachedModel(binder: PlayerService.Binder?) : ScreenModel() {
    val songs: StateFlow<List<Song>?> = Database
        .songsWithContentLength(SongSortBy.DateAdded, SortOrder.Descending)
        .map { songs -> songs.filter { binder?.isCached(it) == true }.map { it.song } }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)
}

/** Favorites (REWRITE §3.2.2). */
@Route
@Composable
fun FavoritesScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("library/favorites") { FavoritesModel() }
        val songs by model.songs.collectAsState()

        SongCollection(
            title = stringResource(R.string.library_favorites),
            songs = songs,
            empty = R.string.favorites_empty,
            sort = SortPreferences.favorites.toListSort(DefaultTrackSort),
            onSort = { SortPreferences.favorites = it.encode() },
            onBack = pop
        )
    }
}

/** An artist's tracks in Favorites: "In your library › All" of the artist (REWRITE §3.7.1). */
class ArtistFavoritesModel(artistId: String) : ScreenModel() {
    val songs: StateFlow<List<Song>?> = Database
        .artistFavorites(artistId)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)
}

@Route
@Composable
fun ArtistFavoritesScreen(artistId: String, name: String) = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("artist_favorites/$artistId") { ArtistFavoritesModel(artistId) }
        val songs by model.songs.collectAsState()

        SongCollection(
            title = name,
            songs = songs,
            empty = R.string.favorites_empty,
            sort = SortPreferences.favorites.toListSort(DefaultTrackSort),
            onSort = { SortPreferences.favorites = it.encode() },
            note = R.string.artist_in_library_note,
            onBack = pop
        )
    }
}

/**
 * Tracks from the cache, the "Downloads" of the Library until real downloads (REDESIGN §2.3):
 * the subtitle says the system may delete them.
 */
@Route
@Composable
fun CachedScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val binder = LocalPlayerServiceBinder.current
        val model = rememberScreenModel("library/cached") { CachedModel(binder) }
        val songs by model.songs.collectAsState()

        SongCollection(
            title = stringResource(R.string.library_downloads),
            songs = songs,
            empty = R.string.cached_empty,
            sort = SortPreferences.downloads.toListSort(DefaultTrackSort),
            onSort = { SortPreferences.downloads = it.encode() },
            note = R.string.cached_subtitle,
            onBack = pop
        )
    }
}

/**
 * A collection of tracks: "N tracks · time" under the title, Play · Shuffle, the sort chip (its
 * choice kept between launches by the caller), a filter behind the search icon, and the rows; a
 * tap plays the list from that track.
 */
@Composable
private fun SongCollection(
    title: String,
    songs: List<Song>?,
    @StringRes empty: Int,
    sort: ListSort<TrackSort>,
    onSort: (ListSort<TrackSort>) -> Unit,
    onBack: () -> Unit,
    @StringRes note: Int? = null
) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val nav = LocalMainNav.current
    val (playingId, _) = playingSong(binder)

    var filtering by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }

    val shown = remember(songs, sort, filter) {
        songs?.let { list -> list.sortedAs(sort.field, sort.descending).filteredBy(filter) }
    }
    val subtitle = songs?.takeIf { it.isNotEmpty() }?.let { list ->
        val count = pluralStringResource(R.plurals.library_tracks_count, list.size, list.size)
        val total = list.sumOf { parseDuration(it.durationText) ?: 0L }
        if (total > 0) "$count · ${formatListeningTime(total)}" else count
    }

    fun play(list: List<Song>, index: Int) {
        binder?.stopRadio()
        binder?.player?.forcePlayAtIndex(list.map { it.asMediaItem }, index)
    }

    CollectionScaffold(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        actions = {
            if (!songs.isNullOrEmpty()) IconButton(onClick = { filtering = !filtering; if (!filtering) filter = "" }) {
                Icon(painter = painterResource(R.drawable.ms_search), contentDescription = stringResource(R.string.collection_filter))
            }
        }
    ) { padding ->
        when {
            shown == null -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                DelayedLoadingIndicator()
            }

            songs.isNullOrEmpty() -> EmptyCollection(
                text = empty,
                onFindMusic = { nav.openSearch() },
                modifier = Modifier.padding(padding)
            )

            else -> LazyColumn(contentPadding = padding, modifier = Modifier.testTag("collection_list")) {
                if (filtering) item(key = "filter") {
                    CollectionFilterField(
                        value = filter,
                        onValueChange = { filter = it },
                        onClose = {
                            filtering = false
                            filter = ""
                        }
                    )
                }

                item(key = "actions") {
                    PlayShuffleButtons(
                        onPlay = { play(shown, 0) },
                        onShuffle = { play(shown.shuffled(), 0) },
                        enabled = shown.isNotEmpty()
                    )
                }

                note?.let {
                    item(key = "note") {
                        Text(
                            text = stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }

                item(key = "sort") {
                    SortChip(
                        options = persistentListOf(*TrackSort.entries.toTypedArray()),
                        selected = sort.field,
                        descending = sort.descending,
                        label = { stringResource(it.label) },
                        onSelect = { option, down -> onSort(ListSort(option, down)) },
                        startsDescending = { it == TrackSort.DateAdded },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                if (shown.isEmpty()) item(key = "no_match") {
                    Text(
                        text = stringResource(R.string.collection_no_match, filter),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }

                itemsIndexed(items = shown, key = { _, song -> song.id }) { index, song ->
                    TrackRow(
                        title = song.title,
                        subtitle = song.artistsText,
                        artworkUrl = song.thumbnailUrl,
                        onClick = { play(shown, index) },
                        onMenu = {
                            menuState.display {
                                NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = song.asMediaItem)
                            }
                        },
                        isPlaying = song.id == playingId,
                        explicit = song.explicit,
                        duration = song.durationText,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .animateItem()
                    )
                }
            }
        }
    }
}

/** An empty collection: what fills it, and "Find music". */
@Composable
internal fun EmptyCollection(
    @StringRes text: Int,
    onFindMusic: (() -> Unit)?,
    modifier: Modifier = Modifier
) = Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    modifier = modifier
        .fillMaxSize()
        .padding(32.dp)
        .testTag("collection_empty")
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    onFindMusic?.let {
        FilledTonalButton(onClick = it) { Text(text = stringResource(R.string.library_find_music)) }
    }
}

private fun List<Song>.sortedAs(sort: TrackSort, descending: Boolean): List<Song> {
    // The source lists come newest first
    val ascending = when (sort) {
        TrackSort.DateAdded -> asReversed()
        TrackSort.Title -> sortedBy { it.title.lowercase() }
        TrackSort.Artist -> sortedBy { it.artistsText.orEmpty().lowercase() }
        TrackSort.Duration -> sortedBy { parseDuration(it.durationText) ?: 0L }
    }
    return if (descending) ascending.asReversed() else ascending
}

private fun List<Song>.filteredBy(filter: String): List<Song> {
    val query = filter.trim()
    if (query.isEmpty()) return this
    return filter { it.title.contains(query, ignoreCase = true) || it.artistsText.orEmpty().contains(query, ignoreCase = true) }
}
