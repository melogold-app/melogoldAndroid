package app.melogold.android.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import kotlinx.coroutines.launch

/**
 * The width of the collapsed [MainNavigationRail].
 */
val MainNavigationRailWidth = 96.dp

private val RailLabelHorizontalPadding = 16.dp

/**
 * The navigation of phones in landscape and of tablets (REDESIGN-M3E §2.7): a collapsed
 * [WideNavigationRail]; from 840 dp on ([expandable]) the ≡ button expands it.
 *
 * @param compact a low window (phone in landscape): the items are centered with little padding,
 * so that all five fit
 */
@Composable
fun MainNavigationRail(
    nav: MainNavState,
    expandable: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val state = rememberWideNavigationRailState()
    val coroutineScope = rememberCoroutineScope()
    val expanded = expandable && state.targetValue == WideNavigationRailValue.Expanded
    val collapsedLabelStyle = rememberDestinationLabelStyle(
        availableWidth = MainNavigationRailWidth - RailLabelHorizontalPadding
    )

    WideNavigationRail(
        state = state,
        header = if (expandable) {
            {
                IconButton(onClick = { coroutineScope.launch { state.toggle() } }) {
                    Icon(
                        painter = painterResource(R.drawable.ms_menu),
                        contentDescription = stringResource(
                            if (expanded) R.string.nav_collapse_rail else R.string.nav_expand_rail
                        )
                    )
                }
            }
        } else null,
        // The display cutout is already padded by the activity's root
        windowInsets = WindowInsets.systemBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
        arrangement = if (compact) Arrangement.Center else WideNavigationRailDefaults.arrangement,
        contentPadding = if (compact) PaddingValues(vertical = 4.dp) else WideNavigationRailDefaults.ContentPadding,
        modifier = modifier.semantics {
            collectionInfo = CollectionInfo(rowCount = TopLevelDestination.entries.size, columnCount = 1)
        }
    ) {
        TopLevelDestination.entries.forEachIndexed { index, tab ->
            val selected = nav.current == tab

            WideNavigationRailItem(
                selected = selected,
                onClick = { nav.onItemClick(tab) },
                icon = { DestinationIcon(tab = tab, selected = selected, badge = nav.hasBadge(tab)) },
                label = { DestinationLabel(tab = tab, style = if (expanded) null else collapsedLabelStyle) },
                railExpanded = expanded,
                modifier = Modifier.semantics {
                    collectionItemInfo = CollectionItemInfo(
                        rowIndex = index,
                        rowSpan = 1,
                        columnIndex = 0,
                        columnSpan = 1
                    )
                }
            )
        }
    }
}
