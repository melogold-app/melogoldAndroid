@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.ui.components.m3e.MelogoldPullToRefreshBox

/**
 * The frame of a section's root screen: a small top app bar above [content] (M3 app bar
 * guidelines: the bar starts in the background color and fills with a container color once the
 * content scrolls under it). The large collapsing headline was dropped on 2026-09-24: on a phone
 * it took a sixth of the screen before any content.
 *
 * Tapping the section's navigation item again while the root is shown calls [onScrollToTop].
 *
 * The bar takes the status bar inset: inside [content], `LocalPlayerAwareWindowInsets` has no top
 * inset, and the `PaddingValues` passed to [content] are its bottom and horizontal insets (the
 * mini player and the navigation bar), ready to be a list's `contentPadding`.
 *
 * With [onRefresh], the content gets pull-to-refresh below the bar.
 *
 * @param subtitle a second line under the title, e.g. counts
 * @param actions up to two icon buttons at the end of the bar
 * @param isRefreshing a refresh started by [onRefresh] is running
 */
@Composable
fun TabRootScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onScrollToTop: suspend () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable (contentPadding: PaddingValues) -> Unit
) {
    val nav = LocalMainNav.current
    val insets = LocalPlayerAwareWindowInsets.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val currentOnScrollToTop by rememberUpdatedState(onScrollToTop)

    LaunchedEffect(nav) {
        nav.reselects.collect { currentOnScrollToTop() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val titleText = @Composable {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val barInsets = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

        if (subtitle == null) TopAppBar(
            title = titleText,
            actions = actions,
            windowInsets = barInsets,
            scrollBehavior = scrollBehavior
        ) else TopAppBar(
            title = titleText,
            subtitle = {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            actions = actions,
            windowInsets = barInsets,
            scrollBehavior = scrollBehavior
        )

        val contentInsets = insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

        CompositionLocalProvider(LocalPlayerAwareWindowInsets provides contentInsets) {
            val contentModifier = Modifier
                .weight(1f)
                .fillMaxWidth()

            // The bar's connection sits below the refresh box, so it sees the scroll first
            val body = @Composable {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                ) {
                    content(contentInsets.asPaddingValues())
                }
            }

            if (onRefresh == null) Box(modifier = contentModifier) { body() }
            else MelogoldPullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = contentModifier
            ) { body() }
        }
    }
}
