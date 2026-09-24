package app.melogold.android.ui.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.melogold.android.utils.thumbnail
import app.melogold.core.ui.utils.px
import coil3.compose.AsyncImage

private const val VIDEO_ASPECT = 16f / 9f

/**
 * A 16:9 preview with its duration, "LIVE" or video count at the bottom end (REWRITE §3.11.2).
 */
@Composable
fun VideoThumbnail(
    url: String?,
    badge: String?,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    width: Dp = 114.dp,
    height: Dp = 64.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
) = Box(
    modifier = modifier
        .size(width = width, height = height)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
) {
    val widthPx = width.px
    if (url != null) AsyncImage(
        model = url.thumbnail(widthPx),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
    if (!badge.isNullOrBlank()) Text(
        text = badge,
        style = MaterialTheme.typography.labelSmall,
        color = if (live) MaterialTheme.colorScheme.onErrorContainer else Color.White,
        maxLines = 1,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(
                color = if (live) MaterialTheme.colorScheme.errorContainer else Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/**
 * A video in a list (REWRITE §3.11.2): the 16:9 preview with its badge, the title on up to two
 * lines and "views · date" under it. Long tap opens the menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    live: Boolean = false,
    onLongClick: (() -> Unit)? = null
) = Row(
    modifier = modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
    VideoThumbnail(url = thumbnailUrl, badge = badge, live = live)
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
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
}

/** A video in a row (an artist's clips): the 16:9 preview with the title and a line under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = 240.dp
) = Column(
    modifier = modifier
        .width(width)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    verticalArrangement = Arrangement.spacedBy(2.dp)
) {
    VideoThumbnail(
        url = thumbnailUrl,
        badge = null,
        width = width,
        height = width / VIDEO_ASPECT,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(bottom = 6.dp)
    )
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    if (!subtitle.isNullOrBlank()) Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
