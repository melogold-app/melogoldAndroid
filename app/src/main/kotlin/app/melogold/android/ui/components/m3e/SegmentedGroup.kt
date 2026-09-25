@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.components.m3e

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.R

/**
 * Collects the rows of a [SegmentedGroup]; each row receives the shapes of its position (outer
 * corners 16dp on the first and last row, inner corners 4dp).
 */
class SegmentedGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable (shapes: ListItemShapes) -> Unit>()

    fun row(content: @Composable (shapes: ListItemShapes) -> Unit) {
        rows += content
    }
}

/**
 * A grouped list: rows separated by the 2dp segmented gap (settings, "All music", playlists,
 * devices). Use [SegmentedRow] for the rows, or any composable that applies the given shapes.
 *
 * ```
 * SegmentedGroup {
 *     row { SegmentedRow(headline = "Theme and color", shapes = it, onClick = { … }) }
 * }
 * ```
 */
@Composable
fun SegmentedGroup(
    modifier: Modifier = Modifier,
    content: SegmentedGroupScope.() -> Unit
) {
    val rows = SegmentedGroupScope().apply(content).rows

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
    ) {
        rows.forEachIndexed { index, row ->
            row(ListItemDefaults.segmentedShapes(index = index, count = rows.size))
        }
    }
}

/**
 * Shapes of the row at [index] of [count], for grouped rows that live in a lazy list.
 */
@Composable
fun segmentedShapes(index: Int, count: Int): ListItemShapes =
    ListItemDefaults.segmentedShapes(index = index, count = count)

object SegmentedGroupDefaults {
    @Composable
    fun colors(): ListItemColors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/**
 * A row of a [SegmentedGroup]: optional icon in a tonal circle, headline, optional summary line
 * and a trailing slot (a value with "›", a switch, a count, …).
 *
 * @param onClick `null` makes the row non-interactive
 * @param iconDescription what the icon says to TalkBack when it tells something the headline doesn't (a kind of device)
 */
@Composable
fun SegmentedRow(
    headline: String,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    iconDescription: String? = null,
    enabled: Boolean = true,
    colors: ListItemColors = SegmentedGroupDefaults.colors(),
    trailing: (@Composable () -> Unit)? = null
) {
    val leading: (@Composable () -> Unit)? = icon?.let {
        {
            ShapeIcon(
                icon = it,
                shape = IconShape.Circle,
                contentDescription = iconDescription,
                size = 40.dp
            )
        }
    }
    val supportingContent: (@Composable () -> Unit)? = supporting?.let {
        { Text(text = it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
    val headlineContent: @Composable () -> Unit = {
        Text(text = headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

    if (onClick == null) SegmentedListItem(
        shapes = shapes,
        modifier = modifier,
        enabled = enabled,
        leadingContent = leading,
        trailingContent = trailing,
        supportingContent = supportingContent,
        colors = colors,
        content = headlineContent
    ) else SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        modifier = modifier,
        enabled = enabled,
        leadingContent = leading,
        trailingContent = trailing,
        supportingContent = supportingContent,
        colors = colors,
        content = headlineContent
    )
}

/**
 * The usual trailing content of a navigating row: the current value (if any) and a chevron.
 */
@Composable
fun SegmentedRowValue(value: String?, modifier: Modifier = Modifier) = Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically
) {
    value?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
    Icon(
        painter = painterResource(R.drawable.ms_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp)
    )
}
