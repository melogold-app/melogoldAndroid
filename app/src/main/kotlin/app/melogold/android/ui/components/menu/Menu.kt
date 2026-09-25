@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.components.menu

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.ui.kit.Artwork
import androidx.compose.material3.HorizontalDivider

/**
 * The content of a menu sheet (REDESIGN-M3E T2.6): a scrolling column of [MenuHeader],
 * [MenuEntry], [MenuSectionTitle] and [MenuDivider].
 */
@Composable
inline fun Menu(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = Column(
    modifier = modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(bottom = 8.dp),
    content = content
)

/**
 * One action of a menu: a list row of 56 dp (72 dp with [secondaryText]) with a 24 dp icon.
 */
@Composable
fun MenuEntry(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) = ListItem(
    onClick = onClick,
    onLongClick = onLongClick,
    enabled = enabled,
    leadingContent = {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
    },
    supportingContent = secondaryText?.let { { Text(text = it) } },
    trailingContent = trailingContent,
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = modifier.testTag("menu_entry")
) {
    Text(text = text)
}

/**
 * What the menu is about, as its first row: a 56 dp cover, the title and one line under it.
 */
@Composable
fun MenuHeader(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) = ListItem(
    supportingContent = subtitle?.let {
        { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    },
    leadingContent = { Artwork(url = artworkUrl, size = 56.dp) },
    trailingContent = trailingContent,
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = modifier.testTag("menu_header")
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** Where the text of a [MenuEntry] starts: 16 dp padding, the 24 dp icon and 12 dp between. */
val MenuEntryTextStart = 52.dp

/** Names the group of entries under it (the player menu's "Track" and "Playback"). */
@Composable
fun MenuSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) = Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
        .semantics { heading() }
)

/** Separates groups of entries. */
@Composable
fun MenuDivider(modifier: Modifier = Modifier) = HorizontalDivider(
    modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    color = MaterialTheme.colorScheme.outlineVariant
)
