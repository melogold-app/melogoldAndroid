package app.melogold.android.ui.screens.player.modern

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import app.melogold.android.R
import app.melogold.android.utils.shouldBePlaying
import app.melogold.domain.lyrics.SyncedLine
import app.melogold.domain.lyrics.SyncedWord
import app.melogold.domain.lyrics.VocalSide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** The lyrics lead the audio a little, so a word lights up right when it is sung. */
private const val LEAD_MS = 60L

private const val RESUME_FOLLOW_DELAY_MS = 3_000L
private const val SEEK_JUMP_LINES = 8
private const val PAUSED_POLL_MS = 250L

/** Alpha of lines around the active one (REWRITE §3.10.3). */
private const val PAST_ALPHA = 0.35f
private const val FUTURE_ALPHA = 0.6f
private const val SCROLLING_ALPHA = 0.6f

/** Alpha of the words of the active line that are not sung yet. */
private const val UNSUNG_ALPHA = 0.4f

private val lineEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * The full-screen, time-synced lyrics (REWRITE §3.10.3, docs/spec/lyrics.md): the active line is
 * bright and, for word-timed lyrics, fills word by word; the second singer of a duet sits at the
 * end edge, backing vocals are smaller under their line, translations under the text. No blur,
 * no scaling.
 *
 * The playback position is read every frame while playing, but only in the draw phase and in a
 * [derivedStateOf] for the active index, so composition only sees the active row change.
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
    onLineLongPress: (SyncedLine) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current

    // The position in lyrics time: every frame while playing, a few times a second when paused
    val positionMs = remember(mediaId) { mutableLongStateOf(player.currentPosition) }
    val shift = LEAD_MS - content.startTimeMs

    LaunchedEffect(player, mediaId, shouldBePlaying) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (player.isPlaying) withFrameMillis { positionMs.longValue = player.currentPosition }
                else {
                    positionMs.longValue = player.currentPosition
                    delay(PAUSED_POLL_MS)
                }
            }
        }
    }

    val lyricsPosition: () -> Long = { positionMs.longValue + shift }
    val activeIndex = remember(content) {
        derivedStateOf { content.rows.activeIndexAt(positionMs.longValue + shift) }
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

    val colors = MaterialTheme.colorScheme
    val lineStyle = MaterialTheme.typography.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        lineHeight = 38.sp,
        letterSpacing = (-0.2).sp
    )
    val backingStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val translationStyle = MaterialTheme.typography.bodyLarge
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
                    items = content.rows,
                    key = { index, _ -> index },
                    contentType = { _, row -> if (row is LyricRow.Interlude) 1 else 0 }
                ) { index, row ->
                    val onClick = {
                        val target = (row.startMs + content.startTimeMs).coerceAtLeast(0L)
                        player.seekTo(target)
                        if (!player.shouldBePlaying) {
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            player.play()
                        }
                        positionMs.longValue = target
                        autoFollow = true
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    }

                    when (row) {
                        is LyricRow.Interlude -> InterludeRow(
                            index = index,
                            row = row,
                            activeIndex = activeIndex,
                            position = lyricsPosition,
                            playing = shouldBePlaying,
                            reduceMotion = reduceMotion,
                            color = colors.onSurface,
                            onClick = onClick,
                            modifier = Modifier.semantics { contentDescription = instrumental }
                        )

                        is LyricRow.Sung -> LyricLineRow(
                            index = index,
                            line = row.line,
                            activeIndex = activeIndex,
                            position = lyricsPosition,
                            scrolling = scrolling,
                            lineStyle = lineStyle,
                            backingStyle = backingStyle,
                            translationStyle = translationStyle,
                            color = colors.onSurface,
                            secondaryColor = colors.onSurfaceVariant,
                            clickLabel = jumpLabel,
                            onClick = onClick,
                            onLongClick = { onLineLongPress(row.line) }
                        )
                    }
                }

                item(key = "footer", contentType = 2) {
                    LyricsSourceFooter(source = content.source, synced = true)
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

/**
 * A sung line: its main text, backing vocals under it and the translation, aligned to its
 * singer's side. Rows only recompose when they become (in)active; the word fill is drawn.
 */
// The states are read in the draw phase so that the playback clock does not recompose the row
@Suppress("StateParam")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricLineRow(
    index: Int,
    line: SyncedLine,
    activeIndex: State<Int>,
    position: () -> Long,
    scrolling: Boolean,
    lineStyle: TextStyle,
    backingStyle: TextStyle,
    translationStyle: TextStyle,
    color: Color,
    secondaryColor: Color,
    clickLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val distance by remember(index, activeIndex) {
        derivedStateOf { (index - activeIndex.value).coerceIn(-1, 1) }
    }
    val active = distance == 0

    val alpha = animateFloatAsState(
        targetValue = when {
            active -> 1f
            scrolling -> SCROLLING_ALPHA
            distance > 0 -> FUTURE_ALPHA
            else -> PAST_ALPHA
        },
        animationSpec = tween(durationMillis = 400, easing = lineEasing),
        label = ""
    )

    val end = line.side == VocalSide.End
    val align = if (end) TextAlign.End else TextAlign.Start

    Column(
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
            .graphicsLayer { this.alpha = alpha.value }
    ) {
        FilledText(
            text = line.text,
            words = line.words,
            active = active,
            position = position,
            style = lineStyle.copy(textAlign = align),
            color = color
        )

        line.background?.let { background ->
            FilledText(
                text = background.text,
                words = background.words,
                active = active,
                position = position,
                style = backingStyle.copy(textAlign = align),
                color = color.copy(alpha = 0.8f)
            )
        }

        (line.translation ?: line.transliteration)?.let { translation ->
            Text(
                text = translation,
                style = translationStyle.copy(textAlign = align),
                color = secondaryColor
            )
        }
    }
}

/**
 * Text that, while [active], fills with [color] word by word as [position] passes each word; the
 * word being sung fills from its start edge with a soft edge. Words not sung yet stay dimmed.
 * Without word timing, the active text is simply bright.
 */
// The position is read in the draw phase only
@Suppress("StateParam")
@Composable
private fun FilledText(
    text: String,
    words: List<SyncedWord>,
    active: Boolean,
    position: () -> Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    // The words joined give the text the layout is measured with
    val shown = if (words.isEmpty()) text else words.joinToString("") { it.text }
    val ranges = remember(words) {
        var offset = 0
        words.map { word -> (offset until offset + word.text.length).also { offset += word.text.length } }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fill = active && words.isNotEmpty()

    Text(
        text = shown,
        style = style,
        color = if (fill) color.copy(alpha = color.alpha * UNSUNG_ALPHA) else color,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val result = layout
            if (!fill || result == null) return@drawWithContent
            drawSungWords(result, words, ranges, position(), color)
        }
    )
}

/**
 * Draws the sung part of [layout] in [color] over the dimmed text: the words already sung in full,
 * the word being sung up to its progress with a feathered edge.
 */
private fun DrawScope.drawSungWords(
    layout: TextLayoutResult,
    words: List<SyncedWord>,
    ranges: List<IntRange>,
    positionMs: Long,
    color: Color
) {
    val sung = Path()
    var current: Pair<Rect, Float>? = null
    var currentRtl = false

    words.forEachIndexed { index, word ->
        val range = ranges[index]
        if (range.isEmpty() || range.last >= layout.layoutInput.text.length) return@forEachIndexed
        when {
            positionMs >= word.endMs -> sung.addPath(layout.getPathForRange(range.first, range.last + 1))
            positionMs > word.startMs -> {
                val progress = (positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)
                current = layout.getPathForRange(range.first, range.last + 1).getBounds() to progress
                currentRtl = layout.getBidiRunDirection(range.first) == ResolvedTextDirection.Rtl
            }
        }
    }

    clipPath(sung) { drawText(layout, color = color) }

    current?.let { (bounds, progress) ->
        val feather = 12.dp.toPx()
        val edge = if (currentRtl) bounds.right - bounds.width * progress else bounds.left + bounds.width * progress
        val (from, to) = if (currentRtl) edge + feather to edge - feather else edge - feather to edge + feather
        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
            drawText(
                layout,
                brush = Brush.horizontalGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    startX = from,
                    endX = to
                )
            )
        }
    }
}

/**
 * Three dots for an instrumental gap, the way the ear expects them: nothing while the gap is not
 * playing (the row takes no room); when it starts the room opens and the dots pop in one after
 * the other with a little overshoot; then they fill one by one and "breathe", and just before the
 * next line they swell and vanish. The animation only touches the draw phase.
 */
// The states are read in the draw phase so that the playback clock does not recompose the row
@Suppress("StateParam")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InterludeRow(
    index: Int,
    row: LyricRow.Interlude,
    activeIndex: State<Int>,
    position: () -> Long,
    playing: Boolean,
    reduceMotion: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active by remember(index, activeIndex) { derivedStateOf { activeIndex.value == index } }
    val clock = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(active, playing, reduceMotion) {
        if (!active || !playing || reduceMotion) return@LaunchedEffect
        val start = withFrameMillis { it } - clock.floatValue.toLong()
        while (true) {
            withFrameMillis { clock.floatValue = (it - start).toFloat() }
        }
    }
    // A new gap starts its entrance from the beginning
    LaunchedEffect(active) { if (!active) clock.floatValue = 0f }

    val height by animateDpAsState(
        targetValue = if (active) 40.dp else 0.dp,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "interlude"
    )

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
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
                val now = if (isActive) position() else 0L
                val duration = (row.endMs - row.startMs).coerceAtLeast(1L)
                val fraction = if (isActive) ((now - row.startMs).toFloat() / duration).coerceIn(0f, 1f) else 0f
                if (!isActive) return@drawBehind
                drawInterludeDots(
                    fraction = fraction,
                    remainingMs = (row.endMs - now).toFloat(),
                    clockMs = clock.floatValue,
                    // Paused (or reduced motion): the dots are simply there
                    appearMs = if (playing && !reduceMotion) clock.floatValue else Float.MAX_VALUE,
                    active = true,
                    animated = !reduceMotion,
                    // With the next singer, where the eye goes next
                    right = (layoutDirection == LayoutDirection.Rtl) != (row.side == VocalSide.End),
                    color = color
                )
            }
    )
}

/**
 * Draws the three interlude dots 32 dp from the left edge, or from the right one when [right],
 * centred vertically.
 */
internal fun DrawScope.drawInterludeDots(
    fraction: Float,
    remainingMs: Float,
    clockMs: Float,
    appearMs: Float,
    active: Boolean,
    animated: Boolean,
    right: Boolean,
    color: Color
) {
    val dot = 10.dp.toPx()
    val gap = 6.dp.toPx()
    val margin = 32.dp.toPx()
    val startX = if (right) size.width - margin - dot * 3 - gap * 2 else margin
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
            // The entrance: each dot pops in 90 ms after the one before, overshooting a little
            val appear = if (animated) easeOutBack(((appearMs - k * 90f) / 320f).coerceIn(0f, 1f)) else 1f
            if (appear <= 0f) return@repeat
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = dot / 2 * appear,
                center = Offset(startX + dot / 2 + k * (dot + gap), cy)
            )
        }
    }
}

/** 0 → 1 with a small overshoot at the end (the "back" easing). */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}
