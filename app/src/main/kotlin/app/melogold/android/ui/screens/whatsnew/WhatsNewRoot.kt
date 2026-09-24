package app.melogold.android.ui.screens.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.foryou.ForYou
import app.melogold.android.ui.kit.ArtistAvatar
import app.melogold.android.ui.kit.CollectionCard
import app.melogold.android.ui.kit.LoadableSection
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.kit.StaleChip
import app.melogold.android.ui.kit.TrackGrid
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.isRefreshing
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.model.valueOrNull
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.moreAlbumsRoute
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.TabRootScaffold
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.thumbnail
import app.melogold.core.ui.utils.px
import app.melogold.compose.routing.RouteHandlerScope
import app.melogold.providers.innertube.Innertube
import coil3.compose.AsyncImage

private val ReleaseSize = 200.dp
private val TrackRowHeight = 72.dp
private const val FOR_YOU_ROWS = 4
private const val CAPTION_FROM = 0.8f

/**
 * The root of New (REWRITE §3.4): new albums and singles, then the personal picks built from the
 * history — tracks, similar artists, albums and playlists.
 */
@Route
@Composable
fun RouteHandlerScope.WhatsNewRoot() {
    val model = rememberScreenModel("whatsnew/model") { WhatsNewModel(catalog, forYou) }
    val releases by model.releasesState.collectAsState()
    val picks by model.picksState.collectAsState()
    val nav = LocalMainNav.current
    val listState = rememberLazyListState()

    TabRootScaffold(
        title = stringResource(R.string.nav_whats_new),
        onScrollToTop = { listState.animateScrollToItem(0) },
        isRefreshing = releases.isRefreshing || picks.isRefreshing,
        onRefresh = model::refresh
    ) { contentPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val trackWidth = min(maxWidth - 48.dp, 420.dp)
            val forYou = picks.valueOrNull
            val artists = forYou?.artists.orEmpty()
            val albums = forYou?.albums.orEmpty()
            val playlists = forYou?.playlists.orEmpty()

            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize()
            ) {
                // Never zero-height: see TrendsRoot
                item(key = "top") { Spacer(modifier = Modifier.height(4.dp)) }

                item(key = "releases/header") {
                    SectionHeader(
                        title = stringResource(R.string.whatsnew_new_releases),
                        actionLabel = stringResource(R.string.kit_show_all),
                        onAction = { moreAlbumsRoute() }
                    )
                }
                item(key = "releases") {
                    LoadableSection(
                        loadable = releases,
                        onRetry = model::refreshReleases,
                        loadingHeight = ReleaseSize
                    ) { content ->
                        Column {
                            Stale(content = content, onRetry = model::refreshReleases)
                            ReleasesCarousel(
                                albums = content.value.newReleaseAlbums,
                                onAlbumClick = { browseId -> albumRoute(browseId) }
                            )
                        }
                    }
                }

                item(key = "picks/header") {
                    ForYouHeader(forYou = forYou)
                }
                item(key = "picks") {
                    LoadableSection(
                        loadable = picks,
                        onRetry = model::refreshPicks,
                        loadingHeight = TrackRowHeight * FOR_YOU_ROWS
                    ) { content ->
                        Column {
                            Stale(content = content, onRetry = model::refreshPicks)
                            if (content.value.hasHistory) TrackGrid(
                                songs = content.value.songs,
                                itemWidth = trackWidth,
                                rows = FOR_YOU_ROWS.coerceAtMost(content.value.songs.size.coerceAtLeast(1))
                            ) else NoHistoryCard(onTrendsClick = { nav.select(TopLevelDestination.Trends) })
                        }
                    }
                }

                if (artists.isNotEmpty()) {
                    item(key = "artists/header") {
                        SectionHeader(title = stringResource(R.string.whatsnew_similar_artists))
                    }
                    item(key = "artists") {
                        CollectionRow(items = artists) { artist ->
                            ArtistAvatar(
                                name = artist.info?.name.orEmpty(),
                                artworkUrl = artist.thumbnail?.url,
                                onClick = { artistRoute(artist.key) }
                            )
                        }
                    }
                }

                if (albums.isNotEmpty()) {
                    item(key = "albums/header") {
                        SectionHeader(title = stringResource(R.string.whatsnew_similar_albums))
                    }
                    item(key = "albums") {
                        CollectionRow(items = albums) { album ->
                            CollectionCard(
                                title = album.info?.name.orEmpty(),
                                subtitle = album.authors?.joinToString { it.name.orEmpty() },
                                artworkUrl = album.thumbnail?.url,
                                onClick = { albumRoute(album.key) }
                            )
                        }
                    }
                }

                if (playlists.isNotEmpty()) {
                    item(key = "playlists/header") {
                        SectionHeader(title = stringResource(R.string.whatsnew_playlists_for_you))
                    }
                    item(key = "playlists") {
                        CollectionRow(items = playlists) { playlist ->
                            CollectionCard(
                                title = playlist.info?.name.orEmpty(),
                                subtitle = playlist.channel?.name,
                                artworkUrl = playlist.thumbnail?.url,
                                onClick = {
                                    playlistRoute(
                                        p0 = playlist.key,
                                        p1 = null,
                                        p2 = null,
                                        p3 = playlist.channel?.name == "YouTube Music"
                                    )
                                }
                            )
                        }
                    }
                }

                item(key = "bottom") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun Stale(
    content: Loadable.Content<*>,
    onRetry: () -> Unit
) {
    val since = content.staleSince ?: return
    StaleChip(
        since = since,
        reason = content.staleReason,
        onClick = onRetry,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/**
 * "For you" with the tracks the picks are based on and "Play all".
 */
@Composable
private fun ForYouHeader(forYou: ForYou?) {
    val binder = LocalPlayerServiceBinder.current
    val songs = forYou?.songs.orEmpty()
    val playAll: @Composable () -> Unit = {
        FilledTonalButton(
            onClick = {
                binder?.stopRadio()
                binder?.player?.forcePlayAtIndex(songs.map { it.asMediaItem }, 0)
            },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding
        ) {
            Icon(
                painter = painterResource(R.drawable.ms_play_arrow_fill),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(text = stringResource(R.string.whatsnew_play_all))
        }
    }

    SectionHeader(
        title = stringResource(R.string.whatsnew_for_you),
        subtitle = forYou
            ?.seeds
            ?.takeIf { it.isNotEmpty() }
            ?.let { seeds -> stringResource(R.string.whatsnew_based_on, seeds.joinToString { it.title }) },
        trailing = playAll.takeIf { songs.isNotEmpty() }
    )
}

/**
 * New albums and singles: a multi-browse carousel, the caption only on the large item.
 */
@Composable
private fun ReleasesCarousel(
    albums: List<Innertube.AlbumItem>,
    onAlbumClick: (browseId: String) -> Unit
) {
    val items = albums.filter { it.info?.endpoint?.browseId != null }
    val state = rememberCarouselState { items.size }
    val sizePx = ReleaseSize.px

    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = ReleaseSize,
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(ReleaseSize)
    ) { index ->
        val album = items[index]
        val info = carouselItemDrawInfo

        Box(
            modifier = Modifier
                .fillMaxSize()
                .maskClip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { onAlbumClick(album.key) }
        ) {
            AsyncImage(
                model = album.thumbnail?.url?.thumbnail(sizePx),
                contentDescription = album.info?.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .graphicsLayer {
                        // Read while drawing: the caption fades in over the last fifth of the item's
                        // growth, so only the large item shows it; no recomposition while scrolling
                        val range = info.maxSize - info.minSize
                        val grown = if (range <= 0f) 1f else (info.size - info.minSize) / range
                        alpha = ((grown - CAPTION_FROM) / (1f - CAPTION_FROM)).coerceIn(0f, 1f)
                    }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 14.dp)
            ) {
                Text(
                    text = album.info?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        album.authors?.joinToString { it.name.orEmpty() }?.takeIf { it.isNotBlank() },
                        album.year
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * A row of albums, playlists or artists. A snapping row rather than an uncontained carousel: the
 * cards have titles under the covers, and a carousel needs a fixed height that large fonts cut.
 */
@Composable
private fun <T : Innertube.Item> CollectionRow(
    items: List<T>,
    itemContent: @Composable (T) -> Unit
) {
    val rowState = rememberLazyListState()

    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = rowState, snapPosition = SnapPosition.Start),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items = items, key = { it.key }) { item -> itemContent(item) }
    }
}

@Composable
private fun NoHistoryCard(onTrendsClick: () -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    shape = RoundedCornerShape(24.dp),
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.whatsnew_no_history),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(
            onClick = onTrendsClick,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(text = stringResource(R.string.whatsnew_whats_trending))
        }
    }
}
