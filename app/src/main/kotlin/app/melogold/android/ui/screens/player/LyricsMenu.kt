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
import app.melogold.android.ui.components.menu.Menu
import app.melogold.android.ui.components.menu.MenuEntry
import app.melogold.android.utils.toast

/**
 * The lyrics actions menu, shared by the classic lyrics overlay and the new player.
 *
 * Every entry hides the menu before invoking its callback. [onRefetch] being null shows a disabled
 * entry; [onPickFromLrcLib] and [onSetStartOffset] being null hides their entries.
 */
@Suppress("ParameterNaming") // "synced" names the lyrics kind, not a past event
@Composable
fun LyricsMenu(
    showingSynced: Boolean,
    onToggleSynced: () -> Unit,
    onEdit: () -> Unit,
    onSearchOnline: () -> Unit,
    onRefetch: (() -> Unit)?,
    onImport: () -> Unit,
    onPickFromLrcLib: (() -> Unit)?,
    onSetStartOffset: (() -> Unit)?,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    val menuState = LocalMenuState.current

    Menu {
        header()

        MenuEntry(
            icon = R.drawable.ms_lyrics,
            text = stringResource(if (showingSynced) R.string.lyrics_show_plain else R.string.lyrics_show_synced),
            onClick = {
                menuState.hide()
                onToggleSynced()
            }
        )

        MenuEntry(
            icon = R.drawable.ms_edit,
            text = stringResource(R.string.lyrics_edit),
            onClick = {
                menuState.hide()
                onEdit()
            }
        )

        MenuEntry(
            icon = R.drawable.ms_travel_explore,
            text = stringResource(R.string.lyrics_search_web),
            onClick = {
                menuState.hide()
                onSearchOnline()
            }
        )

        MenuEntry(
            icon = R.drawable.ms_refresh,
            text = stringResource(R.string.lyrics_refetch),
            enabled = onRefetch != null,
            onClick = {
                menuState.hide()
                onRefetch?.invoke()
            }
        )

        MenuEntry(
            icon = R.drawable.ms_file_open,
            text = stringResource(R.string.lyrics_import_file),
            onClick = {
                menuState.hide()
                onImport()
            }
        )

        if (onPickFromLrcLib != null) MenuEntry(
            icon = R.drawable.ms_manage_search,
            text = stringResource(R.string.lyrics_pick_lrclib),
            onClick = {
                menuState.hide()
                onPickFromLrcLib()
            }
        )

        if (onSetStartOffset != null) MenuEntry(
            icon = R.drawable.ms_start,
            text = stringResource(R.string.lyrics_set_start),
            secondaryText = stringResource(R.string.lyrics_set_start_description),
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
