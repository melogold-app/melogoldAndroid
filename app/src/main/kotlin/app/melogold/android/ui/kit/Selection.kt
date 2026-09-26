@file:OptIn(ExperimentalMaterial3Api::class)

package app.melogold.android.ui.kit

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.LocalAppContainer
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.overrides.TrackOverrides
import app.melogold.android.data.repo.storedTrackLinks
import app.melogold.android.models.DownloadState
import app.melogold.android.models.Playlist
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.models.TrackDownload
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.android.service.isLocal
import app.melogold.android.transaction
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.AddAllToPlaylistMenu
import app.melogold.android.ui.screens.localPlaylistRoute
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.utils.enqueue
import app.melogold.android.utils.forcePlayFromBeginning
import kotlinx.coroutines.launch

/** Video ids per `UPDATE … IN (…)`, under SQLite's limit of bound variables. */
private const val LIKE_CHUNK = 500

private val main = Handler(Looper.getMainLooper())

/**
 * The tracks selected in a list (task 0011): a long press on a track starts the selection, taps
 * then check and uncheck; the selection ends with ✕, Back, or when the last track is unchecked.
 * Kept by video id, so it survives sorting and rotation.
 */
@Stable
class TrackSelection(initial: Set<String> = emptySet()) {
    var ids by mutableStateOf(initial)
        private set

    val active: Boolean get() = ids.isNotEmpty()

    operator fun contains(id: String) = id in ids

    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun selectAll(all: List<String>) {
        ids = all.toSet()
    }

    fun clear() {
        ids = emptySet()
    }

    /** The selected ones of [list], in the order of the list (not the order they were tapped). */
    fun <T> of(list: List<T>, id: (T) -> String): List<T> = list.filter { id(it) in ids }

    /** What a row needs to know about the selection. */
    fun row(id: String) = RowSelection(selected = id in ids, selecting = active, onToggle = { toggle(id) })

    companion object {
        val Saver = listSaver<TrackSelection, String>(
            save = { it.ids.toList() },
            restore = { TrackSelection(it.toSet()) }
        )
    }
}

@Composable
fun rememberTrackSelection(): TrackSelection = rememberSaveable(saver = TrackSelection.Saver) { TrackSelection() }

/**
 * A row in a list that can select: [selected] shows the check, while [selecting] a tap toggles
 * instead of playing, and a long press always toggles (it starts the selection).
 */
@Immutable
class RowSelection(val selected: Boolean, val selecting: Boolean, val onToggle: () -> Unit)

/**
 * The album all of [albums] share, the default name of a playlist made of them; `null` when one is
 * unknown or they differ.
 */
internal fun commonAlbum(albums: List<String?>): String? {
    val names = albums.map { it?.trim()?.takeIf(String::isNotEmpty) ?: return null }
    return names.distinct().singleOrNull()
}

/**
 * What "Download" starts for [items]: not the local files, the live streams, or what is already
 * downloaded or on its way; a failed or only cached one is downloaded (again).
 */
internal fun toDownload(
    items: List<MediaItem>,
    downloads: Map<String, TrackDownload>,
    liveIds: Set<String>
): List<MediaItem> = items.filter { item ->
    val download = downloads[item.mediaId]
    !item.isLocal && item.mediaId !in liveIds &&
        (download == null || !download.manual || download.state == DownloadState.Failed)
}

/**
 * The contextual top app bar of a list while tracks are selected (task 0011): ✕, "N selected", and
 * the actions on the selected [tracks] (in the order of the list): Play, Add to Favorites,
 * Download, and in ⋮ Add to queue, Add to playlist…, New playlist…, Select all. Back ends the
 * selection.
 *
 * @param tracks every track of the list, in its order
 * @param liveIds the live streams among them, which are not downloaded
 * @param collectionName the name of the playlist or album the tracks are in: "Set album…" suggests it when they
 *   have no common album
 */
@Composable
fun SelectionTopBar(
    selection: TrackSelection,
    tracks: List<MediaItem>,
    modifier: Modifier = Modifier,
    liveIds: Set<String> = emptySet(),
    collectionName: String? = null
) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val snackbar = LocalAppSnackbar.current
    val downloads = LocalAppContainer.current.downloads
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()

    val selected = remember(tracks, selection.ids) { selection.of(tracks) { it.mediaId } }
    var overflow by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<String?>(null) }
    var settingAlbum by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = selection::clear)

    fun tracksCount(count: Int) = resources.getQuantityString(R.plurals.library_tracks_count, count, count)

    fun done(message: String) {
        selection.clear()
        snackbar.show(message)
    }

    naming?.let { initial ->
        TextInputDialog(
            title = stringResource(R.string.playlist_new_title),
            label = stringResource(R.string.playlist_name),
            confirmLabel = stringResource(R.string.playlist_create),
            initialValue = initial,
            onDismiss = { naming = null },
            onConfirm = { name ->
                naming = null
                val items = selected.distinctBy { it.mediaId }
                val message = resources.getString(R.string.selection_playlist_created, name, tracksCount(items.size))
                val open = resources.getString(R.string.selection_open)
                selection.clear()
                transaction {
                    val id = Database.insert(Playlist(name = name)).takeIf { it != -1L } ?: return@transaction
                    items.forEachIndexed { position, item ->
                        Database.insert(item)
                        Database.insert(SongPlaylistMap(item.mediaId, id, position))
                    }
                    main.post { snackbar.show(message = message, actionLabel = open) { localPlaylistRoute.global(id) } }
                }
            }
        )
    }

    settingAlbum?.let { initial ->
        TextInputDialog(
            title = stringResource(R.string.selection_set_album_title),
            label = stringResource(R.string.track_details_album),
            confirmLabel = stringResource(R.string.track_details_save),
            initialValue = initial,
            onDismiss = { settingAlbum = null },
            onConfirm = { album ->
                settingAlbum = null
                TrackOverrides.setAlbum(selected.map { it.mediaId }.filterNot { it.startsWith(LOCAL_KEY_PREFIX) }, album)
                done(resources.getString(R.string.selection_album_set, album.trim()))
            }
        )
    }

    TopAppBar(
        title = {
            Text(text = stringResource(R.string.selection_count, selected.size))
        },
        navigationIcon = {
            IconButton(onClick = selection::clear) {
                Icon(
                    painter = painterResource(R.drawable.ms_close),
                    contentDescription = stringResource(R.string.selection_clear)
                )
            }
        },
        actions = {
            IconButton(
                onClick = {
                    binder?.stopRadio()
                    binder?.player?.forcePlayFromBeginning(selected)
                    selection.clear()
                },
                enabled = selected.isNotEmpty()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ms_play_arrow),
                    contentDescription = stringResource(R.string.selection_play)
                )
            }

            IconButton(
                onClick = {
                    val items = selected
                    // One transaction: one change for the sync, not one per track
                    transaction {
                        items.forEach { Database.insert(it) }
                        items.map { it.mediaId }.chunked(LIKE_CHUNK).forEach { ids ->
                            Database.likeAll(ids, System.currentTimeMillis())
                        }
                    }
                    done(resources.getString(R.string.selection_favorited, tracksCount(items.size)))
                },
                enabled = selected.isNotEmpty()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ms_favorite),
                    contentDescription = stringResource(R.string.menu_favorite_add)
                )
            }

            IconButton(
                onClick = {
                    val items = toDownload(selected, downloads.states.value, liveIds)
                    items.forEach(downloads::download)
                    done(
                        if (items.isEmpty()) resources.getString(R.string.selection_already_downloaded)
                        else resources.getString(R.string.selection_downloading, tracksCount(items.size))
                    )
                },
                enabled = selected.isNotEmpty()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ms_download),
                    contentDescription = stringResource(R.string.menu_download)
                )
            }

            Box {
                IconButton(onClick = { overflow = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ms_more_vert),
                        contentDescription = stringResource(R.string.kit_menu)
                    )
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.menu_add_to_queue)) },
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            overflow = false
                            binder?.player?.enqueue(selected)
                            done(resources.getString(R.string.selection_enqueued, tracksCount(selected.size)))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.menu_add_to_playlist)) },
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            overflow = false
                            val items = selected
                            selection.clear()
                            menuState.display {
                                AddAllToPlaylistMenu(mediaItems = items, name = "", onDone = menuState::hide)
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.selection_new_playlist)) },
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            overflow = false
                            val items = selected
                            scope.launch {
                                naming = commonAlbum(items.map { it.storedTrackLinks().album?.name }).orEmpty()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.selection_set_album)) },
                        enabled = selected.any { !it.isLocal },
                        onClick = {
                            overflow = false
                            val items = selected
                            scope.launch {
                                // The album the tracks show: their own override, else YouTube's
                                settingAlbum = commonAlbum(
                                    items.map { TrackOverrides[it.mediaId]?.albumTitle ?: it.storedTrackLinks().album?.name }
                                ) ?: collectionName.orEmpty()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.selection_select_all)) },
                        onClick = {
                            overflow = false
                            selection.selectAll(tracks.map { it.mediaId })
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.testTag("selection_bar")
    )
}

