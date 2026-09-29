package app.melogold.android.ui.screens.library.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

private val MaxBarRadius = 8.dp
private val EmptyBarHeight = 3.dp

/**
 * The bars of "When you listened" and "Time of day" (tasks/0016), drawn on a canvas: one bar per value, the highest in
 * the accent color, the empty ones as a thin dash so the row of days keeps its shape. [labelOf] gives the text under a
 * bar (or none), [description] is what TalkBack reads instead of the picture.
 */
@Composable
fun StatsBarChart(
    values: List<Long>,
    labelOf: (index: Int) -> String?,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp
) {
    val peak = values.maxOrNull()?.takeIf { it > 0 }
    val peakAt = peak?.let { values.indexOf(it) }
    val accent = MaterialTheme.colorScheme.primary
    val regular = MaterialTheme.colorScheme.primaryContainer
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val bar = slot * BAR_SHARE
            val radius = min(bar / 2f, MaxBarRadius.toPx())
            val dash = EmptyBarHeight.toPx()

            values.forEachIndexed { index, value ->
                val left = index * slot + (slot - bar) / 2f
                if (peak == null || value <= 0) drawRoundRect(
                    color = empty,
                    topLeft = Offset(left, size.height - dash),
                    size = Size(bar, dash),
                    cornerRadius = CornerRadius(dash / 2f)
                ) else {
                    val barHeight = (size.height * value / peak).coerceAtLeast(dash * 2)
                    drawRoundRect(
                        color = if (index == peakAt) accent else regular,
                        topLeft = Offset(left, size.height - barHeight),
                        size = Size(bar, barHeight),
                        cornerRadius = CornerRadius(radius)
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            values.indices.forEach { index ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    labelOf(index)?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .wrapContentWidth(align = Alignment.CenterHorizontally, unbounded = true)
                                .testTag("stats_bar_label")
                        )
                    }
                }
            }
        }
    }
}

private const val BAR_SHARE = 0.66f
