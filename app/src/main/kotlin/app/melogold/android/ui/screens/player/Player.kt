@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.melogold.android.Database
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.query
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.components.BottomSheet
import app.melogold.android.ui.components.BottomSheetState
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.MediaItemMenuHeader
import app.melogold.android.ui.components.menu.Menu
import app.melogold.android.ui.components.menu.MenuDivider
import app.melogold.android.ui.components.menu.MenuEntry
import app.melogold.android.ui.components.menu.MenuEntryTextStart
import app.melogold.android.ui.components.menu.MenuSectionTitle
import app.melogold.android.ui.components.menu.TrackMenuEntries
import app.melogold.android.ui.screens.player.modern.ModernPlayer
import app.melogold.android.ui.shell.AppSnackbar
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.seamlessPlay
import app.melogold.android.utils.shouldBePlaying
import app.melogold.android.utils.windowState
import app.melogold.compose.persist.PersistMapCleanup
import app.melogold.compose.routing.OnGlobalRoute
import app.melogold.core.ui.utils.songBundle
import app.melogold.providers.innertube.models.NavigationEndpoint
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

private val sleepTimerMinutes = listOf(15, 30, 45, 60)

/**
 * @param collapsedBottomExtra the space under the mini player that other content covers: the
 * navigation bar of the app shell (REDESIGN-M3E §5.4), 0 dp with the navigation rail
 */
@Composable
fun Player(
    layoutState: BottomSheetState,
    modifier: Modifier = Modifier,
    collapsedBottomExtra: Dp = 0.dp,
    windowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
) {
    val menuState = LocalMenuState.current
    val binder = LocalPlayerServiceBinder.current

    PersistMapCleanup(prefix = "queue/suggestions")

    val snackbar = LocalAppSnackbar.current
    val stoppedMessage = stringResource(R.string.player_stopped)

    var mediaItem by remember(binder) {
        mutableStateOf(
            value = binder?.player?.currentMediaItem,
            policy = neverEqualPolicy()
        )
    }
    var shouldBePlaying by remember(binder) { mutableStateOf(binder?.player?.playingInUi == true) }

    var likedAt by remember(mediaItem) {
        mutableStateOf(
            value = null,
            policy = object : SnapshotMutationPolicy<Long?> {
                override fun equivalent(a: Long?, b: Long?): Boolean {
                    mediaItem?.mediaId?.let {
                        query {
                            Database.like(it, b)
                        }
                    }
                    return a == b
                }
            }
        )
    }

    LaunchedEffect(mediaItem) {
        mediaItem?.mediaId?.let { mediaId ->
            Database
                .likedAt(mediaId)
                .distinctUntilChanged()
                .collect { likedAt = it }
        }
    }

    binder?.player.DisposableListener {
        object : Player.Listener {
            override fun onMediaItemTransition(newMediaItem: MediaItem?, reason: Int) {
                mediaItem = newMediaItem
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldBePlaying = player.playingInUi
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                shouldBePlaying = player.playingInUi
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                shouldBePlaying = player.playingInUi
            }
        }
    }

    val metadata = remember(mediaItem) { mediaItem?.mediaMetadata }
    val extras = remember(metadata) { metadata?.extras?.songBundle }

    val horizontalBottomPaddingValues = windowInsets
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        .asPaddingValues()

    OnGlobalRoute { if (layoutState.expanded) layoutState.collapseSoft() }

    /** Opens the player menu; [extras] come from the expanded player. */
    fun openPlayerMenu(extras: PlayerMenuExtras?) {
        val item = mediaItem ?: return
        val service = binder ?: return

        menuState.display {
            PlayerMenu(
                binder = service,
                mediaItem = item,
                onDismiss = menuState::hide,
                onNavigate = { if (layoutState.expanded) layoutState.collapseSoft() },
                extras = extras
            )
        }
    }

    if (mediaItem != null) BottomSheet(
        state = layoutState,
        modifier = modifier.fillMaxSize(),
        onDismiss = {
            binder?.let { stopWithUndo(it, snackbar, stoppedMessage) }
            layoutState.dismissSoft()
        },
        backHandlerEnabled = !menuState.isDisplayed,
        collapsedContent = { _ ->
            val error = windowState(binder).second
            MiniPlayer(
                binder = binder,
                metadata = metadata,
                explicit = extras?.explicit == true,
                shouldBePlaying = shouldBePlaying,
                onExpand = layoutState::expandSoft,
                onMenu = { openPlayerMenu(extras = null) },
                error = mediaItem?.takeIf { error != null }?.let { playbackErrorMessage(it, error) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontalBottomPaddingValues)
                    .padding(bottom = collapsedBottomExtra)
            )
        }
    ) {
        mediaItem?.let { currentMediaItem ->
            if (binder != null) ModernPlayer(
                layoutState = layoutState,
                binder = binder,
                mediaItem = currentMediaItem,
                likedAt = likedAt,
                setLikedAt = { likedAt = it },
                shouldBePlaying = shouldBePlaying,
                openPlayerMenu = { extras -> openPlayerMenu(extras) }
            )
        }
    }
}

/** What the expanded player adds to its ⋮ menu. */
class PlayerMenuExtras(
    /** The "Lyrics" group, shown while [showLyrics]. */
    val lyrics: @Composable ColumnScope.() -> Unit,
    val showLyrics: Boolean,
    /** An action on what was long-pressed (a lyrics line), above the groups. */
    val top: (@Composable ColumnScope.() -> Unit)? = null
)

/**
 * The ⋮ menu of the player (REWRITE §3.10.5), one short list without section titles: the track
 * without ♡ (it is on screen) with the album and the artists, the lyrics while they are shown, the
 * sleep timer, then what hides the track. The speed, the stream info and the equalizer are in
 * Settings › Player.
 */
@Composable
private fun PlayerMenu(
    binder: PlayerService.Binder,
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit,
    extras: PlayerMenuExtras?
) {
    Menu(modifier = Modifier.testTag("player_menu")) {
        MediaItemMenuHeader(mediaItem = mediaItem)
        extras?.top?.invoke(this)

        TrackMenuEntries(
            mediaItem = mediaItem,
            onDismiss = onDismiss,
            onStartRadio = {
                binder.stopRadio()
                binder.player.seamlessPlay(mediaItem)
                binder.setupRadio(NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId))
            },
            onNavigate = onNavigate,
            // A hidden track is skipped (REWRITE §3.10.5)
            onHidden = { binder.player.forceSeekToNext() },
            showFavorite = false,
            trackRadio = true,
            beforeRemovals = {
                if (extras?.showLyrics == true) {
                    MenuDivider()
                    extras.lyrics(this)
                }
                MenuDivider()
                SleepTimerEntry(binder = binder)
            }
        )
    }
}

/** The sleep timer as one row (REWRITE §3.10.7): its time left; a tap shows the choices in its place. */
@Composable
private fun SleepTimerEntry(binder: PlayerService.Binder) {
    val menuState = LocalMenuState.current
    val millisLeft = binder.sleepTimerLeft()

    MenuEntry(
        icon = R.drawable.ms_bedtime,
        text = stringResource(R.string.menu_sleep_timer),
        secondaryText = millisLeft?.let { stringResource(R.string.menu_sleep_timer_left, it.minutesLeft()) },
        onClick = { menuState.display { SleepTimerMenu(binder = binder) } }
    )
}

/**
 * The choices of the sleep timer, in the menu sheet: 15 · 30 · 45 · 60 min and "End of track";
 * while it runs, "Turn off timer" first. Also opened by the timer chip of the player.
 */
@Composable
internal fun SleepTimerMenu(binder: PlayerService.Binder) {
    val menuState = LocalMenuState.current
    val millisLeft = binder.sleepTimerLeft()

    fun choose(action: () -> Unit): () -> Unit = {
        menuState.hide()
        action()
    }

    Menu(modifier = Modifier.testTag("sleep_timer_menu")) {
        MenuSectionTitle(
            text = millisLeft?.let { stringResource(R.string.menu_sleep_timer_left, it.minutesLeft()) }
                ?: stringResource(R.string.menu_sleep_timer)
        )
        if (millisLeft != null) MenuEntry(
            icon = R.drawable.ms_timer_off,
            text = stringResource(R.string.menu_sleep_timer_stop),
            onClick = choose(binder::cancelSleepTimer)
        )
        sleepTimerMinutes.forEach { minutes ->
            MenuEntry(
                icon = R.drawable.ms_bedtime,
                text = stringResource(R.string.menu_sleep_timer_minutes, minutes),
                onClick = choose { binder.startSleepTimer(minutes * 60_000L) }
            )
        }
        MenuEntry(
            icon = R.drawable.ms_bedtime,
            text = stringResource(R.string.menu_sleep_timer_end_of_track),
            onClick = choose {
                runCatching { binder.startSleepTimer(binder.player.duration - binder.player.contentPosition) }
            }
        )
    }
}

/**
 * The time left on the sleep timer, or null while none runs. Every timer has its own flow, and the
 * binder's getter reads snapshot state, so a new timer is picked up.
 */
@Composable
internal fun PlayerService.Binder.sleepTimerLeft(): Long? {
    val flow = sleepTimerMillisLeft

    return produceState(initialValue = flow?.value, flow) {
        if (flow == null) value = null else flow.collect { value = it }
    }.value
}

/** Whole minutes left, rounded up: a timer with 30 s left still shows "1 min". */
internal fun Long.minutesLeft() = ((this + 59_999) / 60_000).toInt()

/** "1,25" for the speed 1.25 (with "×" from `menu_speed_value`). */
internal fun formatSpeed(speed: Float): String = NumberFormat.getInstance().apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 2
}.format(speed)

/**
 * Whether the play buttons show "pause": a track that failed is not playing, even though the
 * player still wants to (play then prepares it again).
 */
private val Player.playingInUi get() = shouldBePlaying && playerError == null

/**
 * Swiping the mini player down stops playback and clears the queue; "Undo" brings the queue, the
 * track and the position back (REWRITE §3.10.1, VT#177).
 */
private fun stopWithUndo(binder: PlayerService.Binder, snackbar: AppSnackbar, message: String) {
    val player = binder.player
    val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
    val index = player.currentMediaItemIndex
    val position = player.currentPosition

    binder.stopRadio()
    player.clearMediaItems()

    if (items.isNotEmpty()) snackbar.showUndo(message) {
        player.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
        player.prepare()
    }
}
