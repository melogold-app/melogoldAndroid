package app.melogold.android.ui.screens.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.models.Album
import app.melogold.android.models.Info
import app.melogold.android.models.Song
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.CollectionMenu
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.AboutSection
import app.melogold.android.ui.kit.CollectionActions
import app.melogold.android.ui.kit.CollectionCard
import app.melogold.android.ui.kit.CollectionHeader
import app.melogold.android.ui.kit.DetailBody
import app.melogold.android.ui.kit.DetailScaffold
import app.melogold.android.ui.kit.HeaderArtwork
import app.melogold.android.ui.kit.LoadableContent
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.kit.StaleChip
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.detailTwoPane
import app.melogold.android.ui.kit.formatListeningTime
import app.melogold.android.ui.kit.iconAction
import app.melogold.android.ui.kit.parseDuration
import app.melogold.android.ui.kit.primaryAction
import app.melogold.android.ui.kit.toggleAction
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.model.valueOrNull
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import app.melogold.providers.innertube.models.NavigationEndpoint

/** The params of YouTube Music's "Start radio" on a playlist. */
private const val RADIO_PARAMS = "wAEB"

/** An album (REWRITE §3.6). */
@Route
@Composable
fun AlbumScreen(browseId: String) = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("album/$browseId") { AlbumModel(browseId) }
        val state by model.state.collectAsState()

        AlbumContent(
            state = state,
            onBack = pop,
            onRetry = model::load,
            onSetBookmark = model::setBookmark,
            onOpenAlbum = { albumRoute(it) },
            onOpenArtist = { artistRoute(it) }
        )
    }
}

@Composable
private fun AlbumContent(
    state: Loadable<AlbumDetails>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSetBookmark: (Album, Long?) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit
) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val snackbar = LocalAppSnackbar.current
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

    val radioLabel = stringResource(R.string.album_radio)
    val savedMessage = stringResource(R.string.detail_saved_message)
    val removedMessage = stringResource(R.string.detail_removed_message)

    fun play(songs: List<Song>, index: Int) {
        binder?.stopRadio()
        binder?.player?.forcePlayAtIndex(songs.map(Song::asMediaItem), index)
    }

    fun openMenu(details: AlbumDetails) = menuState.display {
        CollectionMenu(
            title = details.album.title.orEmpty(),
            subtitle = details.album.authorsText,
            artworkUrl = details.album.thumbnailUrl,
            mediaItems = details.songs.map(Song::asMediaItem),
            onDismiss = menuState::hide,
            radioLabel = radioLabel,
            onStartRadio = { binder?.startAlbumRadio(details) },
            artists = details.credits.mapNotNull { credit -> credit.id?.let { Info(it, credit.name) } },
            shareUrl = details.album.shareUrl
        )
    }

    DetailScaffold(
        title = details?.album?.title.orEmpty(),
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
        modifier = Modifier.testTag("album")
    ) { padding ->
        LoadableContent(
            loadable = state,
            onRetry = onRetry,
            modifier = Modifier.padding(padding)
        ) { content ->
            val (album, songs, credits, related, relatedTitle) = content.value

            DetailBody(
                twoPane = twoPane,
                padding = padding,
                listState = listState,
                header = { compact ->
                    CollectionHeader(
                        title = album.title ?: stringResource(R.string.unknown),
                        artwork = { size ->
                            HeaderArtwork(
                                url = album.thumbnailUrl,
                                size = size,
                                shape = RoundedCornerShape(if (compact) 16.dp else 28.dp)
                            )
                        },
                        compact = compact,
                        subtitle = albumSubtitle(album, songs, credits, onOpenArtist),
                        status = content.staleSince?.let { since ->
                            { StaleChip(since = since, reason = content.staleReason, onClick = onRetry) }
                        },
                        onTitleBottom = { titleBottom = it },
                        actions = {
                            val playLabel = stringResource(R.string.collection_play)
                            val shuffleLabel = stringResource(R.string.collection_shuffle)
                            val saveLabel = stringResource(R.string.detail_save)
                            val savedLabel = stringResource(R.string.detail_saved)
                            val moreLabel = stringResource(R.string.kit_menu)

                            CollectionActions(compact = compact) {
                                primaryAction(
                                    icon = R.drawable.ms_play_arrow_fill,
                                    label = playLabel,
                                    onClick = { play(songs, 0) }
                                )
                                iconAction(
                                    icon = R.drawable.ms_shuffle,
                                    label = shuffleLabel,
                                    onClick = { play(songs.shuffled(), 0) },
                                    testTag = "action_shuffle"
                                )
                                toggleAction(
                                    checked = album.bookmarkedAt != null,
                                    icon = R.drawable.ms_library_add,
                                    checkedIcon = R.drawable.ms_library_add_check,
                                    label = saveLabel,
                                    checkedLabel = savedLabel,
                                    onCheckedChange = { save ->
                                        val previous = album.bookmarkedAt
                                        onSetBookmark(album, if (save) System.currentTimeMillis() else null)
                                        if (save) snackbar.show(savedMessage)
                                        else snackbar.showUndo(removedMessage) { onSetBookmark(album, previous) }
                                    },
                                    testTag = "action_save"
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
                itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
                    TrackRow(
                        title = song.title,
                        // The album's artists go without saying; features don't
                        subtitle = song.artistsText?.takeIf { it != album.authorsText },
                        artworkUrl = null,
                        showArtwork = false,
                        number = index + 1,
                        onClick = { play(songs, index) },
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

                if (related.isNotEmpty()) {
                    item(key = "related_title") {
                        SectionHeader(title = relatedTitle ?: stringResource(R.string.other_versions))
                    }
                    item(key = "related") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.testTag("related_albums")
                        ) {
                            items(items = related, key = { it.key }) { version ->
                                CollectionCard(
                                    title = version.info?.name.orEmpty(),
                                    subtitle = version.year ?: version.authors?.joinToString("") { it.name.orEmpty() },
                                    artworkUrl = version.thumbnail?.url,
                                    onClick = { onOpenAlbum(version.key) },
                                    size = 140.dp
                                )
                            }
                        }
                    }
                }

                album.description?.takeIf { it.isNotBlank() }?.let { description ->
                    item(key = "about") {
                        AboutSection(
                            title = stringResource(R.string.album_about),
                            text = description,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** "Artist · 2024 · 12 tracks · 48 min", the artists linking to their pages. */
@Composable
private fun albumSubtitle(
    album: Album,
    songs: List<Song>,
    credits: List<Credit>,
    onOpenArtist: (String) -> Unit
): AnnotatedString {
    val count = pluralStringResource(R.plurals.library_tracks_count, songs.size, songs.size)
    val total = songs.sumOf { parseDuration(it.durationText) ?: 0L }
    val time = if (total > 0) formatListeningTime(total) else null
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    )

    return buildAnnotatedString {
        credits.forEach { credit ->
            val id = credit.id
            if (id == null) append(credit.name)
            else withLink(LinkAnnotation.Clickable(tag = id, styles = linkStyles) { onOpenArtist(id) }) {
                append(credit.name)
            }
        }
        listOfNotNull(album.year, count, time).forEach { part ->
            if (length > 0) append(" · ")
            append(part)
        }
    }
}

/**
 * The album's radio: YouTube Music's radio of its playlist, or of its first track when the
 * playlist is unknown.
 */
private fun PlayerService.Binder.startAlbumRadio(details: AlbumDetails) {
    val playlistId = details.album.shareUrl?.toUri()?.getQueryParameter("list")

    stopRadio()
    playRadio(
        if (playlistId != null) NavigationEndpoint.Endpoint.Watch(playlistId = "RDAMPL$playlistId", params = RADIO_PARAMS)
        else NavigationEndpoint.Endpoint.Watch(videoId = details.songs.firstOrNull()?.id)
    )
}
