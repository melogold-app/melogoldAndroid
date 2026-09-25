package app.melogold.android.ui.screens.player

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.melogold.android.R
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.MenuEntry

/**
 * The lyrics group of the player menu, three rows: synced or plain, "Find lyrics" (LRCLIB, or a file
 * from the same dialog) and "Edit lyrics". Everything happens in the app.
 *
 * Every entry hides the menu before invoking its callback; [onSetStartOffset] being null hides its
 * entry (it acts on a long-pressed line).
 */
@Suppress("ParameterNaming") // "synced" names the lyrics kind, not a past event
@Composable
fun ColumnScope.LyricsMenuEntries(
    showingSynced: Boolean,
    onToggleSynced: () -> Unit,
    onFind: () -> Unit,
    onEdit: () -> Unit,
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

    if (onSetStartOffset != null) MenuEntry(
        icon = R.drawable.ms_start,
        text = stringResource(R.string.lyrics_set_start),
        secondaryText = stringResource(R.string.lyrics_set_start_description),
        onClick = entry(onSetStartOffset)
    )
}
