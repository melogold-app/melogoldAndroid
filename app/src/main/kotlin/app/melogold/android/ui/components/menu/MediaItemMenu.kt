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
import app.melogold.android.Database
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.repo.knownTrackLinks
import app.melogold.android.data.repo.trackLinks
import app.melogold.android.models.Song
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.query
import app.melogold.android.service.PrecacheService
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
import app.melogold.android.utils.isCached
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
    val blacklisted by remember(songId) { Database.blacklisted(songId) }
        .collectAsState(initial = false, context = Dispatchers.IO)

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

    if (!isLocal && !isCached(songId)) MenuEntry(
        icon = R.drawable.ms_download,
        text = stringResource(R.string.menu_download),
        onClick = entry {
            runCatching {
                PrecacheService.scheduleCache(context = context.applicationContext, mediaItem = mediaItem)
            }.exceptionOrNull()?.printStackTrace()
        }
    )

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
            transaction {
                Database.insert(mediaItem)
                Database.toggleBlacklist(songId)
            }
            if (!blacklisted) {
                onHidden()
                snackbar.showUndo(hiddenMessage) { transaction { Database.toggleBlacklist(songId) } }
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

/** Shares the link of the track: YouTube Music for music, YouTube for videos (FEATURES "Поделиться"). */
private fun Context.shareTrack(mediaItem: MediaItem, isMusic: Boolean) {
    val host = if (isMusic) "music.youtube.com" else "www.youtube.com"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://$host/watch?v=${mediaItem.mediaId}")
    }

    startActivity(Intent.createChooser(intent, null))
}
