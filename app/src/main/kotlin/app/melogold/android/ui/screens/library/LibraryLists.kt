@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.R
import app.melogold.android.ui.components.themed.Scaffold
import app.melogold.android.ui.kit.ArtistAvatar
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.CollectionCard
import app.melogold.android.ui.kit.groupedListColors
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.localPlaylistRoute
import app.melogold.compose.routing.RouteHandler
import app.melogold.compose.routing.RouteHandlerScope

/**
 * All playlists of the Library, newest first.
 */
@Route
@Composable
fun LibraryPlaylistsScreen() = LibraryListScreen(title = R.string.library_playlists) {
    val model = rememberScreenModel("library/lists") { LibraryListsModel() }
    val playlists by model.playlists.collectAsState()
    val list = playlists ?: return@LibraryListScreen

    if (list.isEmpty()) EmptyList(R.string.library_no_playlists)
    else LazyColumn(
        contentPadding = contentPadding(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        itemsIndexed(items = list, key = { _, playlist -> playlist.id }) { index, playlist ->
            SegmentedListItem(
                onClick = { localPlaylistRoute(playlist.id) },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = list.size),
                colors = groupedListColors(),
                leadingContent = {
                    Artwork(url = playlist.thumbnail, size = 48.dp, shape = RoundedCornerShape(10.dp))
                },
                supportingContent = {
                    Text(text = pluralStringResource(R.plurals.library_tracks_count, playlist.songCount, playlist.songCount))
                }
            ) {
                Text(text = playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Saved albums as a grid of covers.
 */
@Route
@Composable
fun LibraryAlbumsScreen() = LibraryListScreen(title = R.string.library_albums) {
    val model = rememberScreenModel("library/lists") { LibraryListsModel() }
    val albums by model.albums.collectAsState()
    val list = albums ?: return@LibraryListScreen

    if (list.isEmpty()) EmptyList(R.string.library_no_albums)
    else LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 152.dp),
        contentPadding = contentPadding(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        items(items = list, key = { it.id }) { album ->
            CollectionCard(
                title = album.title.orEmpty(),
                subtitle = listOfNotNull(album.authorsText, album.year).joinToString(" · "),
                artworkUrl = album.thumbnailUrl,
                onClick = { albumRoute(album.id) },
                size = 152.dp
            )
        }
    }
}

/**
 * Followed artists and channels as a grid of round photos.
 */
@Route
@Composable
fun LibraryArtistsScreen() = LibraryListScreen(title = R.string.library_artists) {
    val model = rememberScreenModel("library/lists") { LibraryListsModel() }
    val artists by model.artists.collectAsState()
    val list = artists ?: return@LibraryListScreen

    if (list.isEmpty()) EmptyList(R.string.library_no_artists)
    else LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = contentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        items(items = list, key = { it.id }) { artist ->
            ArtistAvatar(
                name = artist.name.orEmpty(),
                artworkUrl = artist.thumbnailUrl,
                onClick = { artistRoute(artist.id) }
            )
        }
    }
}

/**
 * The frame of a list opened from the Library hub: the small app bar with Back and [title].
 */
@Composable
private fun LibraryListScreen(
    title: Int,
    content: @Composable RouteHandlerScope.() -> Unit
) = RouteHandler {
    GlobalRoutes()

    Content {
        Scaffold(
            key = "library-list",
            topIconButtonId = 0,
            onTopIconButtonClick = pop,
            tabIndex = 0,
            onTabChange = { },
            tabColumnContent = { tab(0, title, R.drawable.ms_library_music) },
            title = stringResource(title)
        ) {
            content()
        }
    }
}

@Composable
private fun contentPadding(): PaddingValues = LocalPlayerAwareWindowInsets.current
    .only(WindowInsetsSides.Bottom)
    .asPaddingValues()

@Composable
private fun EmptyList(text: Int) = Box(
    modifier = Modifier
        .fillMaxSize()
        .padding(32.dp),
    contentAlignment = Alignment.Center
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}
