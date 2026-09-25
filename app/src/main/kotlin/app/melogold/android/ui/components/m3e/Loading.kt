@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.components.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.melogold.core.ui.MotionLevel
import app.melogold.core.ui.theme.LocalMotionLevel

/**
 * The M3 Expressive shape-morphing loading indicator. At [MotionLevel.Minimal] (no morphing) it
 * falls back to a circular indicator of the same size.
 */
@Composable
fun MelogoldLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = LoadingIndicatorDefaults.indicatorColor
) = if (LocalMotionLevel.current == MotionLevel.Minimal) Box(
    modifier = modifier.size(
        width = LoadingIndicatorDefaults.ContainerWidth,
        height = LoadingIndicatorDefaults.ContainerHeight
    ),
    contentAlignment = Alignment.Center
) {
    CircularProgressIndicator(
        color = color,
        modifier = Modifier.size(LoadingIndicatorDefaults.IndicatorSize)
    )
} else LoadingIndicator(
    modifier = modifier,
    color = color
)

/**
 * The loading indicator in a tonal container, e.g. in place of play/pause while the stream is
 * being resolved.
 */
@Composable
fun MelogoldContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    containerColor: Color = LoadingIndicatorDefaults.containedContainerColor,
    indicatorColor: Color = LoadingIndicatorDefaults.containedIndicatorColor
) = if (LocalMotionLevel.current == MotionLevel.Minimal) Box(
    modifier = modifier
        .size(
            width = LoadingIndicatorDefaults.ContainerWidth,
            height = LoadingIndicatorDefaults.ContainerHeight
        )
        .background(color = containerColor, shape = LoadingIndicatorDefaults.containerShape),
    contentAlignment = Alignment.Center
) {
    CircularProgressIndicator(
        color = indicatorColor,
        modifier = Modifier
            .size(LoadingIndicatorDefaults.IndicatorSize)
            .padding(4.dp)
    )
} else ContainedLoadingIndicator(
    modifier = modifier,
    containerColor = containerColor,
    indicatorColor = indicatorColor
)

/**
 * [PullToRefreshBox] with the expressive loading indicator (REDESIGN-M3E §2.4, §2.5).
 */
@Composable
fun MelogoldPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    content: @Composable BoxScope.() -> Unit
) = PullToRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    modifier = modifier,
    state = state,
    indicator = {
        PullToRefreshDefaults.LoadingIndicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    },
    content = content
)
