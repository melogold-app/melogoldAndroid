package app.melogold.android.ui.screens.player

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.melogold.android.R
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.MenuEntry

/**
 * The "Lyrics" group of the player menu. Everything happens in the app: the lyrics are found on
 * LRCLIB in a dialog, imported from a file or typed in the editor.
 *
 * Every entry hides the menu before invoking its callback. [onRefetch] being null shows a disabled
 * entry; [onSetStartOffset] being null hides its entry.
 */
@Suppress("ParameterNaming") // "synced" names the lyrics kind, not a past event
@Composable
fun ColumnScope.LyricsMenuEntries(
    showingSynced: Boolean,
    onToggleSynced: () -> Unit,
    onFind: () -> Unit,
    onEdit: () -> Unit,
    onImport: () -> Unit,
    onRefetch: (() -> Unit)?,
    onSetStartOffset: (() -> Unit)?
) {
    val menuState = LocalMenuState.current

    fun entry(action: () -> Unit): () -> Unit = {
        menuState.hide()
        action()
    }

    MenuEntry(
        icon = R.drawable.ms_lyrics,
        text = stringResource(if (showingSynced) R.string.lyrics_show_plain else R.string.lyrics_show_synced),
        onClick = entry(onToggleSynced)
    )

    MenuEntry(
        icon = R.drawable.ms_manage_search,
        text = stringResource(R.string.lyrics_find),
        onClick = entry(onFind)
    )

    MenuEntry(
        icon = R.drawable.ms_edit,
        text = stringResource(R.string.lyrics_edit),
        onClick = entry(onEdit)
    )

    MenuEntry(
        icon = R.drawable.ms_file_open,
        text = stringResource(R.string.lyrics_import_file),
        onClick = entry(onImport)
    )

    MenuEntry(
        icon = R.drawable.ms_refresh,
        text = stringResource(R.string.lyrics_refetch),
        enabled = onRefetch != null,
        onClick = entry { onRefetch?.invoke() }
    )

    if (onSetStartOffset != null) MenuEntry(
        icon = R.drawable.ms_start,
        text = stringResource(R.string.lyrics_set_start),
        secondaryText = stringResource(R.string.lyrics_set_start_description),
        onClick = entry(onSetStartOffset)
    )
}
