package app.melogold.android.ui.components.menu

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.models.Info
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.utils.addNext
import app.melogold.android.utils.enqueue

/**
 * The menu of a collection (REWRITE §3.11.5): what can be done with all of its [mediaItems] at
 * once — queue them, add them to a playlist, start its radio, open its artists, share it.
 * [content] adds the entries only some collections have.
 *
 * Every entry hides the menu, except "Add to playlist…", which turns the sheet into the picker.
 */
@Composable
fun CollectionMenu(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    mediaItems: List<MediaItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    radioLabel: String? = null,
    onStartRadio: (() -> Unit)? = null,
    artists: List<Info> = emptyList(),
    shareUrl: String? = null,
    content: @Composable ColumnScope.() -> Unit = { }
) = Menu(modifier = modifier.testTag("collection_menu")) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val hasTracks = mediaItems.isNotEmpty()

    fun entry(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    MenuHeader(title = title, subtitle = subtitle, artworkUrl = artworkUrl)

    MenuEntry(
        icon = R.drawable.ms_playlist_play,
        text = stringResource(R.string.menu_play_next),
        onClick = entry { binder?.player?.addNext(mediaItems) },
        enabled = hasTracks
    )

    MenuEntry(
        icon = R.drawable.ms_queue_music,
        text = stringResource(R.string.menu_add_to_queue),
        onClick = entry { binder?.player?.enqueue(mediaItems) },
        enabled = hasTracks
    )

    MenuEntry(
        icon = R.drawable.ms_playlist_add,
        text = stringResource(R.string.menu_add_to_playlist),
        onClick = {
            menuState.display { AddAllToPlaylistMenu(mediaItems = mediaItems, name = title, onDone = menuState::hide) }
        },
        enabled = hasTracks
    )

    onStartRadio?.let {
        MenuEntry(
            icon = R.drawable.ms_sensors,
            text = radioLabel ?: stringResource(R.string.menu_start_radio),
            onClick = entry(it)
        )
    }

    artists.forEach { (id, name) ->
        MenuEntry(
            icon = R.drawable.ms_person,
            text = stringResource(R.string.menu_go_to_artist),
            secondaryText = name,
            onClick = entry { artistRoute.global(id) }
        )
    }

    shareUrl?.let { url ->
        MenuEntry(
            icon = R.drawable.ms_share,
            text = stringResource(R.string.menu_share),
            onClick = entry { context.shareLink(url) }
        )
    }

    content()
}

private fun Context.shareLink(url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }

    startActivity(Intent.createChooser(intent, null))
}
