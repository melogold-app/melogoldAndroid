@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.sharedplaylist

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.repo.SharedPlaylists
import app.melogold.android.data.repo.songs
import app.melogold.android.models.Song
import app.melogold.android.sync.Shares
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.ShareDto
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.m3e.IconShape
import app.melogold.android.ui.components.m3e.ShapeIcon
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.PlayShuffleButtons
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.parseDuration
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.localPlaylistRoute
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.formatAsDuration
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import app.melogold.domain.share.ShareRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A playlist by a link, as the screen shows it (tasks/0017). */
sealed interface SharedPlaylistState {
    data object Loading : SharedPlaylistState

    data class Loaded(val share: ShareDto) : SharedPlaylistState

    /** The link was deleted or never was (`404 share_not_found`, a broken id). */
    data object Gone : SharedPlaylistState

    /** The server of the link could not be reached. */
    data object Offline : SharedPlaylistState
}

/** Reads the playlist a link points at, without signing in: the server of a link may be another one. */
class SharedPlaylistModel(private val ref: ShareRef, private val shares: Shares) : ScreenModel() {
    private val mutableState = MutableStateFlow<SharedPlaylistState>(SharedPlaylistState.Loading)
    val state: StateFlow<SharedPlaylistState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.value = SharedPlaylistState.Loading
        scope.launch {
            mutableState.value = try {
                SharedPlaylistState.Loaded(shares.open(ref))
            } catch (e: ApiException) {
                if (e.isNetwork || e.status >= SERVER_ERROR) SharedPlaylistState.Offline else SharedPlaylistState.Gone
            }
        }
    }

    private companion object {
        const val SERVER_ERROR = 500
    }
}

/**
 * "Playlist by link" (tasks/0017): the name and the tracks of a snapshot someone shared, to listen to, shuffle, or save
 * to the Library as a playlist of your own. Nothing is saved without the button.
 */
@Route
@Composable
fun SharedPlaylistScreen(serverUrl: String, shareId: String) = RouteHandler {
    GlobalRoutes()

    Content {
        val ref = remember(serverUrl, shareId) { ShareRef(serverUrl, shareId) }
        val model = rememberScreenModel("shared_playlist/$serverUrl/$shareId") { SharedPlaylistModel(ref, shares) }
        val state by model.state.collectAsState()
        val binder = LocalPlayerServiceBinder.current
        val menuState = LocalMenuState.current
        val snackbar = LocalAppSnackbar.current
        val scope = rememberCoroutineScope()
        val (playingId, _) = playingSong(binder)
        val savedMessage = stringResource(R.string.shared_playlist_saved)
        val failedMessage = stringResource(R.string.shared_playlist_save_failed)
        val openLabel = stringResource(R.string.shared_playlist_open)
        var saved by remember { mutableStateOf(false) }

        SharedPlaylistContent(
            state = state,
            host = Uri.parse(serverUrl).host.orEmpty(),
            playingId = playingId,
            saved = saved,
            onBack = pop,
            onRetry = model::load,
            onPlay = { songs, index, shuffle ->
                binder?.stopRadio()
                val items = songs.map(Song::asMediaItem)
                if (shuffle) binder?.player?.forcePlayAtIndex(items.shuffled(), 0)
                else binder?.player?.forcePlayAtIndex(items, index)
            },
            onMenu = { song ->
                menuState.display { NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = song.asMediaItem) }
            },
            onSave = { share ->
                scope.launch {
                    val id = SharedPlaylists.save(share)
                    if (id == null) snackbar.show(failedMessage)
                    else {
                        saved = true
                        snackbar.show(message = savedMessage, actionLabel = openLabel) { localPlaylistRoute(id) }
                    }
                }
            }
        )
    }
}

/**
 * The screen without its sources: [state], what to do with a touch. [host] is the server of the link, shown so that a
 * playlist from a foreign server says where it comes from.
 */
@Composable
fun SharedPlaylistContent(
    state: SharedPlaylistState,
    host: String,
    playingId: String?,
    saved: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (songs: List<Song>, index: Int, shuffle: Boolean) -> Unit,
    onMenu: (Song) -> Unit,
    onSave: (ShareDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val loaded = state as? SharedPlaylistState.Loaded
    val title = loaded?.share?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.shared_playlist_title)
    val subtitle = loaded?.let {
        listOf(
            stringResource(R.string.shared_playlist_title),
            pluralStringResource(R.plurals.library_tracks_count, it.share.tracks.size, it.share.tracks.size)
        ).joinToString(" · ")
    }

    CollectionScaffold(title = title, subtitle = subtitle, onBack = onBack, modifier = modifier) { padding ->
        when (state) {
            SharedPlaylistState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DelayedLoadingIndicator()
            }

            SharedPlaylistState.Gone -> Problem(
                text = stringResource(R.string.shared_playlist_gone),
                icon = R.drawable.ms_link_off,
                onRetry = null,
                onBack = onBack
            )

            SharedPlaylistState.Offline -> Problem(
                text = stringResource(R.string.shared_playlist_offline),
                icon = R.drawable.ms_cloud_off,
                onRetry = onRetry,
                onBack = null
            )

            is SharedPlaylistState.Loaded -> {
                val songs = remember(state.share) { state.share.songs() }
                LazyColumn(contentPadding = padding, modifier = Modifier.testTag("shared_playlist")) {
                    item(key = "host") {
                        Text(
                            text = stringResource(R.string.shared_playlist_foreign_server, host),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .testTag("shared_playlist_host")
                        )
                    }
                    item(key = "play") {
                        PlayShuffleButtons(
                            onPlay = { onPlay(songs, 0, false) },
                            onShuffle = { onPlay(songs, 0, true) },
                            enabled = songs.isNotEmpty()
                        )
                    }
                    item(key = "save") {
                        FilledTonalButton(
                            onClick = { onSave(state.share) },
                            enabled = !saved && songs.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("shared_playlist_save")
                        ) {
                            Icon(
                                painter = painterResource(if (saved) R.drawable.ms_library_add_check else R.drawable.ms_library_add),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Text(
                                text = stringResource(if (saved) R.string.shared_playlist_saved else R.string.shared_playlist_save),
                                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing)
                            )
                        }
                    }
                    itemsIndexed(items = songs, key = { _, song -> "track_${song.id}" }) { index, song ->
                        TrackRow(
                            title = song.title,
                            subtitle = song.artistsText,
                            artworkUrl = song.thumbnailUrl,
                            videoId = song.id,
                            onClick = { onPlay(songs, index, false) },
                            onMenu = { onMenu(song) },
                            isPlaying = song.id == playingId,
                            explicit = song.explicit,
                            duration = parseDuration(song.durationText)?.let(::formatAsDuration),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** What went wrong and what can be done: try again, or go back. */
@Composable
private fun Problem(text: String, icon: Int, onRetry: (() -> Unit)?, onBack: (() -> Unit)?) = Box(
    modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
    contentAlignment = Alignment.Center
) {
    Column(
        modifier = Modifier.widthIn(max = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ShapeIcon(
            icon = icon,
            shape = IconShape.Cookie9Sided,
            contentDescription = null,
            size = 72.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("shared_playlist_problem")
        )
        onRetry?.let { Button(onClick = it, modifier = Modifier.testTag("shared_playlist_retry")) { Text(stringResource(R.string.kit_retry)) } }
        onBack?.let { TextButton(onClick = it) { Text(stringResource(R.string.kit_back)) } }
    }
}
