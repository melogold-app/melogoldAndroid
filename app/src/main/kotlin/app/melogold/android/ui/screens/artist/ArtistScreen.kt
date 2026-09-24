package app.melogold.android.ui.screens.artist

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.media3.common.MediaItem
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.models.Artist
import app.melogold.android.models.Song
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.themed.Menu
import app.melogold.android.ui.components.themed.MenuEntry
import app.melogold.android.ui.components.themed.MenuHeader
import app.melogold.android.ui.components.themed.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.AboutSection
import app.melogold.android.ui.kit.ArtistAvatar
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.CollectionActions
import app.melogold.android.ui.kit.CollectionCard
import app.melogold.android.ui.kit.CollectionHeader
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.DetailBody
import app.melogold.android.ui.kit.DetailScaffold
import app.melogold.android.ui.kit.LoadableContent
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.kit.StaleChip
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.VideoCard
import app.melogold.android.ui.kit.detailTwoPane
import app.melogold.android.ui.kit.iconAction
import app.melogold.android.ui.kit.primaryAction
import app.melogold.android.ui.kit.toggleAction
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.model.valueOrNull
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistFavoritesRoute
import app.melogold.android.ui.screens.artistItemsRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.playWithRadio
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.NavigationEndpoint

private const val TOP_SONGS = 5
private const val LIBRARY_SONGS = 5
private val AvatarSize = 120.dp

/** An artist on one scroll (REWRITE §3.7.1, user decision 13). */
@Route
@Composable
fun ArtistScreen(browseId: String) = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("artist/$browseId") { ArtistModel(browseId) }
        val state by model.state.collectAsState()

        ArtistContent(
            browseId = browseId,
            state = state,
            onBack = pop,
            onRetry = model::load,
            onSetBookmark = model::setBookmark,
            onOpenAlbum = { albumRoute(it) },
            onOpenArtist = { artistRoute(it) },
            onOpenMore = { endpoint, title, subtitle ->
                val id = endpoint.browseId ?: return@ArtistContent
                // "Top songs" and "Videos" are playlists; the discography is a grid of its own
                if (id.startsWith("VL")) playlistRoute(id, endpoint.params, null, false)
                else artistItemsRoute(id, endpoint.params, title, subtitle)
            },
            onOpenFavorites = { name -> artistFavoritesRoute(browseId, name) }
        )
    }
}

@Composable
private fun ArtistContent(
    browseId: String,
    state: Loadable<ArtistDetails>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSetBookmark: (Artist, Long?) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenMore: (endpoint: NavigationEndpoint.Endpoint.Browse, title: String, subtitle: String?) -> Unit,
    onOpenFavorites: (name: String) -> Unit
) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val snackbar = LocalAppSnackbar.current
    val context = LocalContext.current
    val (playingId, _) = playingSong(binder)

    val details = state.valueOrNull
    val twoPane = detailTwoPane()
    val listState = rememberLazyListState()
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val showTitle by remember(twoPane) {
        derivedStateOf {
            !twoPane && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > titleBottom)
        }
    }

    val radioLabel = stringResource(R.string.artist_radio)
    val shareLabel = stringResource(R.string.menu_share)
    val subscribedMessage = stringResource(R.string.artist_subscribed_message)
    val unsubscribedMessage = stringResource(R.string.artist_unsubscribed_message)

    fun startRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) {
        binder?.stopRadio()
        binder?.playRadio(endpoint)
    }

    fun play(mediaItems: List<MediaItem>, index: Int) {
        binder?.stopRadio()
        binder?.player?.forcePlayAtIndex(mediaItems, index)
    }

    fun openMenu(details: ArtistDetails) = menuState.display {
        Menu {
            MenuHeader(
                title = details.artist.name.orEmpty(),
                subtitle = null,
                artworkUrl = details.artist.thumbnailUrl
            )
            details.page?.radioEndpoint?.let { endpoint ->
                MenuEntry(
                    icon = R.drawable.ms_sensors,
                    text = radioLabel,
                    onClick = {
                        menuState.hide()
                        startRadio(endpoint)
                    }
                )
            }
            MenuEntry(
                icon = R.drawable.ms_share,
                text = shareLabel,
                onClick = {
                    menuState.hide()
                    context.shareArtist(browseId)
                }
            )
        }
    }

    DetailScaffold(
        title = details?.artist?.name.orEmpty(),
        showTitle = showTitle,
        onBack = onBack,
        actions = {
            AnimatedVisibility(visible = showTitle && details != null, enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { details?.let(::openMenu) }) {
                    Icon(
                        painter = painterResource(R.drawable.ms_more_vert),
                        contentDescription = stringResource(R.string.kit_menu)
                    )
                }
            }
        },
        modifier = Modifier.testTag("artist")
    ) { padding ->
        LoadableContent(
            loadable = state,
            onRetry = onRetry,
            modifier = Modifier.padding(padding)
        ) { content ->
            val (artist, page, favorites) = content.value
            val name = artist.name ?: stringResource(R.string.unknown)

            DetailBody(
                twoPane = twoPane,
                padding = padding,
                listState = listState,
                header = { compact ->
                    CollectionHeader(
                        title = name,
                        artwork = { size ->
                            Artwork(url = artist.thumbnailUrl, size = min(size, AvatarSize), shape = CircleShape)
                        },
                        subtitle = page?.subscribersCountText?.let {
                            AnnotatedString(stringResource(R.string.format_subscribers, it))
                        },
                        status = content.staleSince?.let { since ->
                            { StaleChip(since = since, reason = content.staleReason, onClick = onRetry) }
                        },
                        compact = compact,
                        centered = true,
                        onTitleBottom = { titleBottom = it },
                        actions = {
                            val shuffleLabel = stringResource(R.string.collection_shuffle)
                            val subscribeLabel = stringResource(R.string.artist_subscribe)
                            val subscribedLabel = stringResource(R.string.artist_subscribed)
                            val moreLabel = stringResource(R.string.kit_menu)
                            val topSongs = page?.songs.orEmpty()

                            CollectionActions(compact = compact) {
                                primaryAction(
                                    icon = R.drawable.ms_shuffle,
                                    label = shuffleLabel,
                                    onClick = {
                                        page?.shuffleEndpoint?.let(::startRadio)
                                            ?: play(topSongs.map { it.asMediaItem }.shuffled(), 0)
                                    },
                                    enabled = page?.shuffleEndpoint != null || topSongs.isNotEmpty()
                                )
                                iconAction(
                                    icon = R.drawable.ms_sensors,
                                    label = radioLabel,
                                    onClick = { startRadio(page?.radioEndpoint) },
                                    enabled = page?.radioEndpoint != null,
                                    testTag = "action_radio"
                                )
                                toggleAction(
                                    checked = artist.bookmarkedAt != null,
                                    icon = R.drawable.ms_library_add,
                                    checkedIcon = R.drawable.ms_library_add_check,
                                    label = subscribeLabel,
                                    checkedLabel = subscribedLabel,
                                    onCheckedChange = { subscribe ->
                                        val previous = artist.bookmarkedAt
                                        onSetBookmark(artist, if (subscribe) System.currentTimeMillis() else null)
                                        if (subscribe) snackbar.show(subscribedMessage)
                                        else snackbar.showUndo(unsubscribedMessage) { onSetBookmark(artist, previous) }
                                    },
                                    testTag = "action_subscribe"
                                )
                                iconAction(
                                    icon = R.drawable.ms_more_vert,
                                    label = moreLabel,
                                    onClick = { openMenu(content.value) },
                                    testTag = "action_more"
                                )
                            }
                        }
                    )
                }
            ) {
                if (page == null && content.refreshing) item(key = "loading") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    ) {
                        DelayedLoadingIndicator()
                    }
                }

                if (page != null) pageSections(
                    page = page,
                    name = name,
                    playingId = playingId,
                    onPlay = ::play,
                    onVideo = { video -> binder?.playWithRadio(video.asMediaItem) },
                    onMenu = { mediaItem ->
                        menuState.display { NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = mediaItem) }
                    },
                    onOpenAlbum = onOpenAlbum,
                    onOpenMore = onOpenMore
                )

                if (favorites.isNotEmpty()) {
                    item(key = "library_title") {
                        SectionHeader(
                            title = stringResource(R.string.artist_in_library),
                            actionLabel = if (favorites.size > LIBRARY_SONGS)
                                stringResource(R.string.artist_all_count, favorites.size)
                            else null,
                            onAction = { onOpenFavorites(name) }
                        )
                    }
                    val shown = favorites.take(LIBRARY_SONGS)
                    itemsIndexed(items = shown, key = { _, song -> "library_${song.id}" }) { index, song ->
                        TrackRow(
                            title = song.title,
                            subtitle = song.artistsText,
                            artworkUrl = song.thumbnailUrl,
                            onClick = { play(favorites.map(Song::asMediaItem), index) },
                            onMenu = {
                                menuState.display {
                                    NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = song.asMediaItem)
                                }
                            },
                            isPlaying = song.id == playingId,
                            explicit = song.explicit,
                            duration = song.durationText,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                page?.relatedArtists?.takeIf { it.isNotEmpty() }?.let { artists ->
                    item(key = "related_title") { SectionHeader(title = stringResource(R.string.artist_related)) }
                    item(key = "related") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.testTag("related_artists")
                        ) {
                            items(items = artists, key = { it.key }) { related ->
                                ArtistAvatar(
                                    name = related.info?.name.orEmpty(),
                                    artworkUrl = related.thumbnail?.url,
                                    onClick = { onOpenArtist(related.key) }
                                )
                            }
                        }
                    }
                }

                page?.description?.takeIf { it.isNotBlank() }?.let { description ->
                    item(key = "about") {
                        AboutSection(
                            title = stringResource(R.string.artist_about),
                            text = description,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The sections only the network has: popular, albums, singles, videos. */
private fun LazyListScope.pageSections(
    page: Innertube.ArtistPage,
    name: String,
    playingId: String?,
    onPlay: (List<MediaItem>, Int) -> Unit,
    onVideo: (Innertube.VideoItem) -> Unit,
    onMenu: (MediaItem) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenMore: (NavigationEndpoint.Endpoint.Browse, String, String?) -> Unit
) {
    page.songs?.takeIf { it.isNotEmpty() }?.let { items ->
        val mediaItems = items.map { it.asMediaItem }

        item(key = "popular_title") {
            val title = stringResource(R.string.artist_popular)
            SectionHeader(
                title = title,
                actionLabel = page.songsEndpoint?.let { stringResource(R.string.artist_all) },
                onAction = page.songsEndpoint?.let { { onOpenMore(it, title, name) } }
            )
        }
        itemsIndexed(items = items.take(TOP_SONGS), key = { _, song -> "popular_${song.key}" }) { index, song ->
            TrackRow(
                title = song.info?.name.orEmpty(),
                subtitle = song.album?.name ?: song.authors?.joinToString("") { it.name.orEmpty() },
                artworkUrl = song.thumbnail?.url,
                number = index + 1,
                onClick = { onPlay(mediaItems, index) },
                onMenu = { onMenu(song.asMediaItem) },
                isPlaying = song.key == playingId,
                explicit = song.explicit,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }

    albumSection(
        key = "albums",
        title = R.string.artist_albums,
        albums = page.albums,
        more = page.albumsEndpoint,
        name = name,
        onOpenAlbum = onOpenAlbum,
        onOpenMore = onOpenMore
    )

    albumSection(
        key = "singles",
        title = R.string.artist_singles,
        albums = page.singles,
        more = page.singlesEndpoint,
        name = name,
        onOpenAlbum = onOpenAlbum,
        onOpenMore = onOpenMore
    )

    page.videos?.takeIf { it.isNotEmpty() }?.let { videos ->
        item(key = "videos_title") {
            val title = stringResource(R.string.artist_videos)
            SectionHeader(
                title = title,
                actionLabel = page.videosEndpoint?.let { stringResource(R.string.artist_all) },
                onAction = page.videosEndpoint?.let { { onOpenMore(it, title, name) } }
            )
        }
        item(key = "videos") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.testTag("artist_videos")
            ) {
                items(items = videos, key = { it.key }) { video ->
                    VideoCard(
                        title = video.info?.name.orEmpty(),
                        subtitle = video.viewsText,
                        thumbnailUrl = video.thumbnail?.url,
                        onClick = { onVideo(video) },
                        onLongClick = { onMenu(video.asMediaItem) }
                    )
                }
            }
        }
    }
}

private fun LazyListScope.albumSection(
    key: String,
    title: Int,
    albums: List<Innertube.AlbumItem>?,
    more: NavigationEndpoint.Endpoint.Browse?,
    name: String,
    onOpenAlbum: (String) -> Unit,
    onOpenMore: (NavigationEndpoint.Endpoint.Browse, String, String?) -> Unit
) {
    if (albums.isNullOrEmpty()) return

    item(key = "${key}_title") {
        val text = stringResource(title)
        SectionHeader(
            title = text,
            actionLabel = more?.let { stringResource(R.string.artist_all) },
            onAction = more?.let { { onOpenMore(it, text, name) } }
        )
    }
    item(key = key) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.testTag("artist_$key")
        ) {
            items(items = albums, key = { it.key }) { album ->
                CollectionCard(
                    title = album.info?.name.orEmpty(),
                    subtitle = album.year,
                    artworkUrl = album.thumbnail?.url,
                    onClick = { onOpenAlbum(album.key) },
                    size = 140.dp
                )
            }
        }
    }
}

private fun Context.shareArtist(browseId: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/channel/$browseId")
    }

    startActivity(Intent.createChooser(intent, null))
}
