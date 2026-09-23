package app.melogold.android.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.service.downloadState
import app.melogold.android.ui.components.BottomSheetMenu
import app.melogold.android.ui.components.BottomSheetState
import app.melogold.android.ui.components.themed.LinearProgressIndicator
import app.melogold.android.ui.screens.player.Player
import kotlin.math.roundToInt

/**
 * How the shell is laid out for the current window (REDESIGN-M3E §2.7).
 *
 * @property useRail a [MainNavigationRail] at the start instead of the [MainNavigationBar] at the
 * bottom: everywhere but on phones in portrait (width under 600 dp, height from 480 dp)
 * @property railExpandable the rail can be expanded (width from 840 dp, unless the window is too
 * low for the ≡ button above the five items, e.g. a phone in landscape)
 * @property compactHeight the window is lower than 480 dp: the rail packs its items tighter
 */
@Immutable
data class ShellLayout(
    val useRail: Boolean,
    val railExpandable: Boolean,
    val compactHeight: Boolean = false
) {
    /**
     * The part of the bottom block below the mini player: the navigation bar, if any.
     */
    val bottomBarHeight: Dp get() = if (useRail) 0.dp else MainNavigationBarHeight
}

@Composable
fun rememberShellLayout(): ShellLayout {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val compactWidth = !sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val compactHeight = !sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val wide = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    return remember(compactWidth, compactHeight, wide) {
        ShellLayout(
            useRail = !compactWidth || compactHeight,
            railExpandable = wide && !compactHeight,
            compactHeight = compactHeight
        )
    }
}

/**
 * The app outside the player's content (REDESIGN-M3E §5.4). Layers, bottom to top:
 * the sections ([TabHost]), the player sheet with the mini player, the navigation bar or rail,
 * the snackbars, the menu sheet.
 *
 * The navigation bar lies above the player sheet, so it gets touches first; as the player
 * expands, the bar slides down (the rail slides to the start) by its size times the expansion.
 */
@Composable
fun AppShell(
    nav: MainNavState,
    layout: ShellLayout,
    playerSheetState: BottomSheetState,
    snackbar: AppSnackbar,
    modifier: Modifier = Modifier,
    bottomBarHeight: Dp = layout.bottomBarHeight,
    onBottomBarHeightChange: (Dp) -> Unit = { }
) = Box(
    modifier = modifier
        .fillMaxSize()
        // The rail slides out to the start as the player expands; keep it out of the cutout padding
        .clipToBounds()
) {
    val density = LocalDensity.current
    val insets = LocalPlayerAwareWindowInsets.current
    val isDownloading by downloadState.collectAsState()
    val navigationBarsInsets = WindowInsets.navigationBars
    var railWidth by remember { mutableStateOf(MainNavigationRailWidth) }

    // Composed before the sections: every stack and sheet handles "back" first
    BackHandler(enabled = nav.current != nav.startTab) {
        nav.select(nav.startTab)
    }

    TabHost(
        nav = nav,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = if (layout.useRail) railWidth else 0.dp)
    )

    AnimatedVisibility(
        visible = isDownloading,
        modifier = Modifier.padding(insets.asPaddingValues())
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )
    }

    Player(
        layoutState = playerSheetState,
        collapsedBottomExtra = bottomBarHeight,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .let { modifier ->
                if (layout.useRail) modifier.layout { measurable, constraints ->
                    // The mini player sits right of the rail, the expanded player covers it
                    val progress = playerSheetState.progress.coerceIn(0f, 1f)
                    val start = (railWidth.toPx() * (1f - progress))
                        .roundToInt()
                        .coerceIn(0, constraints.maxWidth)
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth - start)
                    )

                    layout(placeable.width + start, placeable.height) {
                        placeable.place(x = start, y = 0)
                    }
                } else modifier
            }
    )

    if (layout.useRail) MainNavigationRail(
        nav = nav,
        expandable = layout.railExpandable,
        compact = layout.compactHeight,
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxHeight()
            .onSizeChanged { railWidth = with(density) { it.width.toDp() } }
            .graphicsLayer {
                translationX = -size.width * playerSheetState.progress.coerceIn(0f, 1f)
            }
    ) else MainNavigationBar(
        nav = nav,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .onSizeChanged {
                // Larger font scales make the bar taller: the mini player moves up with it
                val bottomInset = navigationBarsInsets.getBottom(density)
                onBottomBarHeightChange(
                    with(density) { (it.height - bottomInset).toDp() }.coerceAtLeast(MainNavigationBarHeight)
                )
            }
            .graphicsLayer {
                translationY = size.height * playerSheetState.progress.coerceIn(0f, 1f)
            }
    )

    AppSnackbarHost(
        snackbar = snackbar,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(
                insets
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    .asPaddingValues()
            )
    )

    BottomSheetMenu(modifier = Modifier.align(Alignment.BottomCenter))
}
