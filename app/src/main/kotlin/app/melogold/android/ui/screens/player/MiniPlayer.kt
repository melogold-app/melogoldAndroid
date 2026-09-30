@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.melogold.android.R
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.nextTrackIndex
import app.melogold.android.utils.positionAndDurationState
import app.melogold.android.utils.previousTrackIndex
import app.melogold.core.ui.utils.songBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * The mini player (REWRITE §3.10.1): 64 dp on `surfaceContainerHigh` with 16 dp top corners,
 * artwork, title and artist, play/pause and next, the progress as a 2 dp line along the top edge.
 * Tap expands, a long tap opens the track menu; swiping down (the sheet) stops playback.
 *
 * A sideways swipe pages the tracks like a pager (2026-09-30: it used to push the whole row off to one side and
 * bring the new track back from the same side): the track and its neighbours sit side by side, the finger drags
 * them, and the one that comes in arrives from the other side ([SwipeableTrack]).
 */
@Composable
fun MiniPlayer(
    binder: PlayerService.Binder?,
    metadata: MediaMetadata?,
    explicit: Boolean,
    shouldBePlaying: Boolean,
    onExpand: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null
) = MiniPlayerContent(
    player = binder?.player,
    metadata = metadata,
    explicit = explicit,
    shouldBePlaying = shouldBePlaying,
    onExpand = onExpand,
    onMenu = onMenu,
    modifier = modifier,
    error = error
)

/** [MiniPlayer] over a [Player]: the service binder is only the way to it, tests bring their own. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MiniPlayerContent(
    player: Player?,
    metadata: MediaMetadata?,
    explicit: Boolean,
    shouldBePlaying: Boolean,
    onExpand: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null
) {
    val scope = rememberCoroutineScope()
    val pager = remember(player) { MiniPager(scope) }
    val live = rememberNeighbours(player)
    val (position, duration) = player.positionAndDurationState()
    val progress = if (duration > 0) (position.toFloat() / duration.absoluteValue).coerceIn(0f, 1f) else 0f
    val progressColor = MaterialTheme.colorScheme.primary

    val previousLabel = stringResource(R.string.skip_back)
    val nextLabel = stringResource(R.string.skip_forward)
    val menuLabel = stringResource(R.string.more_options)
    val current = TrackLine(
        key = player?.currentMediaItem?.mediaId.orEmpty(),
        title = metadata?.title?.toString().orEmpty(),
        subtitle = error ?: metadata?.artist?.toString().orEmpty(),
        artworkUrl = metadata?.artworkUri?.toString(),
        explicit = explicit && error == null,
        isError = error != null
    )

    // What is shown: the lines as they stood when the swipe was let go while a page lands, else the live ones
    val shown = pager.frozen ?: Frozen(live.previous, current, live.next)
    SideEffect { pager.update(player, shown) }
    LaunchedEffect(live.key) { pager.onTrackChanged() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = modifier.drawBehind {
                drawRect(
                    color = progressColor,
                    topLeft = Offset.Zero,
                    size = Size(width = size.width * progress, height = 2.dp.toPx())
                )
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .combinedClickable(onClick = onExpand, onLongClick = onMenu)
                    .swipesTracks(pager)
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(previousLabel) { player?.forceSeekToPrevious(); true },
                            CustomAccessibilityAction(nextLabel) { player?.forceSeekToNext(); true },
                            CustomAccessibilityAction(menuLabel) { onMenu(); true }
                        )
                    }
                    .padding(start = 8.dp, end = 4.dp)
                    .testTag("mini_player")
            ) {
                SwipeableTrack(
                    pager = pager,
                    shown = shown,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                FilledTonalIconToggleButton(
                    checked = shouldBePlaying,
                    onCheckedChange = { play ->
                        if (!play) player?.pause()
                        else {
                            if (player?.playbackState == Player.STATE_IDLE) player.prepare()
                            player?.play()
                        }
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.testTag("mini_player_play")
                ) {
                    Icon(
                        painter = painterResource(if (shouldBePlaying) R.drawable.ms_pause_fill else R.drawable.ms_play_arrow_fill),
                        contentDescription = stringResource(if (shouldBePlaying) R.string.pause else R.string.play)
                    )
                }

                IconButton(onClick = { player?.forceSeekToNext() }) {
                    Icon(
                        painter = painterResource(R.drawable.ms_skip_next_fill),
                        contentDescription = nextLabel
                    )
                }
            }
        }
    }
}

/** What one slot of the mini player shows: the artwork, the title and the artist (or the error of the track). */
internal data class TrackLine(
    val key: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val explicit: Boolean,
    val isError: Boolean = false
)

private fun MediaItem.toLine() = TrackLine(
    key = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    subtitle = mediaMetadata.artist?.toString().orEmpty(),
    artworkUrl = mediaMetadata.artworkUri?.toString(),
    explicit = mediaMetadata.extras?.songBundle?.explicit == true
)

/** The tracks a swipe brings in: what [forceSeekToPrevious] and [forceSeekToNext] land on, in the playing order. */
private data class Neighbours(val key: String, val previous: TrackLine?, val next: TrackLine?)

private fun Player.neighbours() = Neighbours(
    key = "$currentMediaItemIndex:${currentMediaItem?.mediaId.orEmpty()}",
    previous = previousTrackIndex()?.let { getMediaItemAt(it).toLine() },
    next = nextTrackIndex()?.let { getMediaItemAt(it).toLine() }
)

@Composable
private fun rememberNeighbours(player: Player?): Neighbours {
    var neighbours by remember(player) { mutableStateOf(player?.neighbours() ?: Neighbours("", null, null)) }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (
                    events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_PLAYLIST_METADATA_CHANGED
                    )
                ) neighbours = player.neighbours()
            }
        }
        neighbours = player.neighbours()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return neighbours
}

/** Where a swipe that ended at [offset] (px, negative = to the left) with [velocity] (px/s) goes. */
internal enum class MiniSwipe { Stay, Previous, Next }

/**
 * A swipe pages to the next track when the content, carried on by the speed of the finger for a moment, would be
 * over 40 % of the width to the left (previous: to the right); otherwise it comes back. Where there is no track on
 * that side it always comes back.
 */
internal fun decideMiniSwipe(offset: Float, velocity: Float, width: Float, hasPrevious: Boolean, hasNext: Boolean): MiniSwipe {
    if (width <= 0f) return MiniSwipe.Stay
    val projected = offset + velocity * SWIPE_CARRY_SECONDS
    return when {
        projected <= -width * SWIPE_PAGE_SHARE && hasNext -> MiniSwipe.Next
        projected >= width * SWIPE_PAGE_SHARE && hasPrevious -> MiniSwipe.Previous
        else -> MiniSwipe.Stay
    }
}

private const val SWIPE_CARRY_SECONDS = 0.18f
private const val SWIPE_PAGE_SHARE = 0.4f

/** A drag toward a side without a track moves the content this much of the finger's way: it gives, and comes back. */
private const val SWIPE_EDGE_RESISTANCE = 0.3f

/** What the row showed when the swipe was let go: the lines stand still while the player switches under them. */
private data class Frozen(val previous: TrackLine?, val current: TrackLine, val next: TrackLine?)

/**
 * The paging of the mini player: how far the finger has dragged the tracks, and what happens when it lets go. It
 * belongs to the whole row, not only to the track area: a swipe that starts over the play button pages too (as the
 * mini player always did), and the buttons stay where they are.
 *
 * Plain state, not an `Animatable`: the last frame of a page (the lines swap, the offset goes to 0) must be one write,
 * or a frame shows the track after the next one in the middle.
 */
@Stable
private class MiniPager(private val scope: CoroutineScope) {
    /** Width of the track area: one page. */
    var width by mutableIntStateOf(0)
    var offset by mutableFloatStateOf(0f)
    var frozen by mutableStateOf<Frozen?>(null)
    private var animation: Job? = null

    // What the composition shows now; the gesture reads it when the finger moves or lets go
    private var player: Player? = null
    var shown = Frozen(null, TrackLine("", "", "", null, false), null)
        private set

    fun update(player: Player?, shown: Frozen) {
        this.player = player
        this.shown = shown
    }

    fun onDrag(delta: Float) {
        val moved = offset + delta
        val blocked = (moved < 0 && shown.next == null) || (moved > 0 && shown.previous == null)
        offset = (offset + if (blocked) delta * SWIPE_EDGE_RESISTANCE else delta).coerceIn(-width.toFloat(), width.toFloat())
    }

    fun onDragStarted() {
        animation?.cancel()
        // A page that was still landing is over: the new track is what is shown now
        if (frozen != null) {
            offset = 0f
            frozen = null
        }
    }

    /** The track changed from outside (the next one starts by itself): a drag that was in progress is dropped. */
    fun onTrackChanged() {
        if (frozen == null) offset = 0f
    }

    fun settle(velocity: Float) {
        val now = shown
        val decision = decideMiniSwipe(offset, velocity, width.toFloat(), now.previous != null, now.next != null)
        animation?.cancel()
        animation = scope.launch {
            when (decision) {
                MiniSwipe.Stay -> animate(
                    initialValue = offset,
                    targetValue = 0f,
                    initialVelocity = velocity,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
                ) { value, _ -> offset = value }

                MiniSwipe.Next, MiniSwipe.Previous -> {
                    val next = decision == MiniSwipe.Next
                    // The lines stand as they were while the player switches (audio starts at once, the picture
                    // follows in the same motion); then the real ones take over at offset 0 in one write
                    frozen = now
                    if (next) player?.forceSeekToNext() else player?.forceSeekToPrevious(seekToStart = false)
                    animate(
                        initialValue = offset,
                        targetValue = if (next) -width.toFloat() else width.toFloat(),
                        initialVelocity = velocity,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)
                    ) { value, _ -> offset = value }
                    offset = 0f
                    frozen = null
                }
            }
        }
    }
}

/** The sideways swipe of the whole row: the tracks follow the finger ([MiniPager]). */
private fun Modifier.swipesTracks(pager: MiniPager) = draggable(
    orientation = Orientation.Horizontal,
    state = DraggableState { delta -> pager.onDrag(delta) },
    onDragStarted = { pager.onDragStarted() },
    onDragStopped = { velocity -> pager.settle(velocity) }
)

/**
 * The artwork, title and artist of the playing track, paged by a sideways swipe. The previous and the next track stand
 * beside it, one width away; the finger drags all three, and what comes in arrives from the far side while the old
 * track leaves on the near one. Let go past 40 % (or with speed) — the player switches and the row lands on the new
 * track without a jump; let go short — it comes back.
 */
@Composable
private fun SwipeableTrack(pager: MiniPager, shown: Frozen, modifier: Modifier = Modifier) {
    Slots(
        offset = { pager.offset },
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { pager.width = it.width }
    ) {
        shown.previous?.let {
            TrackSlot(
                line = it,
                modifier = Modifier
                    .layoutId(SLOT_PREVIOUS)
                    // Not read out: the track that is not playing stands off screen (the tag stays for tests)
                    .clearAndSetSemantics { testTag = "mini_player_previous" }
            )
        }
        TrackSlot(
            line = shown.current,
            modifier = Modifier
                .layoutId(SLOT_CURRENT)
                .testTag("mini_player_current")
        )
        shown.next?.let {
            TrackSlot(
                line = it,
                modifier = Modifier
                    .layoutId(SLOT_NEXT)
                    .clearAndSetSemantics { testTag = "mini_player_next" }
            )
        }
    }
}

private const val SLOT_PREVIOUS = -1
private const val SLOT_CURRENT = 0
private const val SLOT_NEXT = 1

/**
 * The previous, the current and the next track side by side: each as wide as the row, the current one at [offset]
 * (px, read while placing, so a drag moves them without recomposing), the others one width to either side of it.
 */
@Composable
private fun Slots(offset: () -> Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val fixed = Constraints.fixed(w, h)
        val placed = measurables.map { (it.layoutId as? Int ?: SLOT_CURRENT) to it.measure(fixed) }
        layout(w, h) {
            val o = offset().roundToInt()
            placed.forEach { (slot, placeable) -> placeable.place(x = o + slot * w, y = 0) }
        }
    }
}

/** One track of the mini player: artwork, title, artist (an error of the track replaces the artist, §3.10.9). */
@Composable
private fun TrackSlot(line: TrackLine, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().fillMaxHeight()
    ) {
        Artwork(
            url = line.artworkUrl,
            size = 48.dp,
            shape = RoundedCornerShape(12.dp)
        )

        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Text(
                text = line.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Keyed by the track: a change of the track is the swipe's business, only an error coming and going
            // crossfades
            key(line.key) {
                AnimatedContent(
                    targetState = line.subtitle to line.isError,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = ""
                ) { (subtitle, isError) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (line.explicit && !isError) Icon(
                            painter = painterResource(R.drawable.explicit),
                            contentDescription = stringResource(R.string.kit_explicit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
