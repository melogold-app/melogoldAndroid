package app.melogold.android.ui.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.melogold.android.LocalAppContainer
import app.melogold.android.R

/**
 * The height of [MainNavigationBar] without the system navigation bar below it, at the usual font
 * size (larger fonts can make it taller, the shell measures it).
 */
val MainNavigationBarHeight = 64.dp

/**
 * A label never gets smaller than this on screen, whatever the font scale.
 */
private val MinLabelSize = 10.dp

/**
 * The room a label has in its item: the item minus a little padding on each side.
 */
private val LabelHorizontalPadding = 8.dp

/**
 * The bottom bar of phones in portrait (REDESIGN-M3E §2.1): the five sections with always visible
 * labels. TalkBack reads the items as tabs with their position ("tab, 2 of 5").
 *
 * The bar fills the width by itself. Do not give it `fillMaxWidth()`: in material3 1.5.0-alpha27
 * a minimum width constraint is passed on to every item, and each item becomes as wide as the bar.
 */
@Composable
fun MainNavigationBar(
    nav: MainNavState,
    modifier: Modifier = Modifier
) = BoxWithConstraints(modifier = modifier) {
    val labelStyle = rememberDestinationLabelStyle(
        availableWidth = maxWidth / TopLevelDestination.entries.size - LabelHorizontalPadding
    )

    ShortNavigationBar(
        modifier = Modifier.semantics {
            collectionInfo = CollectionInfo(rowCount = 1, columnCount = TopLevelDestination.entries.size)
        }
    ) {
        TopLevelDestination.entries.forEachIndexed { index, tab ->
            val selected = nav.current == tab

            ShortNavigationBarItem(
                selected = selected,
                onClick = { nav.onItemClick(tab) },
                icon = { DestinationIcon(tab = tab, selected = selected, badge = badgeCount(tab)) },
                label = { DestinationLabel(tab = tab, style = labelStyle) },
                modifier = Modifier.semantics {
                    collectionItemInfo = CollectionItemInfo(
                        rowIndex = 0,
                        rowSpan = 1,
                        columnIndex = index,
                        columnSpan = 1
                    )
                }
            )
        }
    }
}

/**
 * The number on the item of [tab]: Settings counts an app update waiting to be installed
 * (REWRITE §4.14).
 */
@Composable
internal fun badgeCount(tab: TopLevelDestination): Int {
    if (tab != TopLevelDestination.Settings) return 0
    val update by LocalAppContainer.current.updates.state.collectAsState()
    return if (update.pending != null) 1 else 0
}

/**
 * Outlined when unselected, filled when selected; a number when [badge] is above zero.
 */
@Composable
internal fun DestinationIcon(
    tab: TopLevelDestination,
    selected: Boolean,
    badge: Int,
    modifier: Modifier = Modifier
) = BadgedBox(
    badge = {
        if (badge > 0) {
            val description = pluralStringResource(R.plurals.nav_badge, badge, badge)
            Badge(modifier = Modifier.semantics { contentDescription = description }) { Text(text = badge.toString()) }
        }
    },
    modifier = modifier
) {
    Icon(
        painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
        // The label already names the item
        contentDescription = null
    )
}

/**
 * One line in [style] (see [rememberDestinationLabelStyle]); `null` keeps the item's own style.
 */
@Composable
internal fun DestinationLabel(
    tab: TopLevelDestination,
    style: TextStyle?,
    modifier: Modifier = Modifier
) = Text(
    text = stringResource(tab.label),
    style = style ?: LocalTextStyle.current,
    maxLines = 1,
    softWrap = false,
    overflow = TextOverflow.Clip,
    modifier = modifier
)

/**
 * `labelMedium`, shrunk just enough for every one of the five labels to fit [availableWidth] on
 * one line (large font scales, long translations), but not below 10 dp. All items share the size,
 * so that the labels stay even; the line height follows the size.
 */
@Composable
internal fun rememberDestinationLabelStyle(availableWidth: Dp): TextStyle {
    val base = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = TopLevelDestination.entries.map { stringResource(it.label) }

    return remember(base, density, availableWidth, labels) {
        val maxWidthPx = with(density) { availableWidth.roundToPx() }
        val minSize = with(density) { MinLabelSize.toSp() }.value.coerceAtMost(base.fontSize.value)

        fun fits(size: Float) = labels.all { label ->
            measurer.measure(
                text = label,
                style = base.copy(fontSize = size.sp),
                maxLines = 1,
                softWrap = false
            ).size.width <= maxWidthPx
        }

        var size = base.fontSize.value
        while (size > minSize && !fits(size)) size = (size - LABEL_SIZE_STEP).coerceAtLeast(minSize)

        base.copy(fontSize = size.sp, lineHeight = LABEL_LINE_HEIGHT.em)
    }
}

private const val LABEL_SIZE_STEP = 0.5f

/**
 * `labelMedium`'s 16 sp line for 12 sp text, as a ratio that follows a shrunk size.
 */
private const val LABEL_LINE_HEIGHT = 4f / 3f
