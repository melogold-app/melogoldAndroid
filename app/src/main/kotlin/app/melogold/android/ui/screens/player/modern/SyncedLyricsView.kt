package app.melogold.android.ui.screens.player.modern

import android.util.Log
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import app.melogold.android.BuildConfig
import app.melogold.android.R
import app.melogold.android.utils.shouldBePlaying
import app.melogold.core.ui.LocalAppearance
import app.melogold.core.ui.utils.isAtLeastAndroid12
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

private const val TAG = "PlayerLyrics"

/** Kill switch for the per-line blur (API 31+). */
private const val LINE_BLUR = true

/** The lyrics lead the audio a little, so a line lights up right when it is sung. */
private const val LEAD_MS = 50L

private const val RESUME_FOLLOW_DELAY_MS = 3_000L
private const val SEEK_JUMP_LINES = 8

private val lineEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** A line's distance from the active one, clamped: 0 = active, 1..5 next, -1..-5 past, ±6 far. */
private fun bucketOf(distance: Int) = distance.coerceIn(-6, 6)

/**
 * The full-screen, time-synced lyrics.
 *
 * Only a [derivedStateOf] reads the (50 ms) position ticker, so composition only sees the active
 * index change; rows only recompose when their distance bucket changes.
 *
 * @param anchor where the top of the active line sits, from the top of the view
 * @param controlsOverlapPx height of the controls drawn over the bottom of this view (0 when none)
 * @param controlsVisible whether those controls are currently shown (for the fade mask)
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun SyncedLyricsView(
    content: LyricsContent.Synced,
    mediaId: String,
    player: Player,
    shouldBePlaying: Boolean,
    anchor: Dp,
    controlsVisible: Boolean,
    controlsOverlapPx: () -> Int,
    reduceMotion: Boolean,
    modeState: PlayerModeState,
    onLineLongPress: (LyricLine) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val typography = LocalAppearance.current.typography

    val positionMs = remember(mediaId) { mutableLongStateOf(player.currentPosition) }

    LaunchedEffect(player, mediaId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                positionMs.longValue = player.currentPosition
                delay(if (player.isPlaying) 50L else 250L)
            }
        }
    }

    val activeIndex = remember(content) {
        derivedStateOf {
            content.lines.activeIndexAt(
                positionMs.longValue + LEAD_MS + content.offsetMs - content.startTimeMs
            )
        }
    }

    val listState = remember(mediaId) { LazyListState() }
    var autoFollow by remember(mediaId) { mutableStateOf(true) }
    val controlsFraction = animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(300),
        label = ""
    )
    val currentReduceMotion by rememberUpdatedState(reduceMotion)

    suspend fun scrollToLine(index: Int, previous: Int) {
        if (index < 0) {
            listState.animateScrollToItem(0)
            return
        }

        val spec = if (currentReduceMotion) tween<Float>(200)
        else spring(dampingRatio = 0.83f, stiffness = 100f)

        // With the top content padding, an item offset of 0 means "top at the anchor"
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (target == null || abs(index - previous) > SEEK_JUMP_LINES) {
            listState.scrollToItem((index - 2).coerceAtLeast(0))
            listState.animateScrollToItem(index)
        } else listState.animateScrollBy(target.offset.toFloat(), spec)
    }

    LaunchedEffect(content, listState) {
        var previous = -1
        snapshotFlow { activeIndex.value }.collectLatest { index ->
            if (BuildConfig.DEBUG) Log.d(TAG, "active=$index pos=${positionMs.longValue}")
            val from = previous
            previous = index
            if (autoFollow) scrollToLine(index, from)
        }
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    autoFollow = false
                    modeState.userScrolling = true
                    modeState.reveal()
                }

                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    delay(RESUME_FOLLOW_DELAY_MS)
                    autoFollow = true
                    modeState.userScrolling = false
                    val index = activeIndex.value
                    scrollToLine(index, index)
                }
            }
        }
    }

    DisposableEffect(modeState) {
        onDispose { modeState.userScrolling = false }
    }

    val lineStyle = typography.xxl.copy(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 40.sp,
        letterSpacing = (-0.3).sp,
        color = Color.White,
        textAlign = TextAlign.Start,
        textDirection = TextDirection.Content
    )
    val footerStyle = typography.xxs.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.45f))
    val jumpLabel = stringResource(R.string.lyrics_jump_to_line)
    val instrumental = stringResource(R.string.lyrics_instrumental)
    val scrolling = modeState.userScrolling

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight

        CompositionLocalProvider(
            LocalDensity provides Density(
                density = density.density,
                fontScale = density.fontScale.coerceAtMost(1.3f)
            )
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = anchor,
                    bottom = (viewportHeight - anchor).coerceAtLeast(0.dp)
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawFadeMask(
                            controlsFraction = controlsFraction.value,
                            controlsOverlap = controlsOverlapPx().toFloat()
                        )
                    }
                    .testTag("lyrics_list")
            ) {
                itemsIndexed(
                    items = content.lines,
                    key = { index, _ -> index },
                    contentType = { _, line -> if (line.isInterlude) 1 else 0 }
                ) { index, line ->
                    val onClick = {
                        val target = (line.startMs - content.offsetMs + content.startTimeMs)
                            .coerceAtLeast(0L)
                        player.seekTo(target)
                        if (!player.shouldBePlaying) {
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            player.play()
                        }
                        positionMs.longValue = target
                        autoFollow = true
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        if (BuildConfig.DEBUG) Log.d(TAG, "seek line=$index to=$target")
                    }

                    if (line.isInterlude) InterludeRow(
                        index = index,
                        line = line,
                        activeIndex = activeIndex,
                        positionMs = positionMs,
                        offsetMs = content.offsetMs - content.startTimeMs,
                        playing = shouldBePlaying,
                        reduceMotion = reduceMotion,
                        onClick = onClick,
                        modifier = Modifier.semantics { contentDescription = instrumental }
                    ) else LyricLineRow(
                        index = index,
                        line = line,
                        activeIndex = activeIndex,
                        scrolling = scrolling,
                        reduceMotion = reduceMotion,
                        style = lineStyle,
                        clickLabel = jumpLabel,
                        onClick = onClick,
                        onLongClick = { onLineLongPress(line) }
                    )
                }

                item(key = "footer", contentType = 2) {
                    BasicText(
                        text = stringResource(R.string.provided_lyrics_by),
                        style = footerStyle,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Fades the top 24 dp out, and the bottom either just above the controls (72 dp fade, when they are
 * visible) or at the bottom of the screen (96 dp fade, when they are hidden).
 */
private fun DrawScope.drawFadeMask(controlsFraction: Float, controlsOverlap: Float) {
    val h = size.height
    if (h <= 0f) return

    val top = 24.dp.toPx()
    val fadeEnd = lerp(h, h - controlsOverlap, controlsFraction)
    val fadeLength = lerp(96.dp.toPx(), 72.dp.toPx(), controlsFraction)
    val fadeStart = (fadeEnd - fadeLength).coerceAtLeast(top + 1f)

    fun stop(y: Float) = (y / h).coerceIn(0f, 1f)

    val stops = mutableListOf(
        0f to Color.Transparent,
        stop(top) to Color.Black,
        stop(fadeStart) to Color.Black,
        stop(fadeEnd.coerceAtLeast(fadeStart + 1f)) to Color.Transparent
    )
    if (stops.last().first < 1f) stops += 1f to Color.Transparent

    drawRect(
        brush = Brush.verticalGradient(colorStops = stops.toTypedArray()),
        blendMode = BlendMode.DstIn
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricLineRow(
    index: Int,
    line: LyricLine,
    activeIndex: State<Int>,
    scrolling: Boolean,
    reduceMotion: Boolean,
    style: TextStyle,
    clickLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bucket by remember(index, activeIndex) {
        derivedStateOf { bucketOf(index - activeIndex.value) }
    }
    val active = bucket == 0

    val targetAlpha = when {
        active -> 1f
        scrolling -> 0.45f
        bucket > 0 -> 0.35f
        else -> 0.20f
    }
    val alpha = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 400, easing = lineEasing),
        label = ""
    )
    val scale = animateFloatAsState(
        targetValue = if (active || reduceMotion) 1f else 0.96f,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 50f),
        label = ""
    )
    val blurDp = when {
        !LINE_BLUR || !isAtLeastAndroid12 || reduceMotion || scrolling || active -> 0f
        bucket > 0 -> min(1f + bucket, 5f)
        else -> min(2f + abs(bucket), 6f)
    }

    BasicText(
        text = line.text,
        style = style,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = clickLabel,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .semantics { selected = active }
            .testTag("lyrics_line")
            .padding(horizontal = 32.dp, vertical = 10.dp)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
                transformOrigin = TransformOrigin(0f, 0.5f)
                val radius = blurDp.dp.toPx()
                renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
            }
    )
}

/**
 * Three dots for an instrumental gap. While active and playing they fill one after the other and
 * "breathe"; the animation only touches the draw phase.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InterludeRow(
    index: Int,
    line: LyricLine,
    activeIndex: State<Int>,
    positionMs: MutableLongState,
    offsetMs: Long,
    playing: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active by remember(index, activeIndex) { derivedStateOf { activeIndex.value == index } }
    val clock = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(active, playing, reduceMotion) {
        if (!active || !playing || reduceMotion) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { clock.floatValue = (it - start).toFloat() }
        }
    }

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .testTag("lyrics_line")
            .semantics { selected = active }
            .drawBehind {
                val isActive = active
                // Only the active row reads the position, so the others never redraw
                val position = if (isActive) positionMs.longValue + LEAD_MS + offsetMs else 0L
                val duration = (line.endMs - line.startMs).coerceAtLeast(1L)
                val fraction = if (isActive) {
                    ((position - line.startMs).toFloat() / duration).coerceIn(0f, 1f)
                } else 0f
                drawInterludeDots(
                    fraction = fraction,
                    remainingMs = if (isActive) (line.endMs - position).toFloat() else Float.MAX_VALUE,
                    clockMs = if (isActive) clock.floatValue else 0f,
                    active = isActive,
                    animated = !reduceMotion
                )
            }
    )
}

/** Draws the three interlude dots, starting 32 dp from the left and centred vertically. */
internal fun DrawScope.drawInterludeDots(
    fraction: Float,
    remainingMs: Float,
    clockMs: Float,
    active: Boolean,
    animated: Boolean
) {
    val dot = 10.dp.toPx()
    val gap = 6.dp.toPx()
    val startX = 32.dp.toPx()
    val cy = size.height / 2
    val groupCenter = Offset(startX + dot * 1.5f + gap, cy)

    var groupScale = if (animated) {
        1f + 0.075f * (1f - cos(2f * PI.toFloat() * clockMs / 3000f))
    } else 1f
    var groupAlpha = 1f

    if (active && animated && remainingMs < 1000f) {
        if (remainingMs > 250f) {
            val p = ((1000f - remainingMs) / 750f).coerceIn(0f, 1f)
            val eased = 1f - (1f - p) * (1f - p)
            groupScale = lerp(groupScale, 1.25f, eased)
        } else {
            val p = ((250f - remainingMs) / 250f).coerceIn(0f, 1f)
            groupScale = lerp(1.25f, 0.4f, p)
            groupAlpha = 1f - p
        }
    }

    scale(scale = groupScale, pivot = groupCenter) {
        repeat(3) { k ->
            val fill = if (active) (fraction * 3f - k).coerceIn(0f, 1f) else 0f
            val alpha = (0.2f + 0.7f * fill) * groupAlpha
            drawCircle(
                color = Color.White.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = dot / 2,
                center = Offset(startX + dot / 2 + k * (dot + gap), cy)
            )
        }
    }
}
