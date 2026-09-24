package app.melogold.android.ui.components.themed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.R

private const val TAB_FADE_IN_MS = 220
private const val TAB_FADE_IN_DELAY_MS = 90
private const val TAB_FADE_OUT_MS = 90

/**
 * The frame of the screens that are not rewritten yet (REWRITE §6.4): a back button and, when
 * the screen has more than one tab, a row of M3 tabs above the content. Replaces ViTune's
 * vertical rail with rotated tab titles.
 *
 * @param key identifies the screen; unused since tabs can no longer be hidden
 * @param topIconButtonId unused: the leading button is always "Back"
 * @param onTopIconButtonClick the back action
 * @param tabIndex the index of the selected tab among all tabs from [tabColumnContent]
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
    content: @Composable AnimatedVisibilityScope.(Int) -> Unit
) {
    val tabs = TabsBuilder.rememberTabs(tabColumnContent)
    val insets = LocalPlayerAwareWindowInsets.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = 64.dp)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onTopIconButtonClick) {
                Icon(
                    painter = painterResource(R.drawable.ms_arrow_back),
                    contentDescription = stringResource(R.string.kit_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            if (tabs.size > 1) PrimaryScrollableTabRow(
                selectedTabIndex = tabIndex.coerceIn(0, tabs.lastIndex),
                containerColor = Color.Transparent,
                edgePadding = 8.dp,
                divider = { },
                modifier = Modifier.weight(1f)
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == tabIndex,
                        onClick = { onTabChange(index) },
                        text = { Text(text = tab.title()) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
                    .fillMaxWidth(),
                label = "tabs",
                content = content
            )
        }
    }
}
