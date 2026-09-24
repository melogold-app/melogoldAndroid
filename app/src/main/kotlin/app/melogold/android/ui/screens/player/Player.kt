@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
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
import app.melogold.android.ui.components.themed.MediaItemMenuHeader
import app.melogold.android.ui.components.themed.Menu
import app.melogold.android.ui.components.themed.MenuDivider
import app.melogold.android.ui.components.themed.MenuEntry
import app.melogold.android.ui.components.themed.MenuEntryTextStart
import app.melogold.android.ui.components.themed.MenuSectionTitle
import app.melogold.android.ui.components.themed.TrackMenuEntries
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
private val speedPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * @param collapsedBottomExtra the space under the mini player that other content covers: the
 * navigation bar of the app shell (REDESIGN-M3E §5.4), 0 dp with the navigation rail
 */
@Composable
fun Player(
    layoutState: BottomSheetState,
    modifier: Modifier = Modifier,
    collapsedBottomExtra: Dp = 0.dp,
    windowInsets: WindowInsets = WindowInsets.systemBars
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

    var speedDialogOpen by rememberSaveable { mutableStateOf(false) }

    /** Opens the player menu; [onStreamInfo] is only there while the player is expanded. */
    fun openPlayerMenu(onStreamInfo: (() -> Unit)?) {
        val item = mediaItem ?: return
        val service = binder ?: return

        menuState.display {
            PlayerMenu(
                binder = service,
                mediaItem = item,
                onDismiss = menuState::hide,
                onNavigate = { if (layoutState.expanded) layoutState.collapseSoft() },
                onCustomSpeed = { speedDialogOpen = true },
                onStreamInfo = onStreamInfo
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
                onMenu = { openPlayerMenu(onStreamInfo = null) },
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
                openPlayerMenu = { onStreamInfo -> openPlayerMenu(onStreamInfo) }
            )
        }
    }

    if (speedDialogOpen) SpeedDialog(onDismiss = { speedDialogOpen = false })
}

/**
 * The ⋮ menu of the player (REWRITE §3.10.5): "Track" without ♡ (it is on screen), then
 * "Playback" with the sleep timer and the speed as chips and the stream info. The equalizer lives
 * in Settings.
 */
@Composable
private fun PlayerMenu(
    binder: PlayerService.Binder,
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit,
    onCustomSpeed: () -> Unit,
    onStreamInfo: (() -> Unit)?
) {
    Menu(modifier = Modifier.testTag("player_menu")) {
        MediaItemMenuHeader(mediaItem = mediaItem)

        MenuSectionTitle(text = stringResource(R.string.menu_section_track))
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
            trackRadio = true
        )

        MenuDivider()
        MenuSectionTitle(text = stringResource(R.string.menu_section_playback))
        SleepTimerRow(binder = binder)
        SpeedRow(
            onCustom = {
                onDismiss()
                onCustomSpeed()
            }
        )
        onStreamInfo?.let {
            MenuEntry(
                icon = R.drawable.ms_info,
                text = stringResource(R.string.menu_stream_info),
                onClick = {
                    onDismiss()
                    it()
                }
            )
        }
    }
}

/** A row of the "Playback" group: an icon, a title with the current value, then a row of chips. */
@Composable
private fun ChipsRow(
    @DrawableRes icon: Int,
    title: String,
    value: String?,
    trailing: (@Composable () -> Unit)? = null,
    chips: @Composable () -> Unit
) = Column(modifier = Modifier.fillMaxWidth()) {
    ListItem(
        supportingContent = value?.let { { Text(text = it) } },
        trailingContent = trailing,
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ) {
        Text(text = title)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = MenuEntryTextStart, end = 16.dp, bottom = 4.dp)
    ) {
        chips()
    }
}

/**
 * The sleep timer (REWRITE §3.10.7): chips 15 · 30 · 45 · 60 min and "End of track"; while it runs,
 * "23 min left" and "Turn off timer".
 */
@Composable
private fun SleepTimerRow(binder: PlayerService.Binder) {
    val millisLeft = binder.sleepTimerLeft()
    // Which chip started the running timer; unknown after the menu is opened again
    var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
    val running = millisLeft != null

    ChipsRow(
        icon = R.drawable.ms_bedtime,
        title = stringResource(R.string.menu_sleep_timer),
        value = millisLeft?.let { stringResource(R.string.menu_sleep_timer_left, it.minutesLeft()) },
        trailing = if (running) {
            {
                TextButton(
                    onClick = {
                        chosen = null
                        binder.cancelSleepTimer()
                    }
                ) { Text(text = stringResource(R.string.menu_sleep_timer_stop)) }
            }
        } else null
    ) {
        sleepTimerMinutes.forEach { minutes ->
            FilterChip(
                selected = running && chosen == minutes,
                onClick = {
                    chosen = minutes
                    binder.startSleepTimer(minutes * 60_000L)
                },
                label = { Text(text = stringResource(R.string.menu_sleep_timer_minutes, minutes)) }
            )
        }
        FilterChip(
            selected = running && chosen == 0,
            onClick = {
                chosen = 0
                runCatching {
                    binder.startSleepTimer(binder.player.duration - binder.player.contentPosition)
                }
            },
            label = { Text(text = stringResource(R.string.menu_sleep_timer_end_of_track)) }
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

/** The playback speed as chips, with "Custom…" for anything in between. */
@Composable
private fun SpeedRow(onCustom: () -> Unit) {
    val speed = PlayerPreferences.speed

    ChipsRow(
        icon = R.drawable.ms_speed,
        title = stringResource(R.string.menu_speed),
        value = stringResource(R.string.menu_speed_value, formatSpeed(speed))
    ) {
        speedPresets.forEach { preset ->
            FilterChip(
                selected = abs(speed - preset) < 0.01f,
                onClick = { PlayerPreferences.speed = preset },
                label = { Text(text = stringResource(R.string.menu_speed_value, formatSpeed(preset))) }
            )
        }
        FilterChip(
            selected = speedPresets.none { abs(speed - it) < 0.01f },
            onClick = onCustom,
            label = { Text(text = stringResource(R.string.menu_speed_custom)) }
        )
    }
}

internal fun formatSpeed(speed: Float): String = NumberFormat.getInstance().apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 2
}.format(speed)

/** Any speed from 0.25× to 2× in steps of 0.05×. */
@Composable
private fun SpeedDialog(onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(PlayerPreferences.speed.coerceIn(0.25f, 2f)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.menu_speed)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.menu_speed_value, formatSpeed(value)),
                    style = MaterialTheme.typography.headlineSmall
                )
                Slider(
                    value = value,
                    onValueChange = { value = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { PlayerPreferences.speed = value },
                    valueRange = 0.25f..2f,
                    steps = 34
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    PlayerPreferences.speed = value
                    onDismiss()
                }
            ) { Text(text = stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    value = 1f
                    PlayerPreferences.speed = 1f
                }
            ) { Text(text = stringResource(R.string.reset)) }
        }
    )
}

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
