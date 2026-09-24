@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.ui.components.MusicBars

private val RowShape = RoundedCornerShape(16.dp)

/**
 * A track in a list (REWRITE §3.11.1): cover (or a chart [number] in front of it), title,
 * "Artist · Album", marks, duration and ⋮. Tap plays, long tap and ⋮ open the menu; without
 * [onMenu] (e.g. inside focused search, a dialog of its own) there is no menu.
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    onClick: () -> Unit,
    onMenu: (() -> Unit)?,
    modifier: Modifier = Modifier,
    number: Int? = null,
    showArtwork: Boolean = true,
    isPlaying: Boolean = false,
    explicit: Boolean = false,
    duration: String? = null,
    leading: (@Composable () -> Unit)? = null,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList()
) {
    val playLabel = stringResource(R.string.kit_play)
    val menuLabel = stringResource(R.string.kit_menu)

    // One line without a cover is a one-line list item (56 dp), the rest are two-line ones
    val minHeight = if (showArtwork || !subtitle.isNullOrBlank()) 72.dp else 56.dp

    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(RowShape)
            .background(if (isPlaying) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .semantics(mergeDescendants = true) {
                customActions = listOfNotNull(
                    CustomAccessibilityAction(playLabel) { onClick(); true },
                    onMenu?.let { CustomAccessibilityAction(menuLabel) { it(); true } }
                ) + accessibilityActions
            }
            .padding(start = if (number != null || leading != null) 4.dp else 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val playingOnNumber = isPlaying && !showArtwork

        leading?.invoke()

        if (number != null) Box(
            modifier = Modifier.width(32.dp),
            contentAlignment = Alignment.Center
        ) {
            if (playingOnNumber) MusicBars(
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                barWidth = 3.dp,
                space = 2.dp,
                modifier = Modifier.height(16.dp)
            ) else Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        if (showArtwork) Box {
            Artwork(url = artworkUrl, size = 56.dp)
            if (isPlaying) Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                MusicBars(
                    color = Color.White,
                    barWidth = 3.dp,
                    space = 2.dp,
                    modifier = Modifier.height(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (explicit) Icon(
            painter = painterResource(R.drawable.explicit),
            contentDescription = stringResource(R.string.kit_explicit),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )

        if (duration != null) Text(
            text = duration,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )

        if (onMenu != null) IconButton(onClick = onMenu) {
            Icon(
                painter = painterResource(R.drawable.ms_more_vert),
                contentDescription = menuLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else Spacer(modifier = Modifier.width(12.dp))
    }
}
