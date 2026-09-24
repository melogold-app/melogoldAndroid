package app.melogold.android.ui.screens

import app.melogold.android.ui.screens.library.collections.CachedScreen
import app.melogold.android.ui.screens.library.collections.FavoritesScreen
import app.melogold.android.ui.screens.library.collections.HistoryMode
import app.melogold.android.ui.screens.library.collections.HistoryScreen
import app.melogold.android.ui.shell.SearchSource
import app.melogold.compose.routing.Route2
import androidx.compose.runtime.Composable
import app.melogold.android.models.Mood
import app.melogold.android.ui.screens.album.AlbumScreen
import app.melogold.android.ui.screens.artist.ArtistItemsScreen
import app.melogold.android.ui.screens.artist.ArtistScreen
import app.melogold.android.ui.screens.library.collections.ArtistFavoritesScreen
import app.melogold.android.ui.screens.library.LibraryAlbumsScreen
import app.melogold.android.ui.screens.library.LibraryArtistsScreen
import app.melogold.android.ui.screens.library.LibraryPlaylistsScreen
import app.melogold.android.ui.screens.localplaylist.LocalPlaylistScreen
import app.melogold.android.ui.screens.mood.MoodScreen
import app.melogold.android.ui.screens.mood.MoreAlbumsScreen
import app.melogold.android.ui.screens.mood.MoreMoodsScreen
import app.melogold.android.ui.screens.playlist.PlaylistScreen
import app.melogold.android.ui.screens.search.SearchResultsEntry
import app.melogold.android.ui.screens.settings.LogsScreen
import app.melogold.android.ui.screens.settings.SettingsPage
import app.melogold.android.ui.screens.settings.SettingsPageScreen
import app.melogold.compose.routing.Route0
import app.melogold.compose.routing.Route1
import app.melogold.compose.routing.Route4
import app.melogold.compose.routing.RouteHandlerScope
import app.melogold.core.data.enums.BuiltInPlaylist

/**
 * Marker class for linters that a composable is a route and should not be handled like a regular
 * composable, but rather as an entrypoint.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class Route

val albumRoute = Route1<String>("albumRoute")
val artistRoute = Route1<String>("artistRoute")
val artistItemsRoute = Route4<String, String?, String, String?>("artistItemsRoute")
val artistFavoritesRoute = Route2<String, String>("artistFavoritesRoute")
val builtInPlaylistRoute = Route1<BuiltInPlaylist>("builtInPlaylistRoute")
val localPlaylistRoute = Route1<Long>("localPlaylistRoute")
val logsRoute = Route0("logsRoute")
val playlistRoute = Route4<String, String?, Int?, Boolean>("playlistRoute")
val moodRoute = Route1<Mood>("moodRoute")
val moreMoodsRoute = Route0("moreMoodsRoute")
val moreAlbumsRoute = Route0("moreAlbumsRoute")
val libraryPlaylistsRoute = Route0("libraryPlaylistsRoute")
val libraryAlbumsRoute = Route0("libraryAlbumsRoute")
val libraryArtistsRoute = Route0("libraryArtistsRoute")
val settingsPageRoute = Route1<SettingsPage>("settingsPageRoute")
val searchResultRoute = Route2<String, SearchSource>("searchResultRoute")

/**
 * The detail screens every stack knows: they open in the stack of the current section
 * (REDESIGN-M3E §1.3). Settings is a section of its own and has no route.
 */
@Composable
fun RouteHandlerScope.GlobalRoutes() {
    albumRoute { browseId ->
        AlbumScreen(browseId = browseId)
    }

    artistRoute { browseId ->
        ArtistScreen(browseId = browseId)
    }

    artistItemsRoute { browseId, params, title, subtitle ->
        ArtistItemsScreen(browseId = browseId, params = params, title = title, subtitle = subtitle)
    }

    artistFavoritesRoute { artistId, name ->
        ArtistFavoritesScreen(artistId = artistId, name = name)
    }

    // The collections of the Library (REWRITE §3.2.2–3.2.4); "Top" became History › Most played
    builtInPlaylistRoute { builtInPlaylist ->
        when (builtInPlaylist) {
            BuiltInPlaylist.Favorites -> FavoritesScreen()
            BuiltInPlaylist.Offline -> CachedScreen()
            BuiltInPlaylist.History -> HistoryScreen(initialMode = HistoryMode.Recent)
            BuiltInPlaylist.Top -> HistoryScreen(initialMode = HistoryMode.MostPlayed)
        }
    }

    libraryPlaylistsRoute {
        LibraryPlaylistsScreen()
    }

    libraryAlbumsRoute {
        LibraryAlbumsScreen()
    }

    libraryArtistsRoute {
        LibraryArtistsScreen()
    }

    settingsPageRoute { page ->
        SettingsPageScreen(page = page)
    }

    localPlaylistRoute { playlistId ->
        LocalPlaylistScreen(playlistId = playlistId)
    }

    logsRoute {
        LogsScreen()
    }

    moodRoute { mood ->
        MoodScreen(mood = mood)
    }

    moreMoodsRoute {
        MoreMoodsScreen()
    }

    moreAlbumsRoute {
        MoreAlbumsScreen()
    }

    playlistRoute { browseId, params, maxDepth, shouldDedup ->
        PlaylistScreen(
            browseId = browseId,
            params = params,
            maxDepth = maxDepth,
            shouldDedup = shouldDedup
        )
    }

    searchResultRoute { query, source ->
        SearchResultsEntry(query = query, source = source)
    }
}
