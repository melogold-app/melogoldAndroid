package app.melogold.android.ui.screens.localplaylist

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.repo.RefreshResult
import app.melogold.android.models.Playlist
import app.melogold.android.models.Song
import app.melogold.android.models.YtLinkMode
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.themed.Menu
import app.melogold.android.ui.components.themed.MenuDivider
import app.melogold.android.ui.components.themed.MenuEntry
import app.melogold.android.ui.components.themed.MenuHeader
import app.melogold.android.ui.components.themed.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.CollectionActions
import app.melogold.android.ui.kit.CollectionFilterField
import app.melogold.android.ui.kit.CollectionHeader
import app.melogold.android.ui.kit.DetailBody
import app.melogold.android.ui.kit.DetailScaffold
import app.melogold.android.ui.kit.MosaicArtwork
import app.melogold.android.ui.kit.SortChip
import app.melogold.android.ui.kit.TextInputDialog
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.detailTwoPane
import app.melogold.android.ui.kit.formatListeningTime
import app.melogold.android.ui.kit.iconAction
import app.melogold.android.ui.kit.parseDuration
import app.melogold.android.ui.kit.primaryAction
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.library.collections.EmptyCollection
import app.melogold.android.ui.shell.AppSnackbar
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** How the tracks of an own playlist are ordered (REWRITE §3.8.1). */
enum class PlaylistSort(@param:StringRes val label: Int) {
    Custom(R.string.local_playlist_sort_custom),
    Title(R.string.sort_title),
    Artist(R.string.sort_artist),
    DateAdded(R.string.sort_date_added)
}

/** An own playlist (REWRITE §3.8.1). */
@Route
@Composable
fun LocalPlaylistScreen(playlistId: Long) = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("local_playlist/$playlistId") { LocalPlaylistModel(playlistId, appScope) }
        val playlist by model.playlist.collectAsState()

        playlist?.let {
            LocalPlaylistContent(playlist = it, model = model, onBack = pop)
        }
    }
}

@Composable
private fun LocalPlaylistContent(
    playlist: Playlist,
    model: LocalPlaylistModel,
    onBack: () -> Unit
) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val snackbar = LocalAppSnackbar.current
    val nav = LocalMainNav.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val (playingId, _) = playingSong(binder)

    val ownOrder by model.songs.collectAsState()
    val byDateAdded by model.songsByDateAdded.collectAsState()
    val covers by model.covers.collectAsState()
    val refreshing by model.refreshing.collectAsState()

    var sort by rememberSaveable { mutableStateOf(PlaylistSort.Custom) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var renaming by rememberSaveable { mutableStateOf(false) }

    val showRefreshResult = refreshMessages()
    val removedMessage = stringResource(R.string.local_playlist_removed_track)
    val deletedMessage = stringResource(R.string.local_playlist_deleted)

    LaunchedEffect(playlist.id) { model.refreshIfStale { result -> showRefreshResult(result, true, snackbar) } }

    val songs = ownOrder
    val shown = remember(ownOrder, byDateAdded, sort, descending, filter) {
        val source = (if (sort == PlaylistSort.DateAdded) byDateAdded else ownOrder).orEmpty()
        source.sortedAs(sort, descending).filteredBy(filter)
    }
    // Dragging reorders the own order only, and not while a filter hides tracks
    val canReorder = sort == PlaylistSort.Custom && !descending && filter.isBlank()

    // The list as it is dragged; the new order is written when the finger lifts
    var ordered by remember(shown) { mutableStateOf(shown) }
    var dragFrom by remember { mutableIntStateOf(-1) }

    val twoPane = detailTwoPane()
    val listState = rememberLazyListState()
    var titleBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val showTitle by remember(twoPane) {
        derivedStateOf {
            !twoPane && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > titleBottom)
        }
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = ordered.indexOfFirst { it.id == from.key }
        val toIndex = ordered.indexOfFirst { it.id == to.key }
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState

        ordered = ordered.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    fun play(list: List<Song>, index: Int) {
        binder?.stopRadio()
        binder?.player?.forcePlayAtIndex(list.map(Song::asMediaItem), index)
    }

    fun delete() = coroutineScope.launch {
        val deleted = model.delete() ?: return@launch
        onBack()
        snackbar.showUndo(deletedMessage) { LocalPlaylistModel.restore(deleted) }
    }

    fun openLinkSheet() = menuState.display {
        LinkSheet(
            playlist = playlist,
            onSelect = { mode ->
                menuState.hide()
                model.setLinkMode(mode)
                if (mode != YtLinkMode.Off) model.refresh { showRefreshResult(it, false, snackbar) }
            }
        )
    }

    fun openMenu() = menuState.display {
        Menu {
            MenuHeader(title = playlist.name, subtitle = null, artworkUrl = covers.firstOrNull() ?: playlist.thumbnail)
            MenuEntry(
                icon = R.drawable.ms_edit,
                text = stringResource(R.string.local_playlist_rename),
                onClick = {
                    menuState.hide()
                    renaming = true
                }
            )
            if (playlist.browseId != null) {
                MenuEntry(
                    icon = R.drawable.ms_link,
                    text = stringResource(R.string.local_playlist_link),
                    secondaryText = stringResource(playlist.ytLinkMode.label),
                    onClick = ::openLinkSheet
                )
                if (playlist.isLinked) MenuEntry(
                    icon = R.drawable.ms_refresh,
                    text = stringResource(R.string.local_playlist_refresh),
                    onClick = {
                        menuState.hide()
                        model.refresh { showRefreshResult(it, false, snackbar) }
                    },
                    enabled = !refreshing
                )
                MenuEntry(
                    icon = R.drawable.ms_share,
                    text = stringResource(R.string.menu_share),
                    onClick = {
                        menuState.hide()
                        context.sharePlaylist(playlist.browseId)
                    }
                )
            }
            MenuDivider()
            MenuEntry(
                icon = R.drawable.ms_delete,
                text = stringResource(R.string.local_playlist_delete),
                onClick = {
                    menuState.hide()
                    delete()
                }
            )
        }
    }

    if (renaming) TextInputDialog(
        title = stringResource(R.string.local_playlist_rename),
        label = stringResource(R.string.playlist_name),
        confirmLabel = stringResource(R.string.local_playlist_rename_confirm),
        initialValue = playlist.name,
        onDismiss = { renaming = false },
        onConfirm = { name ->
            renaming = false
            model.rename(name)
        }
    )

    DetailScaffold(
        title = playlist.name,
        showTitle = showTitle,
        onBack = onBack,
        actions = {
            if (!songs.isNullOrEmpty()) IconButton(
                onClick = {
                    filtering = !filtering
                    if (!filtering) filter = ""
                }
            ) {
                Icon(painter = painterResource(R.drawable.ms_search), contentDescription = stringResource(R.string.collection_filter))
            }
            AnimatedVisibility(visible = showTitle, enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = ::openMenu) {
                    Icon(painter = painterResource(R.drawable.ms_more_vert), contentDescription = stringResource(R.string.kit_menu))
                }
            }
        },
        modifier = Modifier.testTag("local_playlist")
    ) { padding ->
        DetailBody(
            twoPane = twoPane,
            padding = padding,
            listState = listState,
            header = { compact ->
                val count = songs.orEmpty().size
                val total = songs.orEmpty().sumOf { parseDuration(it.durationText) ?: 0L }
                val countText = pluralStringResource(R.plurals.library_tracks_count, count, count)

                CollectionHeader(
                    title = playlist.name,
                    artwork = { size ->
                        MosaicArtwork(
                            urls = covers.ifEmpty { listOfNotNull(playlist.thumbnail) },
                            size = size,
                            shape = RoundedCornerShape(if (compact) 16.dp else 28.dp)
                        )
                    },
                    subtitle = AnnotatedString(if (total > 0) "$countText · ${formatListeningTime(total)}" else countText),
                    status = if (playlist.isLinked || refreshing) {
                        { LinkStatus(playlist = playlist, refreshing = refreshing, onClick = ::openLinkSheet) }
                    } else null,
                    compact = compact,
                    onTitleBottom = { titleBottom = it },
                    actions = {
                        val playLabel = stringResource(R.string.collection_play)
                        val shuffleLabel = stringResource(R.string.collection_shuffle)
                        val moreLabel = stringResource(R.string.kit_menu)
                        val hasSongs = !songs.isNullOrEmpty()

                        CollectionActions(compact = compact) {
                            primaryAction(
                                icon = R.drawable.ms_play_arrow_fill,
                                label = playLabel,
                                onClick = { play(shown, 0) },
                                enabled = hasSongs
                            )
                            iconAction(
                                icon = R.drawable.ms_shuffle,
                                label = shuffleLabel,
                                onClick = { play(shown.shuffled(), 0) },
                                enabled = hasSongs,
                                testTag = "action_shuffle"
                            )
                            iconAction(
                                icon = R.drawable.ms_more_vert,
                                label = moreLabel,
                                onClick = ::openMenu,
                                testTag = "action_more"
                            )
                        }
                    }
                )
            }
        ) {
            if (songs != null && songs.isEmpty()) {
                item(key = "empty") {
                    EmptyCollection(
                        text = R.string.local_playlist_empty,
                        onFindMusic = { nav.openSearch() },
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                return@DetailBody
            }

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

            item(key = "sort") {
                SortChip(
                    options = persistentListOf(*PlaylistSort.entries.toTypedArray()),
                    selected = sort,
                    descending = descending,
                    label = { stringResource(it.label) },
                    onSelect = { option, down ->
                        sort = option
                        descending = option != PlaylistSort.Custom && down
                    },
                    hasDirection = { it != PlaylistSort.Custom },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            val rows = if (canReorder) ordered else shown
            itemsIndexed(items = rows, key = { _, song -> song.id }) { index, song ->
                ReorderableItem(state = reorderState, key = song.id, enabled = canReorder) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "elevation")
                    val position = ownOrder.orEmpty().indexOfFirst { it.id == song.id }

                    Surface(
                        shadowElevation = elevation,
                        color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        TrackRow(
                            title = song.title,
                            subtitle = song.artistsText,
                            artworkUrl = song.thumbnailUrl,
                            onClick = { play(rows, index) },
                            onMenu = {
                                menuState.display {
                                    NonQueuedMediaItemMenu(
                                        onDismiss = menuState::hide,
                                        mediaItem = song.asMediaItem,
                                        onRemoveFromPlaylist = if (position >= 0) {
                                            {
                                                val undo = model.remove(song, position)
                                                snackbar.showUndo(removedMessage, undo)
                                            }
                                        } else null
                                    )
                                }
                            },
                            isPlaying = song.id == playingId,
                            explicit = song.explicit,
                            duration = song.durationText,
                            leading = if (canReorder) {
                                {
                                    IconButton(
                                        onClick = { },
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = {
                                                dragFrom = ownOrder.orEmpty().indexOfFirst { it.id == song.id }
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                val to = ordered.indexOfFirst { it.id == song.id }
                                                if (dragFrom >= 0 && to >= 0) model.move(dragFrom, to)
                                                dragFrom = -1
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            }
                                        )
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ms_drag_handle),
                                            contentDescription = stringResource(R.string.queue_reorder)
                                        )
                                    }
                                }
                            } else null,
                            accessibilityActions = if (canReorder && position >= 0) listOfNotNull(
                                CustomAccessibilityAction(stringResource(R.string.local_playlist_move_up)) {
                                    if (position > 0) model.move(position, position - 1)
                                    true
                                }.takeIf { position > 0 },
                                CustomAccessibilityAction(stringResource(R.string.local_playlist_move_down)) {
                                    model.move(position, position + 1)
                                    true
                                }.takeIf { position < rows.lastIndex }
                            ) else emptyList()
                        )
                    }
                }
            }
        }
    }
}

/** "⛓ YouTube · Add new tracks · refreshed 2 h ago", or the refresh in progress. */
@Composable
private fun LinkStatus(
    playlist: Playlist,
    refreshing: Boolean,
    onClick: () -> Unit
) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val justNow = stringResource(R.string.local_playlist_synced_now)
    val synced = playlist.ytSyncedAt?.let { time ->
        val now = System.currentTimeMillis()
        if (now - time < DateUtils.MINUTE_IN_MILLIS) justNow
        else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ms_link),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        TextButton(onClick = onClick) {
            Text(
                text = if (refreshing) stringResource(R.string.local_playlist_refreshing)
                else listOfNotNull(
                    "YouTube",
                    stringResource(playlist.ytLinkMode.label),
                    synced?.let { stringResource(R.string.local_playlist_synced, it) }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
    if (refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

/** "Link with YouTube" (§3.8.1): what each mode does, the current one checked. */
@Composable
private fun LinkSheet(
    playlist: Playlist,
    onSelect: (YtLinkMode) -> Unit
) = Menu(modifier = Modifier.testTag("playlist_link")) {
    MenuHeader(title = stringResource(R.string.local_playlist_link), subtitle = playlist.name, artworkUrl = playlist.thumbnail)
    listOf(
        LinkChoice(YtLinkMode.Append, R.drawable.ms_link, R.string.local_playlist_mode_append, R.string.playlist_link_append_hint),
        LinkChoice(YtLinkMode.Mirror, R.drawable.ms_sync, R.string.local_playlist_mode_mirror, R.string.playlist_link_mirror_hint),
        LinkChoice(YtLinkMode.Off, R.drawable.ms_link_off, R.string.local_playlist_unlink, R.string.local_playlist_unlink_hint)
    ).forEach { (mode, icon, text, hint) ->
        MenuEntry(
            icon = icon,
            text = stringResource(text),
            secondaryText = stringResource(hint),
            onClick = { onSelect(mode) },
            trailingContent = if (playlist.ytLinkMode == mode) {
                { Icon(painter = painterResource(R.drawable.ms_check), contentDescription = null) }
            } else null
        )
    }
}

private data class LinkChoice(
    val mode: YtLinkMode,
    @param:DrawableRes val icon: Int,
    @param:StringRes val text: Int,
    @param:StringRes val hint: Int
)

private val Playlist.isLinked get() = ytLinkMode == YtLinkMode.Mirror || ytLinkMode == YtLinkMode.Append

@get:StringRes
private val YtLinkMode?.label
    get() = when (this) {
        YtLinkMode.Mirror -> R.string.local_playlist_mode_mirror
        YtLinkMode.Append -> R.string.local_playlist_mode_append
        YtLinkMode.Off, null -> R.string.local_playlist_mode_off
    }

/** The snackbars of a refresh; a quiet (automatic) one speaks only when it changed something. */
@Composable
private fun refreshMessages(): (RefreshResult, Boolean, AppSnackbar) -> Unit {
    val incomplete = stringResource(R.string.local_playlist_incomplete)
    val snapshot = stringResource(R.string.local_playlist_snapshot)
    val failed = stringResource(R.string.local_playlist_refresh_failed)
    val unchanged = stringResource(R.string.local_playlist_up_to_date)
    val resources = LocalContext.current.resources

    return { result, quiet, snackbar ->
        val message = when (result) {
            is RefreshResult.Updated -> when {
                result.added > 0 -> resources.getQuantityString(R.plurals.local_playlist_added, result.added, result.added)
                result.removed > 0 -> resources.getQuantityString(R.plurals.local_playlist_mirrored, result.removed, result.removed)
                quiet -> null
                else -> unchanged
            }
            RefreshResult.SnapshotTaken -> snapshot
            RefreshResult.Incomplete -> incomplete.takeUnless { quiet }
            is RefreshResult.Failed -> failed.takeUnless { quiet }
        }
        message?.let { snackbar.show(it) }
    }
}

private fun List<Song>.sortedAs(sort: PlaylistSort, descending: Boolean): List<Song> = when (sort) {
    PlaylistSort.Custom -> this
    // Its source list is newest first
    PlaylistSort.DateAdded -> if (descending) this else asReversed()
    PlaylistSort.Title -> sortedBy { it.title.lowercase() }.let { if (descending) it.asReversed() else it }
    PlaylistSort.Artist -> sortedBy { it.artistsText.orEmpty().lowercase() }.let { if (descending) it.asReversed() else it }
}

private fun List<Song>.filteredBy(filter: String): List<Song> {
    val query = filter.trim()
    if (query.isEmpty()) return this
    return filter { it.title.contains(query, ignoreCase = true) || it.artistsText.orEmpty().contains(query, ignoreCase = true) }
}

private fun Context.sharePlaylist(browseId: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}")
    }

    startActivity(Intent.createChooser(intent, null))
}
