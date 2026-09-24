package app.melogold.android.ui.screens

import androidx.compose.runtime.Composable
import app.melogold.android.models.Mood
import app.melogold.android.ui.screens.album.AlbumScreen
import app.melogold.android.ui.screens.artist.ArtistScreen
import app.melogold.android.ui.screens.builtinplaylist.BuiltInPlaylistScreen
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
val searchResultRoute = Route1<String>("searchResultRoute")

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

    builtInPlaylistRoute { builtInPlaylist ->
        BuiltInPlaylistScreen(builtInPlaylist = builtInPlaylist)
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

    searchResultRoute { query ->
        SearchResultsEntry(query = query)
    }
}
