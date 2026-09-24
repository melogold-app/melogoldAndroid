@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.components.themed

import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.DatabaseAccessor
import app.melogold.android.R
import app.melogold.android.models.Playlist
import app.melogold.android.models.PlaylistPreview
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.transaction
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.NewPlaylistDialog
import app.melogold.android.ui.shell.AppSnackbar
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.core.data.enums.PlaylistSortBy
import app.melogold.core.data.enums.SortOrder
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap

/** With more playlists than this, a field filters them by name. */
private const val FILTER_THRESHOLD = 8

/**
 * One playlist touched in the sheet: whether it had the track when first touched, where the track
 * was then (to put it back), and whether the sheet created the playlist.
 */
private class PlaylistEdit(
    val name: String,
    val wasMember: Boolean,
    val created: Boolean = false
) {
    @Volatile
    var position: Int? = null
}

/**
 * "Add to playlist…" (GLOSSARY): every playlist with a check on those that have the track. A tap
 * adds or removes it right away; when the sheet goes away, one snackbar sums the changes up with
 * "Undo" (which also deletes a playlist created here).
 */
@Composable
fun AddToPlaylistMenu(
    mediaItem: MediaItem,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val songId = mediaItem.mediaId
    val resources = LocalContext.current.resources
    val snackbar = LocalAppSnackbar.current

    val playlists by remember {
        Database.playlistPreviews(sortBy = PlaylistSortBy.DateAdded, sortOrder = SortOrder.Descending)
    }.collectAsState(initial = null, context = Dispatchers.IO)
    val memberOf by remember(songId) {
        Database.playlistIdsOf(songId)
    }.collectAsState(initial = emptyList(), context = Dispatchers.IO)

    val edits = remember { ConcurrentHashMap<Long, PlaylistEdit>() }
    var filter by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(songId) {
        onDispose { if (edits.isNotEmpty()) summarize(songId, edits.toMap(), resources, snackbar) }
    }

    fun toggle(preview: PlaylistPreview, isMember: Boolean) {
        edits.putIfAbsent(preview.id, PlaylistEdit(name = preview.name, wasMember = isMember))

        transaction {
            if (isMember) Database.positionIn(songId, preview.id)?.let { position ->
                edits[preview.id]?.let { if (it.wasMember && it.position == null) it.position = position }
                Database.removeFrom(songId, preview.id, position)
            } else {
                Database.insert(mediaItem)
                Database.insert(SongPlaylistMap(songId, preview.id, Database.songCountOf(preview.id)))
            }
        }
    }

    if (creating) NewPlaylistDialog(
        onDismiss = { creating = false },
        onCreate = { name ->
            creating = false
            transaction {
                val id = Database.insert(Playlist(name = name))
                if (id == -1L) return@transaction
                Database.insert(mediaItem)
                Database.insert(SongPlaylistMap(songId, id, 0))
                edits[id] = PlaylistEdit(name = name, wasMember = false, created = true)
            }
        }
    )

    Menu(modifier = modifier.testTag("add_to_playlist")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.add_to_playlist_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDone) { Text(text = stringResource(R.string.done)) }
        }

        if ((playlists?.size ?: 0) > FILTER_THRESHOLD) OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text(text = stringResource(R.string.add_to_playlist_find)) },
            leadingIcon = { Icon(painter = painterResource(R.drawable.ms_search), contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        MenuEntry(
            icon = R.drawable.ms_add,
            text = stringResource(R.string.new_playlist),
            onClick = { creating = true }
        )

        playlists
            ?.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
            ?.forEach { preview ->
                val isMember = preview.id in memberOf

                ListItem(
                    checked = isMember,
                    onCheckedChange = { toggle(preview, isMember) },
                    leadingContent = {
                        Artwork(url = preview.thumbnail, size = 48.dp, shape = RoundedCornerShape(8.dp))
                    },
                    supportingContent = {
                        Text(
                            text = pluralStringResource(
                                R.plurals.song_count_plural,
                                preview.songCount,
                                preview.songCount
                            )
                        )
                    },
                    trailingContent = { Checkbox(checked = isMember, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        selectedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.testTag("add_to_playlist_row")
                ) {
                    Text(text = preview.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

        if (playlists?.isEmpty() == true) Text(
            text = stringResource(R.string.add_to_playlist_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

/** Removes [songId] from [playlistId] and closes the gap it leaves. Call in a transaction. */
private fun DatabaseAccessor.removeFrom(songId: String, playlistId: Long, position: Int) {
    move(playlistId, position, Int.MAX_VALUE)
    delete(SongPlaylistMap(songId, playlistId, Int.MAX_VALUE))
}

/**
 * After the edits are written (transactions run in order), shows what changed with "Undo". Runs
 * off the main thread and posts the snackbar back to it.
 */
private fun summarize(
    songId: String,
    edits: Map<Long, PlaylistEdit>,
    resources: Resources,
    snackbar: AppSnackbar
) = transaction {
    val changed = edits.filter { (id, edit) -> (Database.positionIn(songId, id) != null) != edit.wasMember }
    if (changed.isEmpty()) return@transaction

    val message = changed.values.singleOrNull()?.let { edit ->
        resources.getString(
            if (edit.wasMember) R.string.add_to_playlist_removed else R.string.add_to_playlist_added,
            edit.name
        )
    } ?: resources.getQuantityString(R.plurals.add_to_playlist_changed, changed.size, changed.size)

    Handler(Looper.getMainLooper()).post {
        snackbar.showUndo(message) { undo(songId, changed) }
    }
}

private fun undo(songId: String, changed: Map<Long, PlaylistEdit>) = transaction {
    changed.forEach { (id, edit) ->
        when {
            edit.created -> Database.delete(Playlist(id = id, name = edit.name))

            // It was removed: put it back where it was
            edit.wasMember -> if (Database.positionIn(songId, id) == null) {
                val count = Database.songCountOf(id)
                Database.insert(SongPlaylistMap(songId, id, count))
                edit.position?.takeIf { it < count }?.let { Database.move(id, count, it) }
            }

            else -> Database.positionIn(songId, id)?.let { Database.removeFrom(songId, id, it) }
        }
    }
}
