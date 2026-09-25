package app.melogold.android.ui.screens.player.modern

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.models.LyricsSource
import app.melogold.android.ui.kit.DelayedLoadingIndicator

/**
 * Unsynchronized lyrics: selectable text in a free scroll, no highlighting, no following.
 *
 * @param controlsOverlapPx height of the controls drawn over the bottom of this view (0 when none)
 */
@Composable
fun StaticLyricsView(
    text: String,
    source: LyricsSource?,
    mediaId: String,
    controlsOverlapPx: () -> Int,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(top = 36.dp, bottom = bottomPadding + 24.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
            DisableSelection {
                LyricsSourceFooter(source = source, synced = false)
            }
        }
    }
}

/**
 * Where the lyrics came from, under them. Rows cached before the source was kept are synced ones
 * from LRCLIB or KuGou, or plain ones from an unknown provider (nothing is shown for those).
 */
@Composable
fun LyricsSourceFooter(
    source: LyricsSource?,
    synced: Boolean,
    modifier: Modifier = Modifier
) {
    val text = when (source) {
        LyricsSource.YouTubeMusic -> stringResource(R.string.lyrics_source_youtube_music)
        LyricsSource.LrcLib -> stringResource(R.string.lyrics_source_lrclib)
        LyricsSource.KuGou -> stringResource(R.string.lyrics_source_kugou)
        LyricsSource.File -> stringResource(R.string.lyrics_source_file)
        LyricsSource.User -> stringResource(R.string.lyrics_source_user)
        LyricsSource.Melogold -> stringResource(R.string.lyrics_source_melogold)
        null -> if (synced) stringResource(R.string.provided_lyrics_by) else return
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = modifier
            .padding(horizontal = 32.dp, vertical = 24.dp)
            .testTag("lyrics_source")
    )
}

/**
 * The loading indicator while the lyrics load (REWRITE §3.10.3), in the middle of the part of the
 * lyrics area that stays visible: [bottomPadding] is what the controls cover.
 */
@Composable
fun LyricsLoading(
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.lyrics_loading)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
            .semantics { contentDescription = description }
            .testTag("lyrics_loading")
    ) {
        DelayedLoadingIndicator()
    }
}

@Composable
private fun LyricsMessage(
    message: String,
    testTag: String,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit
) = Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    // In the middle of what the controls leave visible, like the loading indicator before it
    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    modifier = modifier
        .fillMaxSize()
        .padding(start = 32.dp, end = 32.dp, bottom = bottomPadding)
        .testTag(testTag)
) {
    Text(
        text = message,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        actions()
    }
}

/** "Lyrics aren't available", with ways to add them. */
@Composable
fun LyricsEmptyState(
    onSearchLrcLib: () -> Unit,
    onImport: () -> Unit,
    onEnterManually: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) = LyricsMessage(
    message = stringResource(R.string.lyrics_unavailable_title),
    testTag = "lyrics_empty",
    bottomPadding = bottomPadding,
    modifier = modifier
) {
    FilledTonalButton(onClick = onSearchLrcLib) { Text(text = stringResource(R.string.lyrics_find)) }
    FilledTonalButton(onClick = onImport) { Text(text = stringResource(R.string.lyrics_import_file)) }
    TextButton(onClick = onEnterManually) { Text(text = stringResource(R.string.lyrics_enter_manually)) }
}

/** Fetching failed because of the network. */
@Composable
fun LyricsErrorState(
    onRetry: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) = LyricsMessage(
    message = stringResource(R.string.lyrics_load_failed),
    testTag = "lyrics_error",
    bottomPadding = bottomPadding,
    modifier = modifier
) {
    FilledTonalButton(onClick = onRetry) { Text(text = stringResource(R.string.retry)) }
}
