package app.melogold.android.ui.screens.playlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.models.YtLinkMode
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.CollectionMenu
import app.melogold.android.ui.components.menu.Menu
import app.melogold.android.ui.components.menu.MenuEntry
import app.melogold.android.ui.components.menu.MenuHeader
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.CollectionActions
import app.melogold.android.ui.kit.CollectionHeader
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.DetailBody
import app.melogold.android.ui.kit.DetailScaffold
import app.melogold.android.ui.kit.HeaderArtwork
import app.melogold.android.ui.kit.LoadableContent
import app.melogold.android.ui.kit.SectionError
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.detailTwoPane
import app.melogold.android.ui.kit.iconAction
import app.melogold.android.ui.kit.primaryAction
import app.melogold.android.ui.kit.toggleAction
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.model.valueOrNull
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.localPlaylistRoute
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import app.melogold.providers.innertube.models.NavigationEndpoint

private const val LOAD_MORE_AHEAD = 10

/** The params of YouTube Music's "Start radio" on a playlist. */
private const val RADIO_PARAMS = "wAEB"

/** A YouTube playlist, also "All" of a chart (REWRITE §3.8.2). */
@Route
@Composable
fun PlaylistScreen(browseId: String, params: String?) = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("playlist/$browseId") { PlaylistModel(browseId, params, appScope) }
        val state by model.state.collectAsState()

        PlaylistContent(
            browseId = browseId,
            model = model,
            state = state,
            onBack = pop,
            onOpenSaved = { localPlaylistRoute(it) }
        )
    }
}

@Composable
private fun PlaylistContent(
    browseId: String,
    model: PlaylistModel,
    state: Loadable<PlaylistDetails>,
    onBack: () -> Unit,
    onOpenSaved: (Long) -> Unit
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

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.run { (visibleItemsInfo.lastOrNull()?.index ?: 0) to totalItemsCount } }
            .collect { (last, total) -> if (last >= total - LOAD_MORE_AHEAD) model.songs.loadMore() }
    }

    val radioLabel = stringResource(R.string.artist_radio)
    val savingMessage = stringResource(R.string.playlist_saving)
    val savedMessage = stringResource(R.string.playlist_saved)
    val openLabel = stringResource(R.string.playlist_open)
    val incompleteMessage = stringResource(R.string.playlist_incomplete)
    val failedMessage = stringResource(R.string.playlist_save_failed)
    val shareUrl = "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}"

    fun save(details: PlaylistDetails, mode: YtLinkMode) {
        snackbar.show(savingMessage)
        model.save(details.page.title.orEmpty(), mode) { result ->
            when (result) {
                is SaveResult.Saved -> snackbar.show(message = savedMessage, actionLabel = openLabel) {
                    onOpenSaved(result.playlistId)
                }
                SaveResult.Incomplete -> snackbar.show(incompleteMessage)
                is SaveResult.Failed -> snackbar.show(failedMessage)
            }
        }
    }

    fun openSaveSheet(details: PlaylistDetails) = menuState.display {
        SaveSheet(
            title = details.page.title.orEmpty(),
            artworkUrl = details.page.thumbnail?.url,
            onSave = { mode ->
                menuState.hide()
                save(details, mode)
            }
        )
    }

    fun openMenu(details: PlaylistDetails) = menuState.display {
        CollectionMenu(
            title = details.page.title.orEmpty(),
            subtitle = details.page.authors?.joinToString("") { it.name.orEmpty() },
            artworkUrl = details.page.thumbnail?.url,
            mediaItems = details.songs.items.map { it.asMediaItem },
            onDismiss = menuState::hide,
            radioLabel = radioLabel,
            onStartRadio = {
                binder?.stopRadio()
                binder?.playRadio(
                    NavigationEndpoint.Endpoint.Watch(
                        playlistId = "RDAMPL${browseId.removePrefix("VL")}",
                        params = RADIO_PARAMS
                    )
                )
            },
            shareUrl = details.page.url ?: shareUrl
        )
    }

    DetailScaffold(
        title = details?.page?.title.orEmpty(),
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
        modifier = Modifier.testTag("playlist")
    ) { padding ->
        LoadableContent(
            loadable = state,
            onRetry = model.songs::retry,
            modifier = Modifier.padding(padding)
        ) { content ->
            val (page, songs, saved) = content.value

            DetailBody(
                twoPane = twoPane,
                padding = padding,
                listState = listState,
                header = { compact ->
                    CollectionHeader(
                        title = page.title ?: stringResource(R.string.unknown),
                        artwork = { size ->
                            HeaderArtwork(
                                url = page.thumbnail?.url,
                                size = size,
                                shape = RoundedCornerShape(if (compact) 16.dp else 28.dp)
                            )
                        },
                        subtitle = listOfNotNull(
                            page.authors?.joinToString("") { it.name.orEmpty() }?.takeIf { it.isNotBlank() },
                            page.otherInfo?.replace(" • ", " · ")
                        ).joinToString(" · ").takeIf { it.isNotBlank() }?.let(::AnnotatedString),
                        status = if (!songs.end) {
                            {
                                Text(
                                    text = stringResource(R.string.playlist_loaded, songs.items.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else null,
                        compact = compact,
                        onTitleBottom = { titleBottom = it },
                        actions = {
                            val playLabel = stringResource(R.string.collection_play)
                            val shuffleLabel = stringResource(R.string.collection_shuffle)
                            val saveLabel = stringResource(R.string.detail_save)
                            val inLibraryLabel = stringResource(R.string.detail_saved)
                            val moreLabel = stringResource(R.string.kit_menu)
                            val hasSongs = songs.items.isNotEmpty()

                            CollectionActions(compact = compact) {
                                primaryAction(
                                    icon = R.drawable.ms_play_arrow_fill,
                                    label = playLabel,
                                    onClick = { binder?.let { model.play(it) } },
                                    enabled = hasSongs
                                )
                                iconAction(
                                    icon = R.drawable.ms_shuffle,
                                    label = shuffleLabel,
                                    onClick = { binder?.let { model.play(it, shuffle = true) } },
                                    enabled = hasSongs,
                                    testTag = "action_shuffle"
                                )
                                toggleAction(
                                    checked = saved != null,
                                    icon = R.drawable.ms_library_add,
                                    checkedIcon = R.drawable.ms_library_add_check,
                                    label = saveLabel,
                                    checkedLabel = inLibraryLabel,
                                    // Saved: the button opens the copy in the Library
                                    onCheckedChange = {
                                        if (saved != null) onOpenSaved(saved.id) else openSaveSheet(content.value)
                                    },
                                    enabled = hasSongs,
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
                itemsIndexed(items = songs.items, key = { _, song -> song.key }) { index, song ->
                    TrackRow(
                        title = song.info?.name.orEmpty(),
                        videoId = song.key,
                        subtitle = song.authors?.joinToString("") { it.name.orEmpty() },
                        artworkUrl = song.thumbnail?.url,
                        onClick = { binder?.let { model.play(it, index = index) } },
                        onMenu = {
                            menuState.display {
                                NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = song.asMediaItem)
                            }
                        },
                        isPlaying = song.key == playingId,
                        explicit = song.explicit,
                        duration = song.durationText,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                if (songs.loading) item(key = "loading") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    ) {
                        DelayedLoadingIndicator()
                    }
                }
                songs.error?.let { kind ->
                    item(key = "error") { SectionError(kind = kind, onRetry = model.songs::retry) }
                }
            }
        }
    }
}

/**
 * "Save to library" of a YouTube playlist (REWRITE §3.8.2): how the copy follows YouTube, with
 * what each choice means.
 */
@Composable
private fun SaveSheet(
    title: String,
    artworkUrl: String?,
    onSave: (YtLinkMode) -> Unit
) = Menu(modifier = Modifier.testTag("playlist_save")) {
    MenuHeader(title = stringResource(R.string.playlist_save_title), subtitle = title, artworkUrl = artworkUrl)
    MenuEntry(
        icon = R.drawable.ms_link,
        text = stringResource(R.string.playlist_link_append),
        secondaryText = stringResource(R.string.playlist_link_append_hint),
        onClick = { onSave(YtLinkMode.Append) }
    )
    MenuEntry(
        icon = R.drawable.ms_sync,
        text = stringResource(R.string.playlist_link_mirror),
        secondaryText = stringResource(R.string.playlist_link_mirror_hint),
        onClick = { onSave(YtLinkMode.Mirror) }
    )
    MenuEntry(
        icon = R.drawable.ms_content_copy,
        text = stringResource(R.string.playlist_link_off),
        secondaryText = stringResource(R.string.playlist_link_off_hint),
        onClick = { onSave(YtLinkMode.Off) }
    )
}
