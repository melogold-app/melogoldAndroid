@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package app.melogold.android.ui.screens.player.modern

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import app.melogold.android.R
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.components.m3e.MelogoldContainedLoadingIndicator
import app.melogold.android.ui.components.m3e.rememberHaptics
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.formatAsDuration
import app.melogold.android.utils.positionAndDurationState
import app.melogold.android.utils.toast
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L

/** Previous and next next to the wide play button (96 × 72 dp). */
private val SideButtonSize = DpSize(64.dp, 64.dp)

/** After a seek, the bar keeps showing the new position until the player reports it. */
private const val SEEK_SETTLE_MS = 700L

private fun Player.playOrResume() {
    if (playbackState == Player.STATE_IDLE) prepare()
    if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
    play()
}

/**
 * The seek bar (REWRITE §3.10.2): the active part waves while playing and lies flat when paused,
 * a bar thumb, the position applied when the finger lifts; elapsed time on the left, remaining time
 * with a minus on the right. It reads the playback position itself, so only this leaf recomposes.
 */
@Composable
fun PlayerScrubber(
    binder: PlayerService.Binder,
    playing: Boolean,
    reduceMotion: Boolean,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val player = binder.player
    val haptics = rememberHaptics()

    val positionAndDuration = player.positionAndDurationState()
    val duration = positionAndDuration.second.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    val position = positionAndDuration.first.coerceIn(0L, duration.coerceAtLeast(0L))

    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var settling by remember { mutableStateOf(false) }

    LaunchedEffect(settling) {
        if (!settling) return@LaunchedEffect
        delay(SEEK_SETTLE_MS)
        scrubFraction = null
        settling = false
    }

    val shownPosition = scrubFraction?.let { (it * duration).toLong() } ?: position
    val fraction = if (duration > 0) shownPosition.toFloat() / duration else 0f
    val interactionSource = remember { MutableInteractionSource() }

    val elapsedText = formatAsDuration(shownPosition)
    val totalText = formatAsDuration(duration)
    val stateText = stringResource(R.string.seek_position_format, elapsedText, totalText)
    val back10 = stringResource(R.string.seek_back_10)
    val forward10 = stringResource(R.string.seek_forward_10)
    val marker = binder.poiTimestamp?.takeIf { duration > 0 }?.let { it.toFloat() / duration }

    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.secondaryContainer
    val markerColor = MaterialTheme.colorScheme.tertiary
    val wavy = playing && scrubFraction == null && !reduceMotion

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { value ->
                val old = scrubFraction
                if (old != null && ((value == 0f && old > 0f) || (value == 1f && old < 1f))) haptics.segmentTick()
                if (old == null) onScrubbing(true)
                settling = false
                scrubFraction = value
            },
            onValueChangeFinished = {
                scrubFraction?.let { player.seekTo((it * duration).toLong()) }
                onScrubbing(false)
                settling = true
            },
            enabled = duration > 0,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = DpSize(4.dp, 32.dp),
                    colors = SliderDefaults.colors(thumbColor = activeColor)
                )
            },
            track = { state ->
                LinearWavyProgressIndicator(
                    progress = { state.value },
                    color = activeColor,
                    trackColor = trackColor,
                    amplitude = { if (wavy) WavyProgressIndicatorDefaults.indicatorAmplitude(it) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            if (marker != null) drawCircle(
                                color = markerColor,
                                radius = 3.dp.toPx(),
                                center = Offset(size.width * marker, size.height / 2)
                            )
                        }
                )
            },
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .semantics {
                    stateDescription = stateText
                    customActions = listOf(
                        CustomAccessibilityAction(back10) {
                            player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
                            true
                        },
                        CustomAccessibilityAction(forward10) {
                            player.seekTo((player.currentPosition + SEEK_STEP_MS).coerceAtMost(duration))
                            true
                        }
                    )
                }
                .testTag("player_scrubber")
        )

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Text(text = elapsedText, style = style, color = color)
            Text(text = "−" + formatAsDuration((duration - shownPosition).coerceAtLeast(0L)), style = style, color = color)
        }
    }
}

/**
 * Shuffle, the `|<< · play · >>|` button group and repeat (REWRITE §3.10.2). Play is the wide
 * button in the middle; its shape morphs between round (paused) and square (playing).
 */
@Composable
fun TransportRow(
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    resolving: Boolean,
    modifier: Modifier = Modifier
) {
    val player = binder.player
    val haptics = rememberHaptics()
    var shuffle by remember(player) { mutableStateOf(player.shuffleModeEnabled) }

    player.DisposableListener {
        object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffle = shuffleModeEnabled
            }
        }
    }

    val trackLoop = PlayerPreferences.trackLoopEnabled
    val queueLoop = PlayerPreferences.queueLoopEnabled

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        IconToggleButton(
            checked = shuffle,
            onCheckedChange = {
                haptics.toggle(it)
                player.shuffleModeEnabled = it
            },
            modifier = Modifier.testTag("player_shuffle")
        ) {
            Icon(
                painter = painterResource(R.drawable.ms_shuffle),
                contentDescription = stringResource(R.string.shuffle)
            )
        }

        val prevSource = remember { MutableInteractionSource() }
        val playSource = remember { MutableInteractionSource() }
        val nextSource = remember { MutableInteractionSource() }

        ButtonGroup(
            overflowIndicator = { },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            customItem(
                buttonGroupContent = {
                    FilledTonalIconButton(
                        onClick = { player.forceSeekToPrevious() },
                        shapes = IconButtonDefaults.shapes(),
                        interactionSource = prevSource,
                        modifier = Modifier
                            .size(SideButtonSize)
                            .animateWidth(prevSource)
                            .testTag("player_prev")
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
                    // Round while paused, square while playing (M3 Expressive shape morph)
                    val shapes = IconButtonDefaults.shapes(
                        shape = if (shouldBePlaying) IconButtonDefaults.extraLargeSquareShape
                        else IconButtonDefaults.extraLargeRoundShape,
                        pressedShape = IconButtonDefaults.extraLargePressedShape
                    )
                    FilledIconButton(
                        onClick = {
                            haptics.confirm()
                            if (shouldBePlaying) player.pause() else player.playOrResume()
                        },
                        shapes = shapes,
                        interactionSource = playSource,
                        modifier = Modifier
                            .size(DpSize(96.dp, 72.dp))
                            .animateWidth(playSource)
                            .testTag("player_play_pause")
                    ) {
                        if (resolving) MelogoldContainedLoadingIndicator(modifier = Modifier.size(40.dp))
                        else Icon(
                            painter = painterResource(if (shouldBePlaying) R.drawable.ms_pause_fill else R.drawable.ms_play_arrow_fill),
                            contentDescription = stringResource(if (shouldBePlaying) R.string.pause else R.string.play),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                },
                menuContent = { }
            )
            customItem(
                buttonGroupContent = {
                    FilledTonalIconButton(
                        onClick = { player.forceSeekToNext() },
                        shapes = IconButtonDefaults.shapes(),
                        interactionSource = nextSource,
                        modifier = Modifier
                            .size(SideButtonSize)
                            .animateWidth(nextSource)
                            .testTag("player_next")
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

        IconToggleButton(
            checked = trackLoop || queueLoop,
            onCheckedChange = {
                // off → queue → track → off
                when {
                    trackLoop -> {
                        PlayerPreferences.trackLoopEnabled = false
                        PlayerPreferences.queueLoopEnabled = false
                    }

                    queueLoop -> PlayerPreferences.trackLoopEnabled = true
                    else -> PlayerPreferences.queueLoopEnabled = true
                }
                haptics.segmentTick()
            },
            modifier = Modifier
                .testTag("player_repeat_button")
                .semantics {
                    stateDescription = when {
                        trackLoop -> "repeat_one"
                        queueLoop -> "repeat_all"
                        else -> "repeat_off"
                    }
                }
        ) {
            Icon(
                painter = painterResource(if (trackLoop) R.drawable.ms_repeat_one else R.drawable.ms_repeat),
                contentDescription = stringResource(
                    when {
                        trackLoop -> R.string.repeat_one
                        queueLoop -> R.string.repeat_all
                        else -> R.string.player_repeat
                    }
                )
            )
        }
    }
}

/**
 * "Lyrics · Queue" as a connected pair of toggle buttons, and the output switcher chip
 * (REWRITE §3.10.2).
 */
@Composable
fun PlayerToolbar(
    lyricsSelected: Boolean,
    queueSelected: Boolean,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val noOutputSwitcher = stringResource(R.string.no_output_switcher)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .testTag("player_toolbar")
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(
                checked = lyricsSelected,
                onCheckedChange = { onLyricsClick() },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.testTag("player_lyrics_button")
            ) {
                Icon(
                    painter = painterResource(if (lyricsSelected) R.drawable.ms_lyrics_fill else R.drawable.ms_lyrics),
                    contentDescription = null,
                    modifier = Modifier.size(ToggleButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.player_lyrics))
            }
            ToggleButton(
                checked = queueSelected,
                onCheckedChange = { onQueueClick() },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.testTag("player_queue_button")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ms_queue_music),
                    contentDescription = null,
                    modifier = Modifier.size(ToggleButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.player_queue))
            }
        }

        AssistChip(
            onClick = { context.showOutputSwitcher(noOutputSwitcher) },
            label = { Text(text = stringResource(R.string.player_output_short)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ms_media_output),
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                )
            },
            modifier = Modifier.testTag("player_output_button")
        )
    }
}

/**
 * The whole bottom block: scrubber, labels, transport and toolbar.
 *
 * @param compact use smaller gaps (short screens, landscape)
 */
@Composable
fun PlayerControlsBlock(
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    resolving: Boolean,
    reduceMotion: Boolean,
    onScrubbing: (Boolean) -> Unit,
    toolbar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) = Column(
    modifier = modifier
        .fillMaxWidth()
        .testTag("player_controls")
) {
    PlayerScrubber(binder = binder, playing = shouldBePlaying, reduceMotion = reduceMotion, onScrubbing = onScrubbing)
    Spacer(modifier = Modifier.height(if (compact) 8.dp else 20.dp))
    TransportRow(binder = binder, shouldBePlaying = shouldBePlaying, resolving = resolving)
    Spacer(modifier = Modifier.height(if (compact) 8.dp else 24.dp))
    toolbar()
    Spacer(modifier = Modifier.height(if (compact) 4.dp else 12.dp))
}

/** Opens the system output switcher (API 34+) or the Bluetooth settings. */
fun Context.showOutputSwitcher(errorMessage: String) {
    val shown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching {
        MediaRouter2.getInstance(this).showSystemOutputSwitcher()
    }.getOrDefault(false) else false

    if (shown) return

    runCatching {
        startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { toast(errorMessage) }
}
