package app.melogold.android.ui.screens.player

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaMetadata
import app.melogold.android.R
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.themed.Menu
import app.melogold.android.ui.components.themed.MenuEntry
import app.melogold.android.utils.toast

/**
 * The lyrics actions menu, shared by the classic lyrics overlay and the new player.
 *
 * Every entry hides the menu before invoking its callback. [onRefetch] being null shows a disabled
 * entry; [onPickFromLrcLib] and [onSetStartOffset] being null hides their entries.
 */
@Composable
fun LyricsMenu(
    showingSynced: Boolean,
    onToggleSynced: () -> Unit,
    onEdit: () -> Unit,
    onSearchOnline: () -> Unit,
    onRefetch: (() -> Unit)?,
    onPickFromLrcLib: (() -> Unit)?,
    onSetStartOffset: (() -> Unit)?,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    val menuState = LocalMenuState.current

    Menu {
        header()

        MenuEntry(
            icon = R.drawable.time,
            text = stringResource(
                if (showingSynced) R.string.show_unsynchronized_lyrics
                else R.string.show_synchronized_lyrics
            ),
            secondaryText = if (showingSynced) null
            else stringResource(R.string.provided_lyrics_by),
            onClick = {
                menuState.hide()
                onToggleSynced()
            }
        )

        MenuEntry(
            icon = R.drawable.pencil,
            text = stringResource(R.string.edit_lyrics),
            onClick = {
                menuState.hide()
                onEdit()
            }
        )

        MenuEntry(
            icon = R.drawable.search,
            text = stringResource(R.string.search_lyrics_online),
            onClick = {
                menuState.hide()
                onSearchOnline()
            }
        )

        MenuEntry(
            icon = R.drawable.sync,
            text = stringResource(R.string.refetch_lyrics),
            enabled = onRefetch != null,
            onClick = {
                menuState.hide()
                onRefetch?.invoke()
            }
        )

        if (onPickFromLrcLib != null) MenuEntry(
            icon = R.drawable.download,
            text = stringResource(R.string.pick_from_lrclib),
            onClick = {
                menuState.hide()
                onPickFromLrcLib()
            }
        )

        if (onSetStartOffset != null) MenuEntry(
            icon = R.drawable.play_skip_forward,
            text = stringResource(R.string.set_lyrics_start_offset),
            secondaryText = stringResource(R.string.set_lyrics_start_offset_description),
            onClick = {
                menuState.hide()
                onSetStartOffset()
            }
        )

        footer()
    }
}

/**
 * Opens a web search for the lyrics of [mediaMetadata], showing [errorMessage] if there is no app
 * to handle it.
 */
fun Context.searchLyricsOnline(mediaMetadata: MediaMetadata, errorMessage: String) {
    try {
        startActivity(
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(
                    SearchManager.QUERY,
                    "${mediaMetadata.title} ${mediaMetadata.artist} lyrics"
                )
            }
        )
    } catch (_: ActivityNotFoundException) {
        toast(errorMessage)
    }
}
