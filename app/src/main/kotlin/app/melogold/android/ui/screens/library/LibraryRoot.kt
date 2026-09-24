package app.melogold.android.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.builtInPlaylistRoute
import app.melogold.android.ui.screens.home.HomeAlbums
import app.melogold.android.ui.screens.home.HomeArtistList
import app.melogold.android.ui.screens.home.HomeLocalSongs
import app.melogold.android.ui.screens.home.HomePlaylists
import app.melogold.android.ui.screens.home.HomeSongs
import app.melogold.android.ui.screens.localPlaylistRoute
import app.melogold.android.ui.screens.pipedPlaylistRoute
import app.melogold.android.ui.shell.TabRootScaffold
import app.melogold.compose.routing.RouteHandlerScope

private enum class LibraryTab(val title: Int) {
    Playlists(R.string.playlists),
    Songs(R.string.library_tab_songs),
    Artists(R.string.artists),
    Albums(R.string.albums),
    OnDevice(R.string.library_tab_on_device)
}

/**
 * The root of the Library section. **Temporary** (REDESIGN-M3E §6.1): the pre-redesign library
 * lists under a tab row. Task T2.2 replaces it with the root of §2.3.
 */
@Route
@Composable
fun RouteHandlerScope.LibraryRoot() {
    val saveableStateHolder = rememberSaveableStateHolder()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    // The old lists own their scroll state: "to the top" recreates the list
    var generation by rememberSaveable { mutableIntStateOf(0) }

    TabRootScaffold(
        title = stringResource(R.string.nav_library),
        onScrollToTop = { generation++ }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = tabIndex,
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                LibraryTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { if (tabIndex == index) generation++ else tabIndex = index },
                        text = { Text(text = stringResource(tab.title), maxLines = 1) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                saveableStateHolder.SaveableStateProvider(tabIndex) {
                    key(generation) {
                        when (LibraryTab.entries[tabIndex]) {
                            LibraryTab.Playlists -> HomePlaylists(
                                onBuiltInPlaylist = { builtInPlaylistRoute(it) },
                                onPlaylistClick = { localPlaylistRoute(it.id) },
                                onPipedPlaylistClick = { session, playlist ->
                                    pipedPlaylistRoute(
                                        p0 = session.apiBaseUrl.toString(),
                                        p1 = session.token,
                                        p2 = playlist.id.toString()
                                    )
                                }
                            )

                            LibraryTab.Songs -> HomeSongs()

                            LibraryTab.Artists -> HomeArtistList(
                                onArtistClick = { artistRoute(it.id) }
                            )

                            LibraryTab.Albums -> HomeAlbums(
                                onAlbumClick = { albumRoute(it.id) }
                            )

                            LibraryTab.OnDevice -> HomeLocalSongs()
                        }
                    }
                }
            }
        }
    }
}
