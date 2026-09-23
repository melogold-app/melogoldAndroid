package app.melogold.android.ui.screens.player.modern

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.melogold.android.R
import app.melogold.core.ui.LocalAppearance

/**
 * Unsynchronized lyrics: selectable text in a free scroll, no highlighting, no following.
 *
 * @param controlsOverlapPx height of the controls drawn over the bottom of this view (0 when none)
 */
@Composable
fun StaticLyricsView(
    text: String,
    mediaId: String,
    controlsOverlapPx: () -> Int,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val typography = LocalAppearance.current.typography
    val scrollState = remember(mediaId) { ScrollState(0) }

    SelectionContainer(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()

                val h = size.height
                if (h <= 0f) return@drawWithContent
                val top = 24.dp.toPx()
                val fadeEnd = (h - controlsOverlapPx()).coerceAtLeast(top + 2f)
                val fadeStart = (fadeEnd - 72.dp.toPx()).coerceAtLeast(top + 1f)

                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        (top / h).coerceIn(0f, 1f) to Color.Black,
                        (fadeStart / h).coerceIn(0f, 1f) to Color.Black,
                        (fadeEnd / h).coerceIn(0f, 1f) to Color.Transparent,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
            .testTag("lyrics_static")
    ) {
        BasicText(
            text = text,
            style = typography.l.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 30.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Start,
                textDirection = TextDirection.Content
            ),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp)
                .padding(top = 36.dp, bottom = bottomPadding + 48.dp)
        )
    }
}

/** Breathing dots shown at the anchor while the lyrics load. */
@Composable
fun LyricsLoading(
    anchor: Dp,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val clock = remember { mutableFloatStateOf(0f) }
    val description = stringResource(R.string.lyrics_loading)

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { clock.floatValue = (it - start).toFloat() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .testTag("lyrics_loading")
    ) {
        Spacer(
            modifier = Modifier
                .padding(top = anchor)
                .fillMaxWidth()
                .height(40.dp)
                .drawBehind {
                    val t = clock.floatValue
                    drawInterludeDots(
                        fraction = if (reduceMotion) 0f else (t / 1800f) % 1f,
                        remainingMs = Float.MAX_VALUE,
                        clockMs = t,
                        active = !reduceMotion,
                        animated = !reduceMotion
                    )
                }
        )
    }
}

@Composable
private fun GlassPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val typography = LocalAppearance.current.typography

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(OnArt.glass)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        BasicText(
            text = text,
            style = typography.s.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = OnArt.primary
            )
        )
    }
}

@Composable
private fun LyricsMessage(
    message: String,
    testTag: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit
) {
    val typography = LocalAppearance.current.typography

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(top = 140.dp, start = 32.dp, end = 32.dp)
            .testTag(testTag)
    ) {
        BasicText(
            text = message,
            style = typography.l.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = OnArt.secondary,
                textAlign = TextAlign.Center
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth()
        ) {
            actions()
        }
    }
}

/** "Lyrics aren't available", with ways to add them. */
@Composable
fun LyricsEmptyState(
    onSearchLrcLib: () -> Unit,
    onEnterManually: () -> Unit,
    modifier: Modifier = Modifier
) = LyricsMessage(
    message = stringResource(R.string.lyrics_unavailable_title),
    testTag = "lyrics_empty",
    modifier = modifier
) {
    GlassPill(text = stringResource(R.string.lyrics_search_lrclib), onClick = onSearchLrcLib)
    GlassPill(text = stringResource(R.string.lyrics_enter_manually), onClick = onEnterManually)
}

/** Fetching failed because of the network. */
@Composable
fun LyricsErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) = LyricsMessage(
    message = stringResource(R.string.lyrics_load_failed),
    testTag = "lyrics_error",
    modifier = modifier
) {
    GlassPill(text = stringResource(R.string.retry), onClick = onRetry)
}
