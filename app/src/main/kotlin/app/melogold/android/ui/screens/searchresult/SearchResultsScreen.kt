package app.melogold.android.ui.screens.searchresult

import app.melogold.android.ui.shell.SearchSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.m3e.ConnectedToggleGroup
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.ErrorState
import app.melogold.android.ui.kit.SectionError
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.VideoThumbnail
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.Paged
import app.melogold.android.ui.model.PagedLoader
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.playWithRadio
import app.melogold.android.utils.thumbnail
import app.melogold.compose.persist.PersistMapCleanup
import app.melogold.compose.routing.RouteHandler
import app.melogold.compose.routing.RouteHandlerScope
import app.melogold.core.ui.utils.px
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.youtube.YouTubeItem
import app.melogold.providers.innertube.youtube.YouTubeSearchFilter
import coil3.compose.AsyncImage
import kotlinx.collections.immutable.toImmutableList

private const val LOAD_MORE_AHEAD = 5

/**
 * `searchResultRoute` (REWRITE §3.1.3, M3 search guidelines): the query stays visible on top —
 * a tap goes back to editing it; "All · Music · YouTube" and the chips of a segment narrow the
 * results. Plain YouTube is first-class: re-uploads and covers the catalog doesn't have.
 */
@Route
@Composable
fun SearchResultsScreen(
    query: String,
    initialSource: SearchSource,
    onEditQuery: () -> Unit
) {
    PersistMapCleanup(prefix = "searchResults/$query/")

    RouteHandler {
        GlobalRoutes()

        Content {
            val model = rememberScreenModel("searchResults/$query/model") { SearchResultsModel(query, initialSource) }
            val source by model.source.collectAsState()
            val insets = LocalPlayerAwareWindowInsets.current
            val contentPadding = insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal).asPaddingValues()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                QueryBar(
                    query = query,
                    onBack = pop,
                    onEdit = onEditQuery,
                    modifier = Modifier.windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                )

                ConnectedToggleGroup(
                    options = SearchSource.entries.toImmutableList(),
                    selected = source,
                    onSelect = { model.source.value = it },
                    label = {
                        stringResource(
                            when (it) {
                                SearchSource.All -> R.string.results_all
                                SearchSource.Music -> R.string.results_music
                                SearchSource.YouTube -> R.string.results_youtube
                            }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (source) {
                        SearchSource.All -> AllResultsList(
                            model = model,
                            contentPadding = contentPadding,
                            onMoreMusic = { model.source.value = SearchSource.Music },
                            onMoreYouTube = { model.source.value = SearchSource.YouTube }
                        )

                        SearchSource.Music -> MusicResults(
                            model = model,
                            contentPadding = contentPadding,
                            onSearchYouTube = { model.source.value = SearchSource.YouTube }
                        )

                        SearchSource.YouTube -> YouTubeResults(model = model, contentPadding = contentPadding)
                    }
                }
            }
        }
    }
}

/**
 * The query, collapsed into a search bar: visible but not focused (M3 search guidelines).
 */
@Composable
private fun QueryBar(
    query: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) = Row(
    modifier = modifier
        .fillMaxWidth()
        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    IconButton(onClick = onBack) {
        Icon(
            painter = painterResource(R.drawable.ms_arrow_back),
            contentDescription = stringResource(R.string.kit_back)
        )
    }
    Surface(
        onClick = onEdit,
        shape = SearchBarDefaults.inputFieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .weight(1f)
            .height(SearchBarDefaults.InputFieldHeight)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ms_search),
                contentDescription = stringResource(R.string.results_edit_query),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = query,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RouteHandlerScope.AllResultsList(
    model: SearchResultsModel,
    contentPadding: PaddingValues,
    onMoreMusic: () -> Unit,
    onMoreYouTube: () -> Unit
) {
    val all by model.all.collectAsState()

    when (val state = all) {
        Loadable.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DelayedLoadingIndicator()
        }

        is Loadable.Error -> ErrorState(kind = state.kind, onRetry = model::loadAll, modifier = Modifier.fillMaxSize())

        is Loadable.Content -> {
            val results = state.value

            if (results.isEmpty) NothingFound(onSearchYouTube = onMoreYouTube)
            else LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                // Nothing in the catalog: YouTube goes first, with a word why
                if (results.musicEmpty) {
                    item(key = "ytm/none") { Note(text = stringResource(R.string.results_nothing_in_catalog)) }
                    youTubeSection(results, routes = this@AllResultsList, onMore = onMoreYouTube, onRetry = model::loadAll)
                } else {
                    musicSection(results, routes = this@AllResultsList, onMore = onMoreMusic, onRetry = model::loadAll)
                    youTubeSection(results, routes = this@AllResultsList, onMore = onMoreYouTube, onRetry = model::loadAll)
                }
            }
        }
    }
}

private fun LazyListScope.musicSection(
    results: AllResults,
    routes: RouteHandlerScope,
    onMore: () -> Unit,
    onRetry: () -> Unit
) {
    item(key = "ytm/header") {
        SectionHeader(
            title = stringResource(R.string.results_ytm),
            actionLabel = stringResource(R.string.results_more),
            onAction = onMore
        )
    }
    results.musicError?.let { kind ->
        item(key = "ytm/error") { SectionError(kind = kind, onRetry = onRetry) }
    }
    results.artist?.let { artist ->
        item(key = "ytm/artist") { with(routes) { MusicItemRow(item = artist) } }
    }
    items(items = results.songs, key = { "ytm/${it.key}" }) { song ->
        with(routes) { MusicItemRow(item = song) }
    }
}

private fun LazyListScope.youTubeSection(
    results: AllResults,
    routes: RouteHandlerScope,
    onMore: () -> Unit,
    onRetry: () -> Unit
) {
    item(key = "yt/header") {
        SectionHeader(
            title = stringResource(R.string.results_youtube),
            actionLabel = stringResource(R.string.results_more),
            onAction = onMore
        )
    }
    results.youTubeError?.let { kind ->
        item(key = "yt/error") { SectionError(kind = kind, onRetry = onRetry) }
    }
    items(items = results.videos, key = { "yt/${it.key}" }) { video ->
        with(routes) { YouTubeItemRow(item = video) }
    }
}

@Composable
private fun RouteHandlerScope.MusicResults(
    model: SearchResultsModel,
    contentPadding: PaddingValues,
    onSearchYouTube: () -> Unit
) {
    val filter by model.musicFilter.collectAsState()
    val loader = model.music(filter)
    val state by loader.state.collectAsState()

    Column {
        ChipsRow(
            options = MusicFilter.entries,
            selected = filter,
            onSelect = { model.musicFilter.value = it },
            label = {
                when (it) {
                    MusicFilter.Songs -> R.string.results_songs
                    MusicFilter.Albums -> R.string.results_albums
                    MusicFilter.Artists -> R.string.results_artists
                    MusicFilter.Videos -> R.string.results_music_videos
                    MusicFilter.Playlists -> R.string.results_playlists
                }
            }
        )
        PagedList(
            state = state,
            loader = loader,
            contentPadding = contentPadding,
            key = { "music/${filter.name}/${it.key}" },
            footer = if (filter == MusicFilter.Songs) {
                {
                    TextButton(
                        onClick = onSearchYouTube,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(text = stringResource(R.string.results_not_here))
                        Icon(
                            painter = painterResource(R.drawable.ms_chevron_right),
                            contentDescription = null
                        )
                    }
                }
            } else null
        ) { item -> MusicItemRow(item = item) }
    }
}

@Composable
private fun RouteHandlerScope.YouTubeResults(
    model: SearchResultsModel,
    contentPadding: PaddingValues
) {
    val filter by model.youTubeFilter.collectAsState()
    val loader = model.youTube(filter)
    val state by loader.state.collectAsState()

    Column {
        ChipsRow(
            options = YouTubeSearchFilter.entries,
            selected = filter,
            onSelect = { model.youTubeFilter.value = it },
            label = {
                when (it) {
                    YouTubeSearchFilter.Videos -> R.string.results_videos
                    YouTubeSearchFilter.Channels -> R.string.results_channels
                    YouTubeSearchFilter.Live -> R.string.results_live
                    YouTubeSearchFilter.Playlists -> R.string.results_playlists
                }
            }
        )
        PagedList(
            state = state,
            loader = loader,
            contentPadding = contentPadding,
            key = { "youtube/${filter.name}/${it.key}" }
        ) { item -> YouTubeItemRow(item = item) }
    }
}

/**
 * Single-choice filter chips of a segment.
 */
@Composable
private fun <T> ChipsRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> Int
) = LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
) {
    items(items = options, key = { label(it) }) { option ->
        val checked = option == selected
        FilterChip(
            selected = checked,
            onClick = { onSelect(option) },
            label = { Text(text = stringResource(label(option))) },
            leadingIcon = if (checked) {
                {
                    Icon(
                        painter = painterResource(R.drawable.ms_check),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else null
        )
    }
}

/**
 * An endless list: the next page loads a few rows before the end; a failed page shows its error
 * with Retry below what is loaded.
 */
@Composable
private fun <T> PagedList(
    state: Paged<T>,
    loader: PagedLoader<T>,
    contentPadding: PaddingValues,
    key: (T) -> String,
    footer: (@Composable () -> Unit)? = null,
    itemContent: @Composable (T) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState, loader, state.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.items.size - LOAD_MORE_AHEAD) loader.loadMore() }
    }

    if (state.items.isEmpty() && state.error != null) {
        ErrorState(kind = state.error, onRetry = loader::retry, modifier = Modifier.fillMaxSize())
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = state.items, key = key) { item -> itemContent(item) }

        when {
            state.loading -> item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (state.items.isEmpty()) 240.dp else 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    DelayedLoadingIndicator()
                }
            }

            state.error != null -> item(key = "error") { SectionError(kind = state.error, onRetry = loader::retry) }

            state.end && state.items.isEmpty() -> item(key = "empty") {
                Note(text = stringResource(R.string.results_nothing))
            }

            state.end && footer != null -> item(key = "footer") { footer() }
        }
    }
}

/**
 * A result from YouTube Music: song, album, artist, music video or playlist.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RouteHandlerScope.MusicItemRow(item: Innertube.Item) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current

    when (item) {
        is Innertube.SongItem -> TrackRow(
            title = item.info?.name.orEmpty(),
            subtitle = item.authors?.joinToString { it.name.orEmpty() },
            artworkUrl = item.thumbnail?.url,
            explicit = item.explicit,
            duration = item.durationText,
            onClick = { binder?.playWithRadio(item.asMediaItem) },
            onMenu = {
                menuState.display { NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = item.asMediaItem) }
            },
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        is Innertube.AlbumItem -> ResultRow(
            title = item.info?.name.orEmpty(),
            subtitle = subtitle(
                stringResource(R.string.results_album),
                item.authors?.joinToString { it.name.orEmpty() },
                item.year
            ),
            leading = { Artwork(url = item.thumbnail?.url, size = 56.dp) },
            onClick = { albumRoute(item.key) }
        )

        is Innertube.ArtistItem -> ResultRow(
            title = item.info?.name.orEmpty(),
            subtitle = subtitle(stringResource(R.string.results_artist), item.subscribersCountText),
            leading = { Artwork(url = item.thumbnail?.url, size = 56.dp, shape = CircleShape) },
            onClick = { artistRoute(item.key) }
        )

        is Innertube.VideoItem -> ResultRow(
            title = item.info?.name.orEmpty(),
            subtitle = subtitle(
                stringResource(R.string.results_music_video),
                item.authors?.joinToString { it.name.orEmpty() },
                item.viewsText
            ),
            leading = { VideoThumbnail(url = item.thumbnail?.url, badge = item.durationText) },
            onClick = { binder?.playWithRadio(item.asMediaItem) },
            onLongClick = {
                menuState.display { NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = item.asMediaItem) }
            }
        )

        is Innertube.PlaylistItem -> ResultRow(
            title = item.info?.name.orEmpty(),
            subtitle = subtitle(stringResource(R.string.results_playlist), item.channel?.name),
            leading = { Artwork(url = item.thumbnail?.url, size = 56.dp) },
            onClick = { playlistRoute(item.key, null, null, item.channel?.name == "YouTube Music") }
        )

        else -> Unit
    }
}

/**
 * A result from plain YouTube: video (16:9 preview), channel or playlist.
 */
@Composable
private fun RouteHandlerScope.YouTubeItemRow(item: YouTubeItem) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current

    when (item) {
        is YouTubeItem.Video -> ResultRow(
            title = item.title,
            subtitle = subtitle(item.channelName, item.viewsText, item.publishedText),
            titleLines = 2,
            leading = {
                VideoThumbnail(
                    url = item.thumbnailUrl,
                    badge = item.liveLabel ?: item.durationText,
                    live = item.isLive
                )
            },
            onClick = { binder?.playWithRadio(item.asMediaItem) },
            onLongClick = {
                menuState.display { NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = item.asMediaItem) }
            }
        )

        is YouTubeItem.Channel -> ResultRow(
            title = item.name,
            subtitle = item.subtitle,
            leading = { Artwork(url = item.thumbnailUrl, size = 56.dp, shape = CircleShape) },
            onClick = { artistRoute(item.channelId) }
        )

        is YouTubeItem.Playlist -> ResultRow(
            title = item.title,
            subtitle = subtitle(item.channelName, item.videoCountText),
            titleLines = 2,
            leading = { VideoThumbnail(url = item.thumbnailUrl, badge = item.videoCountText) },
            onClick = { playlistRoute("VL${item.playlistId}", null, null, false) }
        )
    }
}

private fun subtitle(vararg parts: String?) = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    titleLines: Int = 1
) = Row(
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
    leading()
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Note(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
)

@Composable
private fun NothingFound(onSearchYouTube: () -> Unit) = Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
) {
    Text(
        text = stringResource(R.string.results_nothing),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )
    Button(onClick = onSearchYouTube) { Text(text = stringResource(R.string.results_search_youtube)) }
}
