package app.melogold.android.ui.screens.trends

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.melogold.android.R
import app.melogold.android.models.toUiMood
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.home.HomeDiscovery
import app.melogold.android.ui.screens.moodRoute
import app.melogold.android.ui.screens.moreAlbumsRoute
import app.melogold.android.ui.screens.moreMoodsRoute
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.TabRootScaffold
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.compose.routing.RouteHandlerScope

/**
 * The root of the Trends section. **Temporary** (REDESIGN-M3E §6.1): the pre-redesign "Discover"
 * page (moods and genres, new releases). Task T2.3 replaces it with the root of §2.4.
 */
@Route
@Composable
fun RouteHandlerScope.TrendsRoot() {
    val nav = LocalMainNav.current
    // The old page owns its scroll state: "to the top" recreates it
    var generation by rememberSaveable { mutableIntStateOf(0) }

    TabRootScaffold(
        title = stringResource(R.string.nav_trends),
        onScrollToTop = { generation++ }
    ) {
        key(generation) {
            HomeDiscovery(
                onMoodClick = { mood -> moodRoute(mood.toUiMood()) },
                onNewReleaseAlbumClick = { albumRoute(it) },
                onSearchClick = { nav.select(TopLevelDestination.Search) },
                onMoreMoodsClick = { moreMoodsRoute() },
                onMoreAlbumsClick = { moreAlbumsRoute() },
                onPlaylistClick = { playlistRoute(it, null, null, true) }
            )
        }
    }
}
