@file:OptIn(ExperimentalMaterial3Api::class)

package app.melogold.android.ui.components.themed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.R

private const val TAB_FADE_IN_MS = 220
private const val TAB_FADE_IN_DELAY_MS = 90
private const val TAB_FADE_OUT_MS = 90
private const val MAX_FIXED_TABS = 4

/**
 * The frame of the screens that are not rewritten yet (REWRITE §6.4): a small top app bar with
 * Back and, when the screen has more than one tab, primary tabs right under it (M3 tabs
 * guidelines: fixed up to four tabs, scrollable beyond). Bar and tabs move and fill with color as
 * one unit when the content scrolls under them.
 *
 * @param key identifies the screen; unused since tabs can no longer be hidden
 * @param topIconButtonId unused: the leading button is always Back
 * @param onTopIconButtonClick the back action
 * @param tabIndex the index of the selected tab among all tabs from [tabColumnContent]
 * @param title the headline of the bar, when the screen knows it up front
 */
@Suppress("UnusedParameter")
@Composable
fun Scaffold(
    key: String,
    topIconButtonId: Int,
    onTopIconButtonClick: () -> Unit,
    tabIndex: Int,
    onTabChange: (Int) -> Unit,
    tabColumnContent: TabsBuilder.() -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable AnimatedVisibilityScope.(Int) -> Unit
) {
    val tabs = TabsBuilder.rememberTabs(tabColumnContent)
    val insets = LocalPlayerAwareWindowInsets.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // One color for bar and tabs: the surface at rest, a container color once content is under them
    val containerColor = lerp(
        start = MaterialTheme.colorScheme.surface,
        stop = MaterialTheme.colorScheme.surfaceContainer,
        fraction = scrollBehavior.state.overlappedFraction.coerceIn(0f, 1f)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        TopAppBar(
            title = {
                if (title != null) Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onTopIconButtonClick) {
                    Icon(
                        painter = painterResource(R.drawable.ms_arrow_back),
                        contentDescription = stringResource(R.string.kit_back)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = containerColor
            ),
            windowInsets = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            scrollBehavior = scrollBehavior
        )

        if (tabs.size > 1) {
            val selected = tabIndex.coerceIn(0, tabs.lastIndex)
            val tabItems = @Composable {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == tabIndex,
                        onClick = { onTabChange(index) },
                        text = {
                            Text(
                                text = tab.title(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (tabs.size <= MAX_FIXED_TABS) PrimaryTabRow(
                selectedTabIndex = selected,
                containerColor = containerColor,
                modifier = Modifier.fillMaxWidth()
            ) { tabItems() }
            else PrimaryScrollableTabRow(
                selectedTabIndex = selected,
                containerColor = containerColor,
                modifier = Modifier.fillMaxWidth()
            ) { tabItems() }
        }

        CompositionLocalProvider(
            LocalPlayerAwareWindowInsets provides insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        ) {
            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    fadeIn(tween(durationMillis = TAB_FADE_IN_MS, delayMillis = TAB_FADE_IN_DELAY_MS)) togetherWith
                        fadeOut(tween(durationMillis = TAB_FADE_OUT_MS))
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                label = "tabs",
                content = content
            )
        }
    }
}
