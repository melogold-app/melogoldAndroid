@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.library

import app.melogold.android.LocalAppContainer
import app.melogold.android.models.DownloadState
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Playlist
import app.melogold.android.models.PlaylistPreview
import app.melogold.android.query
import app.melogold.android.ui.components.m3e.IconShape
import app.melogold.android.ui.components.m3e.SegmentedGroupDefaults
import app.melogold.android.ui.components.m3e.ShapeIcon
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.NewPlaylistDialog
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.builtInPlaylistRoute
import app.melogold.android.ui.screens.libraryAlbumsRoute
import app.melogold.android.ui.screens.libraryArtistsRoute
import app.melogold.android.ui.screens.libraryPlaylistsRoute
import app.melogold.android.ui.screens.libraryTracksRoute
import app.melogold.android.ui.screens.localPlaylistRoute
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.TabRootScaffold
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.compose.routing.RouteHandlerScope
import app.melogold.core.data.enums.BuiltInPlaylist

/**
 * The root of the Library (REWRITE §3.2.1): the three collections as tiles, the newest
 * playlists and the saved albums and artists as grouped lists (M3 segmented list items).
 */
@Route
@Composable
fun RouteHandlerScope.LibraryRoot() {
    val model = rememberScreenModel("library/model") { LibraryModel() }
    val counts by model.counts.collectAsState()
    val playlists by model.playlists.collectAsState()
    val nav = LocalMainNav.current
    val listState = rememberLazyListState()
    var creating by rememberSaveable { mutableStateOf(false) }
    val import = rememberImportAction()

    val subtitle = counts?.takeIf { it.favorites > 0 || it.playlists > 0 }?.let {
        listOfNotNull(
            stringResource(R.string.library_in_favorites, it.favorites).takeIf { _ -> it.favorites > 0 },
            pluralStringResource(R.plurals.library_playlists_count, it.playlists, it.playlists)
                .takeIf { _ -> it.playlists > 0 }
        ).joinToString(" · ")
    }

    TabRootScaffold(
        title = stringResource(R.string.nav_library),
        subtitle = subtitle,
        centered = true,
        actions = {
            IconButton(onClick = { creating = true }) {
                Icon(
                    painter = painterResource(R.drawable.ms_playlist_add),
                    contentDescription = stringResource(R.string.library_new_playlist)
                )
            }
        },
        onScrollToTop = { listState.animateScrollToItem(0) }
    ) { contentPadding ->
        val current = counts ?: return@TabRootScaffold

        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "collections") {
                CollectionTiles(
                    favorites = current.favorites,
                    historyTracks = current.historyTracks,
                    onOpen = { builtInPlaylistRoute(it) }
                )
            }

            if (current.isEmpty) item(key = "empty") {
                EmptyLibrary(
                    onFindMusic = { nav.select(TopLevelDestination.Search) },
                    onTrends = { nav.select(TopLevelDestination.Trends) },
                    onImport = import
                )
            } else {
                item(key = "playlists/header") {
                    SectionHeader(
                        title = stringResource(R.string.library_playlists),
                        actionLabel = stringResource(R.string.library_all_count, current.playlists)
                            .takeIf { current.playlists > playlists.size },
                        onAction = { libraryPlaylistsRoute() }
                    )
                }
                item(key = "playlists") {
                    PlaylistGroup(
                        playlists = playlists,
                        onNew = { creating = true },
                        onOpen = { localPlaylistRoute(it.id) }
                    )
                }

                item(key = "saved") {
                    SavedGroup(
                        tracks = current.tracks,
                        albums = current.albums,
                        artists = current.artists,
                        onTracks = { libraryTracksRoute() },
                        onAlbums = { libraryAlbumsRoute() },
                        onArtists = { libraryArtistsRoute() },
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }

                item(key = "import") {
                    ImportRow(onClick = import, modifier = Modifier.padding(top = 24.dp))
                }
            }

            item(key = "bottom") { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    ImportDialogHost()

    if (creating) NewPlaylistDialog(
        onDismiss = { creating = false },
        onCreate = { name ->
            creating = false
            query { Database.insert(Playlist(name = name)) }
        }
    )
}

/**
 * Favorites, Downloads and History: one row of tonal tiles with shaped icons.
 */
@Composable
private fun CollectionTiles(
    favorites: Int,
    historyTracks: Int,
    onOpen: (BuiltInPlaylist) -> Unit
) = Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    CollectionTile(
        title = stringResource(R.string.library_favorites),
        detail = favorites.takeIf { it > 0 }?.toString(),
        icon = R.drawable.ms_favorite_fill,
        shape = IconShape.Heart,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = { onOpen(BuiltInPlaylist.Favorites) },
        modifier = Modifier.weight(1f)
    )
    // The downloaded tracks; the count moves as downloads complete
    val downloads by LocalAppContainer.current.downloads.visible.collectAsState()
    val downloaded = downloads.values.count { it.state == DownloadState.Completed }

    CollectionTile(
        title = stringResource(R.string.library_downloads),
        detail = downloaded.takeIf { it > 0 }?.toString(),
        icon = R.drawable.ms_download,
        shape = IconShape.Cookie9Sided,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        onClick = { onOpen(BuiltInPlaylist.Offline) },
        modifier = Modifier.weight(1f)
    )
    CollectionTile(
        title = stringResource(R.string.library_history),
        // Tracks ever played: after an import from ViTune, where its "Songs" went
        detail = historyTracks.takeIf { it > 0 }?.toString(),
        icon = R.drawable.ms_history,
        shape = IconShape.Clover4Leaf,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        onClick = { onOpen(BuiltInPlaylist.History) },
        modifier = Modifier.weight(1f)
    )
}

@Composable
private fun CollectionTile(
    title: String,
    detail: String?,
    @DrawableRes icon: Int,
    shape: IconShape,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = Surface(
    onClick = onClick,
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = modifier.heightIn(min = 112.dp)
) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ShapeIcon(
            icon = icon,
            shape = shape,
            contentDescription = null,
            size = 44.dp,
            containerColor = containerColor,
            contentColor = contentColor
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail != null) Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * "New playlist" and the newest playlists as one segmented group.
 */
@Composable
private fun PlaylistGroup(
    playlists: List<PlaylistPreview>,
    onNew: () -> Unit,
    onOpen: (PlaylistPreview) -> Unit
) {
    val count = playlists.size + 1

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
    ) {
        SegmentedListItem(
            onClick = onNew,
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = count),
            colors = SegmentedGroupDefaults.colors(),
            leadingContent = {
                LeadingIcon(icon = R.drawable.ms_add)
            }
        ) {
            Text(text = stringResource(R.string.library_new_playlist))
        }

        playlists.forEachIndexed { index, playlist ->
            SegmentedListItem(
                onClick = { onOpen(playlist) },
                shapes = ListItemDefaults.segmentedShapes(index = index + 1, count = count),
                colors = SegmentedGroupDefaults.colors(),
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
 * Saved albums and followed artists: two rows leading to their lists.
 */
@Composable
private fun SavedGroup(
    tracks: Int,
    albums: Int,
    artists: Int,
    onTracks: () -> Unit,
    onAlbums: () -> Unit,
    onArtists: () -> Unit,
    modifier: Modifier = Modifier
) = Column(
    modifier = modifier.padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
) {
    // Everything played, liked, in playlists or downloaded: ViTune's "Songs", which people coming from it look for
    SegmentedListItem(
        onClick = onTracks,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
        colors = SegmentedGroupDefaults.colors(),
        leadingContent = { LeadingIcon(icon = R.drawable.ms_music_note) },
        trailingContent = { CountAndChevron(tracks) }
    ) {
        Text(text = stringResource(R.string.library_all_tracks))
    }
    SegmentedListItem(
        onClick = onAlbums,
        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
        colors = SegmentedGroupDefaults.colors(),
        leadingContent = { LeadingIcon(icon = R.drawable.ms_album) },
        trailingContent = { CountAndChevron(albums) }
    ) {
        Text(text = stringResource(R.string.library_albums))
    }
    SegmentedListItem(
        onClick = onArtists,
        shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
        colors = SegmentedGroupDefaults.colors(),
        leadingContent = { LeadingIcon(icon = R.drawable.ms_person) },
        trailingContent = { CountAndChevron(artists) }
    ) {
        Text(text = stringResource(R.string.library_artists))
    }
}

/** "Import from ViTune or ViMusic" (REWRITE §4.5.5): the library of the app people come from. */
@Composable
private fun ImportRow(onClick: () -> Unit, modifier: Modifier = Modifier) = Column(
    modifier = modifier.padding(horizontal = 16.dp)
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = SegmentedGroupDefaults.colors(),
        leadingContent = { LeadingIcon(icon = R.drawable.ms_input) },
        supportingContent = { Text(text = stringResource(R.string.library_import_description)) }
    ) {
        Text(text = stringResource(R.string.library_import))
    }
}

@Composable
private fun LeadingIcon(@DrawableRes icon: Int) = Box(
    modifier = Modifier.size(48.dp),
    contentAlignment = Alignment.Center
) {
    ShapeIcon(
        icon = icon,
        shape = IconShape.Circle,
        contentDescription = null,
        size = 40.dp,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
}

@Composable
private fun CountAndChevron(count: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Icon(
        painter = painterResource(R.drawable.ms_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The first launch: nothing saved yet (REWRITE §3.2.1).
 */
@Composable
private fun EmptyLibrary(
    onFindMusic: () -> Unit,
    onTrends: () -> Unit,
    onImport: () -> Unit
) = Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 32.dp, vertical = 40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp)
) {
    Text(
        text = stringResource(R.string.library_empty_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
    )
    Text(
        text = stringResource(R.string.library_empty_text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(4.dp))
    Button(onClick = onFindMusic) { Text(text = stringResource(R.string.library_find_music)) }
    TextButton(onClick = onTrends) { Text(text = stringResource(R.string.whatsnew_whats_trending)) }
    // Coming from ViTune or ViMusic: bring the library along
    TextButton(onClick = onImport) { Text(text = stringResource(R.string.library_import)) }
}
