package app.melogold.android.ui.screens.player.modern

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import app.melogold.android.R
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.screens.player.AnimatedPlayPauseButton
import app.melogold.android.utils.formatAsDuration
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.positionAndDurationState
import app.melogold.android.utils.toast
import app.melogold.core.ui.LocalAppearance

private const val SEEK_STEP_MS = 10_000L
private val TrackInset = 32.dp
private val TrackInsetPressed = 24.dp

private fun Player.playOrResume() {
    if (playbackState == Player.STATE_IDLE) prepare()
    play()
}

/**
 * A slider in the Apple style: a thin rounded track without a knob, which grows while touched.
 * Drags are relative (touching does not jump), a tap jumps to the tapped position, and the value is
 * only committed on release.
 *
 * @param fraction the current value in 0..1
 * @param onScrub called while dragging with the value that would be committed
 * @param onCommit called on release (or tap) with the new value
 */
@Composable
private fun AppleSlider(
    fraction: Float,
    pressFraction: () -> Float,
    onPressedChange: (Boolean) -> Unit,
    onScrub: (Float?) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inset: Dp = TrackInset,
    pressedInset: Dp = TrackInsetPressed,
    markerFraction: Float? = null
) {
    val currentFraction by rememberUpdatedState(fraction)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                sliderGestures(
                    inset = inset,
                    fraction = { currentFraction },
                    onPressedChange = { currentOnPressedChange(it) },
                    onScrub = { currentOnScrub(it) },
                    onCommit = { currentOnCommit(it) }
                )
            }
            .drawBehind {
                val p = pressFraction()
                val start = (inset + (pressedInset - inset) * p).toPx()
                val width = size.width - start * 2
                val height = (6.dp + 6.dp * p).toPx()
                val top = (size.height - height) / 2
                val radius = CornerRadius(height / 2, height / 2)

                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(start, top),
                    size = Size(width, height),
                    cornerRadius = radius
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.7f + 0.3f * p),
                    topLeft = Offset(start, top),
                    size = Size(width * fraction.coerceIn(0f, 1f), height),
                    cornerRadius = radius
                )
                markerFraction?.let {
                    drawCircle(
                        color = Color.White,
                        radius = height / 2,
                        center = Offset(start + width * it.coerceIn(0f, 1f), top + height / 2)
                    )
                }
            }
    )
}

private suspend fun PointerInputScope.sliderGestures(
    inset: Dp,
    fraction: () -> Float,
    onPressedChange: (Boolean) -> Unit,
    onScrub: (Float?) -> Unit,
    onCommit: (Float) -> Unit
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val trackStart = inset.toPx()
    val trackWidth = (size.width - trackStart * 2).coerceAtLeast(1f)
    val start = fraction()
    var accumulated = 0f

    onPressedChange(true)

    try {
        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            accumulated += over
            onScrub((start + accumulated / trackWidth).coerceIn(0f, 1f))
        }

        if (slop != null) {
            var value = (start + accumulated / trackWidth).coerceIn(0f, 1f)
            val completed = horizontalDrag(slop.id) { change ->
                accumulated += change.positionChange().x
                change.consume()
                value = (start + accumulated / trackWidth).coerceIn(0f, 1f)
                onScrub(value)
            }
            if (completed) onCommit(value)
        } else {
            val up = currentEvent.changes.firstOrNull { it.id == down.id }
            if (up != null && !up.pressed && !up.isConsumed) {
                up.consume()
                onCommit(((up.position.x - trackStart) / trackWidth).coerceIn(0f, 1f))
            }
        }
    } finally {
        onScrub(null)
        onPressedChange(false)
    }
}

/**
 * The seek bar with the elapsed / remaining labels. It reads the playback position itself, so only
 * this leaf recomposes (every 500 ms).
 */
@Composable
fun PlayerScrubber(
    binder: PlayerService.Binder,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val player = binder.player
    val haptic = LocalHapticFeedback.current

    val positionAndDuration = player.positionAndDurationState()
    val duration = positionAndDuration.second.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    val position = positionAndDuration.first.coerceIn(0L, duration.coerceAtLeast(0L))

    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val pressFraction = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 700f),
        label = ""
    )

    val shownPosition = scrubFraction?.let { (it * duration).toLong() } ?: position
    val fraction = if (duration > 0) shownPosition.toFloat() / duration else 0f

    val elapsedText = formatAsDuration(shownPosition)
    val totalText = formatAsDuration(duration)
    val stateText = stringResource(R.string.seek_position_format, elapsedText, totalText)
    val back10 = stringResource(R.string.seek_back_10)
    val forward10 = stringResource(R.string.seek_forward_10)

    Column(modifier = modifier.fillMaxWidth()) {
        AppleSlider(
            fraction = fraction,
            pressFraction = { pressFraction.value },
            enabled = duration > 0,
            onPressedChange = {
                pressed = it
                onScrubbing(it)
            },
            onScrub = { newFraction ->
                val old = scrubFraction
                if (newFraction != null && old != null) {
                    val hitEdge = (newFraction == 0f && old > 0f) || (newFraction == 1f && old < 1f)
                    if (hitEdge) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                scrubFraction = newFraction
            },
            onCommit = { player.seekTo((it * duration).toLong()) },
            markerFraction = binder.poiTimestamp?.takeIf { duration > 0 }?.let { it.toFloat() / duration },
            modifier = Modifier
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = position.toFloat(),
                        range = 0f..duration.toFloat().coerceAtLeast(1f)
                    )
                    stateDescription = stateText
                    setProgress { target ->
                        player.seekTo(target.toLong())
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(back10) {
                            player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
                            true
                        },
                        CustomAccessibilityAction(forward10) {
                            player.seekTo(
                                (player.currentPosition + SEEK_STEP_MS).coerceAtMost(duration)
                            )
                            true
                        }
                    )
                }
                .testTag("player_scrubber")
        )

        TimeLabels(
            elapsed = elapsedText,
            remaining = "−" + formatAsDuration((duration - shownPosition).coerceAtLeast(0L)),
            pressFraction = { pressFraction.value }
        )
    }
}

@Composable
private fun TimeLabels(
    elapsed: String,
    remaining: String,
    pressFraction: () -> Float,
    modifier: Modifier = Modifier
) {
    val typography = LocalAppearance.current.typography
    val style = typography.xxs.copy(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
        color = Color.White
    )

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .offset(y = (-6).dp)
            .padding(horizontal = TrackInset)
            .graphicsLayer {
                val p = pressFraction()
                translationY = 4.dp.toPx() * p
                alpha = 0.55f + 0.35f * p
            }
    ) {
        BasicText(text = elapsed, style = style)
        BasicText(text = remaining, style = style)
    }
}

@Composable
private fun TransportButton(
    @DrawableRes icon: Int?,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    iconWidth: Dp = 40.dp,
    iconHeight: Dp = 24.dp,
    content: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f),
        label = ""
    )
    val circleAlpha = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 80 else 250),
        label = ""
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(80.dp)
            .testTag(testTag)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            // Keeps the description on the tagged node (no merged child nodes)
            .clearAndSetSemantics { this.contentDescription = contentDescription }
    ) {
        Spacer(
            modifier = Modifier
                .size(72.dp)
                .drawBehind {
                    drawCircle(Color.White.copy(alpha = 0.12f * circleAlpha.value))
                }
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
        ) {
            if (content != null) content()
            else if (icon != null) Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(OnArt.primary),
                modifier = Modifier.size(width = iconWidth, height = iconHeight)
            )
        }
    }
}

@Composable
fun TransportRow(
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val player = binder.player

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        TransportButton(
            icon = R.drawable.play_skip_back,
            contentDescription = stringResource(R.string.skip_back),
            onClick = { player.forceSeekToPrevious() },
            testTag = "player_prev",
            iconWidth = 32.dp,
            iconHeight = 26.dp,
            modifier = Modifier.offset(x = (-112).dp)
        )

        TransportButton(
            icon = null,
            contentDescription = stringResource(if (shouldBePlaying) R.string.pause else R.string.play),
            onClick = {
                if (shouldBePlaying) player.pause() else player.playOrResume()
            },
            testTag = "player_play_pause"
        ) {
            AnimatedPlayPauseButton(
                playing = shouldBePlaying,
                modifier = Modifier.size(42.dp)
            )
        }

        TransportButton(
            icon = R.drawable.play_skip_forward,
            contentDescription = stringResource(R.string.skip_forward),
            onClick = { player.forceSeekToNext() },
            testTag = "player_next",
            iconWidth = 32.dp,
            iconHeight = 26.dp,
            modifier = Modifier.offset(x = 112.dp)
        )
    }
}

@Composable
private fun ToolbarButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    testTag: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    isToggle: Boolean = false,
    stateDescription: String? = null,
    alpha: Float = 1f,
    onClick: () -> Unit
) {
    val selectedFraction = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200),
        label = ""
    )
    val interactionSource = remember { MutableInteractionSource() }
    val tint = if (selected) OnArt.onSelected else OnArt.primary.copy(alpha = 0.85f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .testTag(testTag)
            .let {
                if (isToggle) it.toggleable(
                    value = selected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Switch,
                    onValueChange = { onClick() }
                ) else it.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                )
            }
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .graphicsLayer { this.alpha = alpha }
            .drawBehind {
                val f = selectedFraction.value
                if (f > 0f) drawCircle(
                    color = OnArt.selected.copy(alpha = 0.85f * f),
                    radius = 20.dp.toPx() * (0.8f + 0.2f * f)
                )
            }
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun PlayerToolbar(
    lyricsSelected: Boolean,
    lyricsAvailable: Boolean,
    queueSelected: Boolean,
    onLyricsClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val on = stringResource(R.string.state_on)
    val off = stringResource(R.string.state_off)
    val noOutputSwitcher = stringResource(R.string.no_output_switcher)

    val trackLoop = PlayerPreferences.trackLoopEnabled
    val queueLoop = PlayerPreferences.queueLoopEnabled

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .testTag("player_toolbar")
    ) {
        ToolbarButton(
            icon = R.drawable.lyrics,
            contentDescription = stringResource(R.string.player_lyrics),
            testTag = "player_lyrics_button",
            selected = lyricsSelected,
            isToggle = true,
            stateDescription = if (lyricsSelected) on else off,
            alpha = if (lyricsAvailable || lyricsSelected) 1f else 0.35f,
            onClick = onLyricsClick
        )

        ToolbarButton(
            icon = R.drawable.speaker_output,
            contentDescription = stringResource(R.string.player_output),
            testTag = "player_output_button",
            onClick = { context.showOutputSwitcher(noOutputSwitcher) }
        )

        ToolbarButton(
            icon = when {
                trackLoop -> R.drawable.infinite
                queueLoop -> R.drawable.repeat_on
                else -> R.drawable.repeat
            },
            contentDescription = stringResource(R.string.player_repeat),
            testTag = "player_repeat_button",
            stateDescription = stringResource(
                when {
                    trackLoop -> R.string.repeat_one
                    queueLoop -> R.string.repeat_all
                    else -> R.string.repeat_off
                }
            ),
            alpha = if (trackLoop || queueLoop) 1f else 0.6f,
            onClick = {
                when {
                    trackLoop -> {
                        PlayerPreferences.trackLoopEnabled = false
                        PlayerPreferences.queueLoopEnabled = false
                    }

                    queueLoop -> PlayerPreferences.trackLoopEnabled = true

                    else -> PlayerPreferences.queueLoopEnabled = true
                }
            }
        )

        ToolbarButton(
            icon = R.drawable.list,
            contentDescription = stringResource(R.string.player_queue),
            testTag = "player_queue_button",
            selected = queueSelected,
            isToggle = true,
            stateDescription = if (queueSelected) on else off,
            onClick = onQueueClick
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
    onScrubbing: (Boolean) -> Unit,
    toolbar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) = Column(
    modifier = modifier
        .fillMaxWidth()
        .testTag("player_controls")
) {
    PlayerScrubber(binder = binder, onScrubbing = onScrubbing)
    Spacer(modifier = Modifier.height(if (compact) 8.dp else 34.dp))
    TransportRow(binder = binder, shouldBePlaying = shouldBePlaying)
    Spacer(modifier = Modifier.height(if (compact) 8.dp else 28.dp))
    toolbar()
    Spacer(modifier = Modifier.height(if (compact) 4.dp else 10.dp))
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
