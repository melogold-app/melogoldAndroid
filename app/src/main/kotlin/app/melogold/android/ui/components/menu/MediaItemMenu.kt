package app.melogold.android.ui.components.menu

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import app.melogold.android.LocalAppContainer
import app.melogold.android.models.DownloadFailure
import app.melogold.android.models.DownloadState
import app.melogold.android.models.DownloadWaitReason
import app.melogold.android.models.TrackDownload
import app.melogold.android.ui.kit.rememberDownload
import app.melogold.android.ui.shell.LocalAskNotifications
import app.melogold.android.data.repo.PendingMutation
import app.melogold.android.data.repo.withPending
import app.melogold.android.Database
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.repo.knownTrackLinks
import app.melogold.android.data.repo.trackLinks
import app.melogold.android.models.Song
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.query
import app.melogold.android.service.isLocal
import app.melogold.android.transaction
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.SearchSource
import app.melogold.android.utils.addNext
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.enqueue
import app.melogold.android.utils.forcePlay
import app.melogold.providers.innertube.models.NavigationEndpoint
import kotlinx.coroutines.Dispatchers

@Composable
fun NonQueuedMediaItemMenu(
    onDismiss: () -> Unit,
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onHideFromDatabase: (() -> Unit)? = null,
    onRemoveFromQuickPicks: (() -> Unit)? = null
) {
    val binder = LocalPlayerServiceBinder.current

    BaseMediaItemMenu(
        mediaItem = mediaItem,
        onDismiss = onDismiss,
        onStartRadio = {
            binder?.stopRadio()
            binder?.player?.forcePlay(mediaItem)
            binder?.setupRadio(
                NavigationEndpoint.Endpoint.Watch(
                    videoId = mediaItem.mediaId,
                    playlistId = mediaItem.mediaMetadata.extras?.getString("playlistId")
                )
            )
        },
        onPlayNext = { binder?.player?.addNext(mediaItem) },
        onEnqueue = { binder?.player?.enqueue(mediaItem) },
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onHideFromDatabase = onHideFromDatabase,
        onRemoveFromQuickPicks = onRemoveFromQuickPicks,
        modifier = modifier
    )
}

@Composable
fun BaseMediaItemMenu(
    onDismiss: () -> Unit,
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    onStartRadio: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onEnqueue: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onHideFromDatabase: (() -> Unit)? = null,
    onRemoveFromQuickPicks: (() -> Unit)? = null
) = Menu(modifier = modifier) {
    MediaItemMenuHeader(mediaItem = mediaItem)
    TrackMenuEntries(
        mediaItem = mediaItem,
        onDismiss = onDismiss,
        onStartRadio = onStartRadio,
        onPlayNext = onPlayNext,
        onEnqueue = onEnqueue,
        onRemoveFromQueue = onRemoveFromQueue,
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onHideFromDatabase = onHideFromDatabase,
        onRemoveFromQuickPicks = onRemoveFromQuickPicks
    )
}

/** The track the menu is about: cover, title and "Artist · Album". */
@Composable
fun MediaItemMenuHeader(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier
) {
    val metadata = mediaItem.mediaMetadata

    MenuHeader(
        title = metadata.title?.toString().orEmpty(),
        subtitle = listOfNotNull(metadata.artist, metadata.albumTitle)
            .map { it.toString() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifEmpty { null },
        artworkUrl = metadata.artworkUri?.toString(),
        modifier = modifier
    )
}

/**
 * The entries of the track menu in the order every client uses (GLOSSARY "Меню трека"): the
 * actions on the track, then, after a divider, the ones that hide or remove it.
 *
 * Every entry hides the menu. [onNavigate] runs before an entry leaves for another screen (the
 * player collapses itself there); [onHidden] runs after "Don't show this track" hid it (the player
 * skips it), and a snackbar offers "Undo".
 *
 * @param showFavorite false where ♡ is already on screen (the player)
 * @param trackRadio "Start track radio" instead of "Start radio" (the player menu)
 */
@Composable
fun TrackMenuEntries(
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onStartRadio: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onEnqueue: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onHideFromDatabase: (() -> Unit)? = null,
    onRemoveFromQuickPicks: (() -> Unit)? = null,
    onNavigate: () -> Unit = {},
    onHidden: () -> Unit = {},
    showFavorite: Boolean = true,
    trackRadio: Boolean = false
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val nav = LocalMainNav.current
    val snackbar = LocalAppSnackbar.current
    val hiddenMessage = stringResource(R.string.menu_track_hidden)
    val songId = mediaItem.mediaId
    val isLocal = mediaItem.isLocal

    val likedAt by remember(songId) { Database.likedAt(songId) }
        .collectAsState(initial = null, context = Dispatchers.IO)
    val blacklisted by remember(songId) {
        Database.blacklisted(songId).withPending { pending ->
            this || pending.any { it is PendingMutation.Hide && it.songId == songId }
        }
    }.collectAsState(initial = false, context = Dispatchers.IO)

    // What the item carries at once; Room and then YouTube Music fill in the rest
    var links by remember(mediaItem) { mutableStateOf(mediaItem.knownTrackLinks) }
    LaunchedEffect(mediaItem) { links = mediaItem.trackLinks() }
    val album = links.album

    fun entry(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    val liked = likedAt != null
    if (showFavorite) MenuEntry(
        icon = if (liked) R.drawable.ms_favorite_fill else R.drawable.ms_favorite,
        text = stringResource(if (liked) R.string.menu_favorite_remove else R.string.menu_favorite_add),
        onClick = entry {
            query {
                val changed = Database.like(
                    songId = songId,
                    likedAt = if (liked) null else System.currentTimeMillis()
                ) != 0
                if (!changed) Database.insert(mediaItem, Song::toggleLike)
            }
        }
    )

    onPlayNext?.let {
        MenuEntry(
            icon = R.drawable.ms_playlist_play,
            text = stringResource(R.string.menu_play_next),
            onClick = entry(it)
        )
    }

    onEnqueue?.let {
        MenuEntry(
            icon = R.drawable.ms_queue_music,
            text = stringResource(R.string.menu_add_to_queue),
            onClick = entry(it)
        )
    }

    MenuEntry(
        icon = R.drawable.ms_playlist_add,
        text = stringResource(R.string.menu_add_to_playlist),
        onClick = {
            // In the same sheet: the menu becomes the playlist picker
            menuState.display { AddToPlaylistMenu(mediaItem = mediaItem, onDone = menuState::hide) }
        }
    )

    if (!isLocal) DownloadEntry(mediaItem = mediaItem, onDismiss = onDismiss)

    if (!isLocal) {
        MenuEntry(
            icon = R.drawable.ms_manage_search,
            text = stringResource(R.string.other_versions),
            onClick = entry {
                onNavigate()
                val metadata = mediaItem.mediaMetadata
                nav.openSearch(
                    query = listOfNotNull(metadata.artist, metadata.title).joinToString(" ").trim(),
                    source = SearchSource.YouTube
                )
            }
        )

        onStartRadio?.let {
            MenuEntry(
                icon = R.drawable.ms_sensors,
                text = stringResource(if (trackRadio) R.string.menu_start_track_radio else R.string.menu_start_radio),
                onClick = entry(it)
            )
        }

        // Found after the menu opened: the rows unfold instead of jumping in
        AnimatedVisibility(visible = album != null, enter = expandVertically() + fadeIn()) {
            album?.let { (id, name) ->
                MenuEntry(
                    icon = R.drawable.ms_album,
                    text = stringResource(R.string.menu_go_to_album),
                    secondaryText = name ?: mediaItem.mediaMetadata.albumTitle?.toString(),
                    onClick = entry {
                        onNavigate()
                        albumRoute.global(id)
                    }
                )
            }
        }

        AnimatedVisibility(visible = links.artists.isNotEmpty(), enter = expandVertically() + fadeIn()) {
            Column {
                links.artists.forEach { (id, name) ->
                    MenuEntry(
                        icon = R.drawable.ms_person,
                        text = stringResource(R.string.menu_go_to_artist),
                        secondaryText = name,
                        onClick = entry {
                            onNavigate()
                            artistRoute.global(id)
                        }
                    )
                }
            }
        }

        MenuEntry(
            icon = R.drawable.ms_share,
            text = stringResource(R.string.menu_share),
            onClick = entry { context.shareTrack(mediaItem, isMusic = album != null) }
        )
    }

    val hasRemovals = !isLocal || onRemoveFromPlaylist != null || onHideFromDatabase != null ||
        onRemoveFromQueue != null
    if (hasRemovals) MenuDivider()

    if (!isLocal) MenuEntry(
        icon = R.drawable.ms_visibility_off,
        text = stringResource(if (blacklisted) R.string.menu_show_again else R.string.menu_dont_show),
        onClick = entry {
            if (blacklisted) transaction { Database.toggleBlacklist(songId) }
            else {
                // The hiding is written later, to the row of the track
                transaction { Database.insert(mediaItem) }
                onHidden()
                snackbar.undoable(hiddenMessage, PendingMutation.Hide(songId))
            }
        }
    )

    onRemoveFromPlaylist?.let {
        MenuEntry(
            icon = R.drawable.ms_playlist_remove,
            text = stringResource(R.string.menu_remove_from_playlist),
            onClick = entry(it)
        )
    }

    // Asks for confirmation in a dialog, which then closes the menu
    onHideFromDatabase?.let {
        MenuEntry(
            icon = R.drawable.ms_delete_history,
            text = stringResource(R.string.menu_remove_from_history),
            onClick = it
        )
    }

    onRemoveFromQueue?.let {
        MenuEntry(
            icon = R.drawable.ms_playlist_remove,
            text = stringResource(R.string.menu_remove_from_queue),
            onClick = entry(it)
        )
    }

    if (!isLocal) onRemoveFromQuickPicks?.let {
        MenuEntry(
            icon = R.drawable.ms_block,
            text = stringResource(R.string.menu_not_interested),
            onClick = entry(it)
        )
    }
}

/**
 * The download of the track (REWRITE §3.10.5): "Download"; while it goes "Cancel download" with how
 * far it got; "Download again" after a failure; "Remove download", with "Undo", once it is done.
 */
@Composable
private fun DownloadEntry(mediaItem: MediaItem, onDismiss: () -> Unit) {
    val downloads = LocalAppContainer.current.downloads
    val snackbar = LocalAppSnackbar.current
    val askNotifications = LocalAskNotifications.current
    val removedMessage = stringResource(R.string.download_removed)
    val videoId = mediaItem.mediaId
    val download = rememberDownload(videoId)

    fun entry(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    when (download?.state) {
        null -> MenuEntry(
            icon = R.drawable.ms_download,
            text = stringResource(R.string.menu_download),
            onClick = entry {
                askNotifications()
                downloads.download(mediaItem)
            }
        )

        DownloadState.Completed -> MenuEntry(
            icon = R.drawable.ms_delete,
            text = stringResource(R.string.menu_download_remove),
            onClick = entry { snackbar.undoable(removedMessage, PendingMutation.RemoveDownload(videoId)) }
        )

        DownloadState.Failed -> MenuEntry(
            icon = R.drawable.ms_refresh,
            text = stringResource(R.string.menu_download_retry),
            secondaryText = downloadStatus(download),
            onClick = entry { downloads.retry(videoId) }
        )

        else -> MenuEntry(
            icon = R.drawable.ms_close,
            text = stringResource(R.string.menu_download_cancel),
            secondaryText = downloadStatus(download),
            onClick = entry { downloads.remove(videoId) }
        )
    }
}

/** How a download stands, in words: "Downloading · 45 %", "Waiting for Wi‑Fi", "Network error"… */
@Composable
fun downloadStatus(download: TrackDownload): String = when (download.state) {
    DownloadState.Downloading -> download.progress
        ?.let { stringResource(R.string.download_state_downloading, (it * 100).toInt()) }
        ?: stringResource(R.string.download_state_starting)

    DownloadState.Queued -> stringResource(R.string.download_state_queued)
    DownloadState.Paused -> stringResource(R.string.download_state_paused)
    DownloadState.Completed -> stringResource(R.string.download_completed)

    DownloadState.Waiting -> stringResource(
        when (download.waitReason) {
            DownloadWaitReason.Wifi -> R.string.download_wait_wifi
            DownloadWaitReason.Storage -> R.string.download_wait_storage
            else -> R.string.download_wait_network
        }
    )

    DownloadState.Failed -> stringResource(
        when (download.failureCode) {
            DownloadFailure.Unavailable -> R.string.download_failure_unavailable
            DownloadFailure.Network -> R.string.download_failure_network
            DownloadFailure.StorageFull -> R.string.download_failure_storage
            else -> R.string.download_failure_unknown
        }
    )
}

/** Shares the link of the track: YouTube Music for music, YouTube for videos (FEATURES "Поделиться"). */
private fun Context.shareTrack(mediaItem: MediaItem, isMusic: Boolean) {
    val host = if (isMusic) "music.youtube.com" else "www.youtube.com"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://$host/watch?v=${mediaItem.mediaId}")
    }

    startActivity(Intent.createChooser(intent, null))
}
