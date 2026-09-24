package app.melogold.android.ui.screens.library

import app.melogold.android.Database
import app.melogold.android.models.Album
import app.melogold.android.models.Artist
import app.melogold.android.models.PlaylistPreview
import app.melogold.android.ui.model.ScreenModel
import app.melogold.core.data.enums.AlbumSortBy
import app.melogold.core.data.enums.ArtistSortBy
import app.melogold.core.data.enums.PlaylistSortBy
import app.melogold.core.data.enums.SortOrder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val HUB_PLAYLISTS = 5
private const val KEEP_WHILE_HIDDEN_MS = 5_000L

/**
 * The counts of the Library hub (REWRITE §3.2.1); null until the database answers, so the empty
 * state never flashes.
 */
data class LibraryCounts(
    val favorites: Int,
    val playlists: Int,
    val albums: Int,
    val artists: Int,
    val plays: Int
) {
    val isEmpty get() = favorites == 0 && playlists == 0 && albums == 0 && artists == 0 && plays == 0
}

class LibraryModel : ScreenModel() {
    val counts: StateFlow<LibraryCounts?> = combine(
        Database.favoritesCount(),
        Database.playlistsCount(),
        Database.savedAlbumsCount(),
        Database.savedArtistsCount(),
        Database.eventsCount()
    ) { favorites, playlists, albums, artists, plays ->
        LibraryCounts(favorites, playlists, albums, artists, plays)
    }.stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    /** The newest playlists shown in the hub. */
    val playlists: StateFlow<List<PlaylistPreview>> = Database
        .playlistPreviews(PlaylistSortBy.DateAdded, SortOrder.Descending)
        .map { it.take(HUB_PLAYLISTS) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), emptyList())
}

/**
 * The full lists opened from the hub.
 */
class LibraryListsModel : ScreenModel() {
    val playlists: StateFlow<List<PlaylistPreview>?> = Database
        .playlistPreviews(PlaylistSortBy.DateAdded, SortOrder.Descending)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val albums: StateFlow<List<Album>?> = Database
        .albums(AlbumSortBy.DateAdded, SortOrder.Descending)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val artists: StateFlow<List<Artist>?> = Database
        .artists(ArtistSortBy.DateAdded, SortOrder.Descending)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)
}
