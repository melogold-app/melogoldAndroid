@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Playlist
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.service.PlayerService
import app.melogold.android.transaction
import app.melogold.android.ui.components.BottomSheet
import app.melogold.android.ui.components.BottomSheetState
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.MusicBars
import app.melogold.android.ui.components.themed.AddToPlaylistMenu
import app.melogold.android.ui.components.themed.MediaItemMenuHeader
import app.melogold.android.ui.components.themed.Menu
import app.melogold.android.ui.components.themed.MenuEntry
import app.melogold.android.ui.components.themed.MenuSectionTitle
import app.melogold.android.ui.components.themed.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.NewPlaylistDialog
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.shell.AppSnackbar
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.enqueue
import app.melogold.android.utils.shouldBePlaying
import app.melogold.android.utils.shuffleQueue
import app.melogold.android.utils.windows
import app.melogold.compose.persist.persist
import app.melogold.core.ui.utils.songBundle
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.NextBody
import app.melogold.providers.innertube.requests.nextPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * One track of the queue, keyed by its timeline window (a track can be in the queue twice); the
 * key is an Int as lazy list keys must fit in a Bundle.
 */
@Immutable
private data class QueueEntry(val key: Int, val mediaItem: MediaItem)

private val Timeline.Window.key get() = uid.hashCode()

private val Timeline.entries get() = windows.map { QueueEntry(key = it.key, mediaItem = it.mediaItem) }

/**
 * The queue sheet of the player (REWRITE §3.10.4): "Up next · N tracks · time", Shuffle, Save as
 * playlist and Clear, the tracks with a drag handle (the playing one highlighted), a swipe that
 * removes a track with "Undo", and similar tracks to add at the end.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun Queue(
    layoutState: BottomSheetState,
    binder: PlayerService.Binder,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.systemBars
) {
    val player = binder.player
    val menuState = LocalMenuState.current
    val snackbar = LocalAppSnackbar.current
    val resources = LocalContext.current.resources
    val haptic = LocalHapticFeedback.current

    var entries by remember { mutableStateOf(player.currentTimeline.entries) }
    var currentIndex by remember { mutableIntStateOf(player.currentMediaItemIndex) }
    var shouldBePlaying by remember { mutableStateOf(player.shouldBePlaying) }

    // While a row is dragged the list follows the finger; the player gets the move at the end
    var dragged by remember { mutableStateOf<Int?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }

    player.DisposableListener {
        object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (dragged == null) entries = timeline.entries
                currentIndex = player.currentMediaItemIndex
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldBePlaying = player.shouldBePlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                shouldBePlaying = player.shouldBePlaying
            }
        }
    }

    val currentKey = entries.getOrNull(currentIndex)?.key
    val currentMediaId = entries.getOrNull(currentIndex)?.mediaItem?.mediaId
    val open = !layoutState.collapsed

    // Similar tracks of the playing one, fetched once the sheet is open
    var similar by persist<List<MediaItem>?>(tag = "queue/suggestions/$currentMediaId")
    LaunchedEffect(currentMediaId, open) {
        if (!open || currentMediaId == null || similar != null) return@LaunchedEffect
        similar = withContext(Dispatchers.IO) {
            Innertube.nextPage(NextBody(videoId = currentMediaId))
                ?.getOrNull()
                ?.itemsPage
                ?.items
                ?.map { it.asMediaItem }
        }
    }
    val queuedIds = remember(entries) { entries.mapTo(HashSet()) { it.mediaItem.mediaId } }
    val visibleSimilar = similar.orEmpty().filter { it.mediaId !in queuedIds }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = entries.indexOfFirst { it.key == from.key }
        val toIndex = entries.indexOfFirst { it.key == to.key }
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState

        entries = entries.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LaunchedEffect(open) {
        if (open) listState.scrollToItem(currentIndex.coerceAtLeast(0))
    }

    fun indexOf(key: Int) = player.currentTimeline.windows.indexOfFirst { it.key == key }

    fun remove(entry: QueueEntry) {
        val index = indexOf(entry.key).takeIf { it >= 0 } ?: return
        player.removeMediaItem(index)
        snackbar.showUndo(resources.getString(R.string.queue_removed)) {
            player.addMediaItem(index.coerceAtMost(player.mediaItemCount), entry.mediaItem)
        }
    }

    fun move(entry: QueueEntry, by: Int) {
        val index = indexOf(entry.key).takeIf { it >= 0 } ?: return
        val target = (index + by).coerceIn(0, player.mediaItemCount - 1)
        if (target != index) player.moveMediaItem(index, target)
    }

    var saving by rememberSaveable { mutableStateOf(false) }
    if (saving) NewPlaylistDialog(
        onDismiss = { saving = false },
        onCreate = { name ->
            saving = false
            saveAsPlaylist(name, player.currentTimeline.windows.map { it.mediaItem })
            snackbar.show(resources.getString(R.string.queue_saved, name))
        }
    )

    BottomSheet(
        state = layoutState,
        modifier = modifier.fillMaxSize(),
        collapsedContent = { }
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("queue")
        ) {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))

                QueueHeader(
                    upcoming = entries.drop(currentIndex + 1).map { it.mediaItem },
                    canClear = entries.size > 1,
                    onClear = { clearQueue(player, snackbar, resources) },
                    onCollapse = layoutState::collapseSoft
                )

                QueueActions(
                    enabled = entries.size > 1,
                    onShuffle = player::shuffleQueue,
                    onSave = { saving = true }
                )

                LazyColumn(
                    state = listState,
                    contentPadding = windowInsets
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                        .asPaddingValues(),
                    modifier = Modifier
                        .weight(1f)
                        .nestedScroll(layoutState.preUpPostDownNestedScrollConnection)
                ) {
                    items(items = entries, key = { it.key }) { entry ->
                        val isCurrent = entry.key == currentKey

                        ReorderableItem(state = reorderState, key = entry.key) { isDragging ->
                            val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "")
                            val dismissState = rememberSwipeToDismissBoxState()

                            SwipeToDismissBox(
                                state = dismissState,
                                gesturesEnabled = !isCurrent && dragged == null,
                                backgroundContent = { RemoveBackground() },
                                onDismiss = { remove(entry) }
                            ) {
                                QueueRow(
                                    mediaItem = entry.mediaItem,
                                    isCurrent = isCurrent,
                                    playing = shouldBePlaying,
                                    elevation = elevation,
                                    handle = {
                                        IconButton(
                                            onClick = { },
                                            modifier = Modifier.draggableHandle(
                                                onDragStarted = {
                                                    dragged = entry.key
                                                    dragFrom = indexOf(entry.key)
                                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                                },
                                                onDragStopped = {
                                                    val to = entries.indexOfFirst { it.key == entry.key }
                                                    if (dragFrom >= 0 && to >= 0 && to != dragFrom) {
                                                        player.moveMediaItem(dragFrom, to)
                                                    }
                                                    dragged = null
                                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                }
                                            )
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ms_drag_handle),
                                                contentDescription = stringResource(R.string.queue_reorder)
                                            )
                                        }
                                    },
                                    onClick = {
                                        val index = indexOf(entry.key)
                                        when {
                                            isCurrent -> if (shouldBePlaying) player.pause() else player.play()
                                            index >= 0 -> {
                                                player.seekToDefaultPosition(index)
                                                player.playWhenReady = true
                                            }
                                        }
                                    },
                                    onMenu = {
                                        menuState.display {
                                            QueueItemMenu(
                                                mediaItem = entry.mediaItem,
                                                isCurrent = isCurrent,
                                                onDismiss = menuState::hide,
                                                onPlayNext = {
                                                    val index = indexOf(entry.key)
                                                    val next = player.currentMediaItemIndex + 1
                                                    if (index >= 0) player.moveMediaItem(
                                                        index,
                                                        if (index < next) next - 1 else next
                                                    )
                                                },
                                                onRemove = { remove(entry) }
                                            )
                                        }
                                    },
                                    onMoveUp = { move(entry, -1) },
                                    onMoveDown = { move(entry, 1) },
                                    onRemove = if (isCurrent) null else ({ remove(entry) })
                                )
                            }
                        }
                    }

                    if (visibleSimilar.isNotEmpty()) {
                        item(key = "similar", contentType = "title") {
                            MenuSectionTitle(
                                text = stringResource(R.string.queue_similar),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        items(
                            items = visibleSimilar,
                            key = { "similar_${it.mediaId}" },
                            contentType = { "similar" }
                        ) { mediaItem ->
                            SimilarRow(
                                mediaItem = mediaItem,
                                onAdd = { player.enqueue(mediaItem) },
                                onClick = {
                                    menuState.display {
                                        NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = mediaItem)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Up next" with how many tracks and how long they play, "Clear" (GLOSSARY: in the header) and ⌄
 * to hide the sheet.
 */
@Composable
private fun QueueHeader(
    upcoming: List<MediaItem>,
    canClear: Boolean,
    onClear: () -> Unit,
    onCollapse: () -> Unit
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = 24.dp, end = 12.dp)
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = stringResource(R.string.queue_up_next),
            style = MaterialTheme.typography.titleLarge
        )

        val count = pluralStringResource(R.plurals.queue_tracks, upcoming.size, upcoming.size)
        val minutes = upcoming.sumOf { it.durationMs() ?: 0L } / 60_000
        val duration = when {
            minutes <= 0 -> null
            minutes >= 60 -> stringResource(R.string.queue_duration_hours, minutes / 60, minutes % 60)
            else -> stringResource(R.string.queue_duration_minutes, minutes)
        }
        Text(
            text = listOfNotNull(count, duration).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    TextButton(onClick = onClear, enabled = canClear) {
        Text(text = stringResource(R.string.queue_clear))
    }
    IconButton(onClick = onCollapse) {
        Icon(
            painter = painterResource(R.drawable.ms_keyboard_arrow_down),
            contentDescription = stringResource(R.string.queue_collapse)
        )
    }
}

/** Shuffle and Save as playlist as assist chips. */
@Composable
private fun QueueActions(
    enabled: Boolean,
    onShuffle: () -> Unit,
    onSave: () -> Unit
) = Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp)
) {
    @Composable
    fun Action(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) = AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(text = stringResource(label)) },
        leadingIcon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        }
    )

    Action(icon = R.drawable.ms_shuffle, label = R.string.queue_shuffle, onClick = onShuffle)
    Action(icon = R.drawable.ms_playlist_add, label = R.string.queue_save_as_playlist, onClick = onSave)
}

/**
 * A track of the queue: handle, cover, title and artist, and ⋮ — or, for the playing track, a
 * highlight and music bars. TalkBack gets "Move up", "Move down" and "Remove from queue".
 */
@Composable
private fun QueueRow(
    mediaItem: MediaItem,
    isCurrent: Boolean,
    playing: Boolean,
    elevation: Dp,
    handle: @Composable () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: (() -> Unit)?
) {
    val metadata = mediaItem.mediaMetadata
    val nowPlaying = stringResource(R.string.queue_now_playing)
    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)
    val removeLabel = stringResource(R.string.menu_remove_from_queue)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = elevation,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        ListItem(
            selected = isCurrent,
            onClick = onClick,
            onLongClick = onMenu,
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    handle()
                    Artwork(
                        url = metadata.artworkUri?.toString(),
                        size = 48.dp,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            supportingContent = {
                Text(
                    text = metadata.artist?.toString().orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = {
                if (isCurrent) Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp)
                ) {
                    // Paused: a tap on the row plays it again
                    if (playing) MusicBars(
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.height(20.dp)
                    ) else Icon(
                        painter = painterResource(R.drawable.ms_play_arrow),
                        contentDescription = null
                    )
                } else IconButton(onClick = onMenu) {
                    Icon(
                        painter = painterResource(R.drawable.ms_more_vert),
                        contentDescription = stringResource(R.string.more_options)
                    )
                }
            },
            shapes = ListItemDefaults.shapes(
                shape = RoundedCornerShape(16.dp),
                selectedShape = RoundedCornerShape(16.dp)
            ),
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            contentPadding = PaddingValues(start = 0.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            modifier = Modifier.semantics {
                if (isCurrent) stateDescription = nowPlaying
                customActions = listOfNotNull(
                    CustomAccessibilityAction(moveUp) { onMoveUp(); true },
                    CustomAccessibilityAction(moveDown) { onMoveDown(); true },
                    onRemove?.let { CustomAccessibilityAction(removeLabel) { it(); true } }
                )
            }
        ) {
            Text(
                text = metadata.title?.toString().orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Behind a row being swiped away. */
@Composable
private fun RemoveBackground() = Box(
    contentAlignment = Alignment.CenterEnd,
    modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 8.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
        .padding(horizontal = 24.dp)
) {
    Icon(
        painter = painterResource(R.drawable.ms_playlist_remove),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** A similar track: tap for its menu, "+" adds it to the end of the queue. */
@Composable
private fun SimilarRow(
    mediaItem: MediaItem,
    onAdd: () -> Unit,
    onClick: () -> Unit
) = ListItem(
    onClick = onClick,
    leadingContent = {
        Artwork(
            url = mediaItem.mediaMetadata.artworkUri?.toString(),
            size = 48.dp,
            shape = RoundedCornerShape(8.dp)
        )
    },
    supportingContent = {
        Text(
            text = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    },
    trailingContent = {
        IconButton(onClick = onAdd) {
            Icon(
                painter = painterResource(R.drawable.ms_add),
                contentDescription = stringResource(R.string.menu_add_to_queue)
            )
        }
    },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = Modifier.padding(horizontal = 8.dp)
) {
    Text(
        text = mediaItem.mediaMetadata.title?.toString().orEmpty(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * The short menu of a queued track (REWRITE §3.10.4): Play next, Remove from queue, Add to
 * playlist, Go to album / artist.
 */
@Composable
private fun QueueItemMenu(
    mediaItem: MediaItem,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit
) {
    val menuState = LocalMenuState.current
    val extras = mediaItem.mediaMetadata.extras?.songBundle

    Menu {
        MediaItemMenuHeader(mediaItem = mediaItem)

        if (!isCurrent) {
            MenuEntry(
                icon = R.drawable.ms_playlist_play,
                text = stringResource(R.string.menu_play_next),
                onClick = {
                    onDismiss()
                    onPlayNext()
                }
            )
            MenuEntry(
                icon = R.drawable.ms_playlist_remove,
                text = stringResource(R.string.menu_remove_from_queue),
                onClick = {
                    onDismiss()
                    onRemove()
                }
            )
        }

        MenuEntry(
            icon = R.drawable.ms_playlist_add,
            text = stringResource(R.string.menu_add_to_playlist),
            onClick = { menuState.display { AddToPlaylistMenu(mediaItem = mediaItem, onDone = menuState::hide) } }
        )

        extras?.albumId?.let { albumId ->
            MenuEntry(
                icon = R.drawable.ms_album,
                text = stringResource(R.string.menu_go_to_album),
                secondaryText = mediaItem.mediaMetadata.albumTitle?.toString(),
                onClick = {
                    onDismiss()
                    albumRoute.global(albumId)
                }
            )
        }

        extras?.artistIds?.firstOrNull()?.let { artistId ->
            MenuEntry(
                icon = R.drawable.ms_person,
                text = stringResource(R.string.menu_go_to_artist),
                secondaryText = extras.artistNames?.firstOrNull(),
                onClick = {
                    onDismiss()
                    artistRoute.global(artistId)
                }
            )
        }
    }
}

/** Removes everything but the playing track; "Undo" puts the tracks back around it. */
private fun clearQueue(player: Player, snackbar: AppSnackbar, resources: Resources) {
    val current = player.currentMediaItemIndex
    val all = List(player.mediaItemCount) { player.getMediaItemAt(it) }
    if (all.size <= 1 || current !in all.indices) return

    player.removeMediaItems(current + 1, all.size)
    player.removeMediaItems(0, current)

    snackbar.showUndo(resources.getString(R.string.queue_cleared)) {
        if (player.mediaItemCount == 0) return@showUndo
        player.addMediaItems(0, all.subList(0, current))
        player.addMediaItems(current + 1, all.subList(current + 1, all.size))
    }
}

/** A new playlist with the tracks of the queue, in their order. */
private fun saveAsPlaylist(name: String, mediaItems: List<MediaItem>) = transaction {
    val playlistId = Database.insert(Playlist(name = name)).takeIf { it != -1L } ?: return@transaction
    mediaItems.forEachIndexed { position, mediaItem ->
        Database.insert(mediaItem)
        Database.insert(SongPlaylistMap(songId = mediaItem.mediaId, playlistId = playlistId, position = position))
    }
}

/** The duration from the metadata ("3:45", "1:02:03"), before the player knows it. */
private fun MediaItem.durationMs(): Long? = mediaMetadata.durationMs
    ?: mediaMetadata.extras?.songBundle?.durationText
        ?.split(':')
        ?.mapNotNull { it.trim().toLongOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?.fold(0L) { total, part -> total * 60 + part }
        ?.times(1000)
