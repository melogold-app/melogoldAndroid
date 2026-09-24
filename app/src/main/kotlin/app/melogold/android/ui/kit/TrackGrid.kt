package app.melogold.android.ui.kit

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.playingSong
import app.melogold.providers.innertube.Innertube

private val TrackRowHeight = 72.dp

/**
 * Tracks in [rows] rows that scroll sideways, the next column peeking in ("Trending", "For you").
 * A tap plays the whole list from that track.
 *
 * @param numbered shows chart positions in front of the covers
 */
@Composable
fun TrackGrid(
    songs: List<Innertube.SongItem>,
    itemWidth: Dp,
    modifier: Modifier = Modifier,
    rows: Int = 4,
    numbered: Boolean = false
) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val (currentMediaId, playing) = playingSong(binder)
    val gridState = rememberLazyGridState()

    LazyHorizontalGrid(
        state = gridState,
        rows = GridCells.Fixed(rows),
        contentPadding = PaddingValues(horizontal = 8.dp),
        flingBehavior = rememberSnapFlingBehavior(
            lazyGridState = gridState,
            snapPosition = SnapPosition.Start
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(TrackRowHeight * rows)
    ) {
        itemsIndexed(items = songs, key = { _, song -> song.key }) { index, song ->
            TrackRow(
                title = song.info?.name.orEmpty(),
                subtitle = song.authors?.joinToString { it.name.orEmpty() },
                artworkUrl = song.thumbnail?.url,
                number = if (numbered) index + 1 else null,
                explicit = song.explicit,
                isPlaying = playing && currentMediaId == song.key,
                onClick = {
                    binder?.stopRadio()
                    binder?.player?.forcePlayAtIndex(songs.map { it.asMediaItem }, index)
                },
                onMenu = {
                    menuState.display {
                        NonQueuedMediaItemMenu(
                            onDismiss = menuState::hide,
                            mediaItem = song.asMediaItem
                        )
                    }
                },
                modifier = Modifier.width(itemWidth)
            )
        }
    }
}
