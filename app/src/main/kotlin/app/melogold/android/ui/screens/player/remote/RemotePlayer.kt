@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package app.melogold.android.ui.screens.player.remote

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.data.repo.toSong
import app.melogold.android.sync.remote.RemoteNow
import app.melogold.android.sync.remote.RemoteTarget
import app.melogold.android.sync.remote.ServerClock
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.deviceIcon
import app.melogold.android.ui.kit.deviceKindName
import app.melogold.android.ui.screens.player.modern.ArtworkTintedBackground
import app.melogold.android.ui.screens.player.modern.PlayerArtwork
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.formatAsDuration
import app.melogold.android.utils.rememberReduceMotion
import kotlinx.coroutines.delay

/** How often the position of a playing track is worked out again: twice a second is smooth enough for a bar. */
private const val TICK_MS = 500L

/** The volume command goes this long after the finger stops (tasks/0018). */
const val VOLUME_SETTLE_MS = 150L

/** After a seek or a volume change the bar keeps what the finger chose until the device reports its own. */
private const val SETTLE_MS = 1_500L

/**
 * The mini player while the player is the remote of another device (tasks/0018): the device's icon in front of the track,
 * play/pause and next as commands to it. A tap opens the remote.
 */
@Composable
fun RemoteMiniPlayer(
    target: RemoteTarget,
    now: RemoteNow?,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val position by rememberRemotePosition(now)
    val duration = now?.durationMs ?: 0L
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val progressColor = MaterialTheme.colorScheme.primary
    val track = now?.track
    val playing = now?.playing == true

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
                    .combinedClickable(onClick = onExpand)
                    .padding(start = 8.dp, end = 4.dp)
                    .testTag("remote_mini_player")
            ) {
                Artwork(url = track?.thumbnailUrl, size = 48.dp, shape = RoundedCornerShape(12.dp))

                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Text(
                        text = track?.title ?: stringResource(R.string.remote_nothing_playing, target.name),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // The icon of the device it plays on, then who it is
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            painter = painterResource(deviceIcon(target.platform)),
                            contentDescription = stringResource(deviceKindName(target.platform)),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(14.dp)
                                .testTag("remote_mini_device")
                        )
                        Text(
                            text = target.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                FilledTonalIconToggleButton(
                    checked = playing,
                    onCheckedChange = { onPlayPause() },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.testTag("remote_mini_play")
                ) {
                    Icon(
                        painter = painterResource(if (playing) R.drawable.ms_pause_fill else R.drawable.ms_play_arrow_fill),
                        contentDescription = stringResource(if (playing) R.string.pause else R.string.play)
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.testTag("remote_mini_next")) {
                    Icon(painter = painterResource(R.drawable.ms_skip_next_fill), contentDescription = stringResource(R.string.skip_forward))
                }
            }
        }
    }
}

/** The current position of the controlled device: worked out from its last state, moving on while it plays. */
@Composable
fun rememberRemotePosition(now: RemoteNow?) = produceState(initialValue = now?.positionAt(ServerClock.now()) ?: 0L, now) {
    while (true) {
        value = now?.positionAt(ServerClock.now()) ?: 0L
        delay(TICK_MS)
    }
}

/**
 * The expanded player while it is the remote of another device (tasks/0018): a banner "Playing on «MacBook Air»" with
 * "Listen here" and "Disconnect"; the cover, the title, the progress and the volume of that device; play/pause,
 * previous, next and the seek bar send commands to it. The colors come from its cover.
 */
@Composable
fun RemotePlayer(
    target: RemoteTarget,
    now: RemoteNow?,
    artworkScheme: ColorScheme?,
    onCollapse: () -> Unit,
    onDevices: () -> Unit,
    onListenHere: () -> Unit,
    onDisconnect: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = artworkScheme ?: MaterialTheme.colorScheme
    val mediaItem = remember(now?.track) { now?.track?.toSong()?.asMediaItem }
    val reduceMotion = rememberReduceMotion()

    MaterialTheme(colorScheme = scheme) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("remote_player")
        ) {
            ArtworkTintedBackground()

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wide = maxWidth > maxHeight
                val content: @Composable () -> Unit = {
                    RemoteControls(
                        target = target,
                        now = now,
                        onDevices = onDevices,
                        onPlayPause = onPlayPause,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onSeek = onSeek,
                        onVolume = onVolume
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = onCollapse, modifier = Modifier.testTag("remote_collapse")) {
                            Icon(
                                painter = painterResource(R.drawable.ms_keyboard_arrow_down),
                                contentDescription = stringResource(R.string.kit_back)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    RemoteBanner(target = target, onListenHere = onListenHere, onDisconnect = onDisconnect)

                    if (wide) Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            RemoteArtwork(mediaItem = mediaItem, now = now, reduceMotion = reduceMotion, size = 240.dp)
                        }
                        Column(modifier = Modifier.weight(1f)) { content() }
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            RemoteArtwork(mediaItem = mediaItem, now = now, reduceMotion = reduceMotion, size = 300.dp)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteArtwork(mediaItem: androidx.media3.common.MediaItem?, now: RemoteNow?, reduceMotion: Boolean, size: androidx.compose.ui.unit.Dp) {
    if (mediaItem != null) PlayerArtwork(
        mediaItem = mediaItem,
        size = size,
        playing = now?.playing == true,
        reduceMotion = reduceMotion,
        onTap = { },
        square = true
    ) else Artwork(url = null, size = size, shape = RoundedCornerShape(28.dp))
}

/** "Playing on «MacBook Air»" with "Listen here" and "Disconnect". */
@Composable
fun RemoteBanner(target: RemoteTarget, onListenHere: () -> Unit, onDisconnect: () -> Unit, modifier: Modifier = Modifier) = Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    shape = RoundedCornerShape(20.dp),
    modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .testTag("remote_banner")
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painter = painterResource(deviceIcon(target.platform)),
                contentDescription = stringResource(deviceKindName(target.platform)),
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = stringResource(R.string.remote_playing_on, target.name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("remote_playing_on")
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = onListenHere,
                modifier = Modifier.testTag("remote_listen_here")
            ) { Text(text = stringResource(R.string.remote_listen_here)) }
            TextButton(onClick = onDisconnect, modifier = Modifier.testTag("remote_disconnect")) {
                Text(text = stringResource(R.string.remote_disconnect))
            }
        }
    }
}

/** Title, seek bar, transport, volume and the device button of the remote. */
@Composable
private fun RemoteControls(
    target: RemoteTarget,
    now: RemoteNow?,
    onDevices: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit
) = Column {
    val track = now?.track
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Text(
            text = track?.title ?: stringResource(R.string.remote_nothing_playing, target.name),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("remote_title")
        )
        track?.artistsText?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    RemoteScrubber(now = now, onSeek = onSeek)
    Spacer(modifier = Modifier.height(12.dp))
    RemoteTransport(playing = now?.playing == true, enabled = track != null, onPlayPause = onPlayPause, onPrevious = onPrevious, onNext = onNext)
    Spacer(modifier = Modifier.height(12.dp))
    RemoteVolume(volume = now?.volume, onVolume = onVolume)
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.padding(horizontal = 24.dp)) {
        AssistChip(
            onClick = onDevices,
            label = { Text(text = target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(
                    painter = painterResource(deviceIcon(target.platform)),
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                )
            },
            modifier = Modifier.testTag("remote_devices_button")
        )
    }
}

/** The seek bar of the device: the position moves by itself, a seek is a command when the finger lifts. */
@Composable
fun RemoteScrubber(now: RemoteNow?, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val duration = now?.durationMs?.takeIf { it > 0 } ?: 0L
    val position by rememberRemotePosition(now)
    var scrub by remember { mutableStateOf<Float?>(null) }
    var settling by remember { mutableStateOf(false) }

    LaunchedEffect(settling) {
        if (!settling) return@LaunchedEffect
        delay(SETTLE_MS)
        scrub = null
        settling = false
    }

    val shown = scrub?.let { (it * duration).toLong() } ?: position.coerceIn(0L, duration.coerceAtLeast(0L))
    val fraction = if (duration > 0) shown.toFloat() / duration else 0f
    val elapsed = formatAsDuration(shown)
    val total = formatAsDuration(duration)

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = {
                settling = false
                scrub = it
            },
            onValueChangeFinished = {
                scrub?.let { onSeek((it * duration).toLong()) }
                settling = true
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .semantics { stateDescription = "$elapsed / $total" }
                .testTag("remote_scrubber")
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            Text(text = elapsed, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("remote_elapsed"))
            Text(text = "−" + formatAsDuration((duration - shown).coerceAtLeast(0L)), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Previous, play/pause and next: commands to the device; play/pause shows what the device does. */
@Composable
fun RemoteTransport(
    playing: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) = Row(
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth()
) {
    ButtonGroup(
        overflowIndicator = { },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        customItem(
            buttonGroupContent = {
                FilledTonalIconButton(
                    onClick = onPrevious,
                    enabled = enabled,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier
                        .size(DpSize(64.dp, 64.dp))
                        .testTag("remote_prev")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ms_skip_previous_fill),
                        contentDescription = stringResource(R.string.skip_back),
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            menuContent = { }
        )
        customItem(
            buttonGroupContent = {
                val shapes = IconButtonDefaults.shapes(
                    shape = if (playing) IconButtonDefaults.extraLargeSquareShape else IconButtonDefaults.extraLargeRoundShape,
                    pressedShape = IconButtonDefaults.extraLargePressedShape
                )
                FilledIconButton(
                    onClick = onPlayPause,
                    enabled = enabled,
                    shapes = shapes,
                    modifier = Modifier
                        .size(DpSize(96.dp, 72.dp))
                        .testTag("remote_play_pause")
                ) {
                    Icon(
                        painter = painterResource(if (playing) R.drawable.ms_pause_fill else R.drawable.ms_play_arrow_fill),
                        contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                        modifier = Modifier.size(36.dp)
                    )
                }
            },
            menuContent = { }
        )
        customItem(
            buttonGroupContent = {
                FilledTonalIconButton(
                    onClick = onNext,
                    enabled = enabled,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier
                        .size(DpSize(64.dp, 64.dp))
                        .testTag("remote_next")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ms_skip_next_fill),
                        contentDescription = stringResource(R.string.skip_forward),
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            menuContent = { }
        )
    }
}

/**
 * The volume of the device, 0..100 (tasks/0018): the slider follows the finger; the command goes [VOLUME_SETTLE_MS] after
 * the finger stops moving, so a drag is a few commands, not hundreds. What the device reports takes over again after a
 * moment.
 */
@Composable
fun RemoteVolume(volume: Int?, onVolume: (Int) -> Unit, modifier: Modifier = Modifier) {
    var chosen by remember { mutableStateOf<Int?>(null) }
    var sent by remember { mutableStateOf<Int?>(null) }

    // The command, once the finger has not moved for a while
    LaunchedEffect(chosen) {
        val value = chosen ?: return@LaunchedEffect
        delay(VOLUME_SETTLE_MS)
        if (sent != value) {
            sent = value
            onVolume(value)
        }
        // The device's own report is the truth again after a moment
        delay(SETTLE_MS)
        chosen = null
    }

    val shown = chosen ?: volume ?: 0
    val label = stringResource(R.string.remote_volume)
    val valueText = stringResource(R.string.remote_volume_value, shown)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Icon(painter = painterResource(R.drawable.ms_volume_up), contentDescription = label, modifier = Modifier.size(24.dp))
        Slider(
            value = shown.toFloat(),
            onValueChange = { chosen = it.toInt().coerceIn(0, MAX_VOLUME) },
            valueRange = 0f..MAX_VOLUME.toFloat(),
            enabled = volume != null || chosen != null,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { stateDescription = valueText }
                .testTag("remote_volume")
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            modifier = Modifier
                .padding(start = 4.dp)
                .testTag("remote_volume_value")
        )
    }
}

private const val MAX_VOLUME = 100
