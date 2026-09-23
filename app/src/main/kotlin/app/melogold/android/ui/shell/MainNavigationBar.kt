package app.melogold.android.ui.shell

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The height of [MainNavigationBar] without the system navigation bar below it.
 */
val MainNavigationBarHeight = 64.dp

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
) = ShortNavigationBar(
    modifier = modifier.semantics {
        collectionInfo = CollectionInfo(rowCount = 1, columnCount = TopLevelDestination.entries.size)
    }
) {
    TopLevelDestination.entries.forEachIndexed { index, tab ->
        val selected = nav.current == tab

        ShortNavigationBarItem(
            selected = selected,
            onClick = { nav.onItemClick(tab) },
            icon = { DestinationIcon(tab = tab, selected = selected, badge = nav.hasBadge(tab)) },
            label = { DestinationLabel(tab = tab) },
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

/**
 * Outlined when unselected, filled when selected; a dot when [badge] is set.
 */
@Composable
internal fun DestinationIcon(
    tab: TopLevelDestination,
    selected: Boolean,
    badge: Boolean,
    modifier: Modifier = Modifier
) = BadgedBox(
    badge = { if (badge) Badge() },
    modifier = modifier
) {
    Icon(
        painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
        // The label already names the item
        contentDescription = null
    )
}

/**
 * One line, shrinking down to 10 sp before it would be cut (large font sizes).
 */
@Composable
internal fun DestinationLabel(
    tab: TopLevelDestination,
    modifier: Modifier = Modifier
) {
    val style = MaterialTheme.typography.labelMedium

    Text(
        text = stringResource(tab.label),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 10.sp,
            maxFontSize = style.fontSize,
            stepSize = 0.5.sp
        ),
        modifier = modifier
    )
}
