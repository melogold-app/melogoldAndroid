package app.melogold.android.ui.screens.whatsnew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.melogold.android.R
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.home.QuickPicks
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.android.ui.shell.TabRootScaffold
import app.melogold.compose.routing.RouteHandlerScope

/**
 * The root of the "What's new" section. **Temporary** (REDESIGN-M3E §6.1): the pre-redesign
 * "Quick picks" page. Task T2.3 replaces it with the root of §2.5.
 */
@Route
@Composable
fun RouteHandlerScope.WhatsNewRoot() {
    // The old page owns its scroll state: "to the top" recreates it
    var generation by rememberSaveable { mutableIntStateOf(0) }

    TabRootScaffold(
        title = stringResource(R.string.nav_whats_new),
        onScrollToTop = { generation++ }
    ) {
        key(generation) {
            QuickPicks(
                onAlbumClick = { albumRoute(it.key) },
                onArtistClick = { artistRoute(it.key) },
                onPlaylistClick = {
                    playlistRoute(
                        p0 = it.key,
                        p1 = null,
                        p2 = null,
                        p3 = it.channel?.name == "YouTube Music"
                    )
                }
            )
        }
    }
}
