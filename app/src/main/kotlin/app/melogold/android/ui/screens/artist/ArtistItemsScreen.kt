package app.melogold.android.ui.screens.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.melogold.android.ui.kit.CollectionCard
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.LoadableContent
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.classify
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.compose.routing.RouteHandler
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.requests.itemsPage
import app.melogold.providers.innertube.utils.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A whole section of an artist behind "All ›": the albums or singles of the discography. */
class ArtistItemsModel(
    private val browseId: String,
    private val params: String?
) : ScreenModel() {
    private val mutableAlbums = MutableStateFlow<Loadable<List<Innertube.AlbumItem>>>(Loadable.Loading)
    val albums: StateFlow<Loadable<List<Innertube.AlbumItem>>> = mutableAlbums.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableAlbums.value = Loadable.Loading
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                Innertube.itemsPage(
                    body = BrowseBody(browseId = browseId, params = params),
                    fromTwoRowRenderer = Innertube.AlbumItem::from
                )
            }
            val items = result?.getOrNull()?.items

            mutableAlbums.value = when {
                items != null -> Loadable.Content(items.distinctBy { it.key })
                else -> Loadable.Error(result?.exceptionOrNull()?.let(::classify) ?: Loadable.Error.Kind.Parser)
            }
        }
    }
}

/** `artistItemsRoute` (REWRITE §3.7.1): the albums or singles of an artist as a grid. */
@Route
@Composable
fun ArtistItemsScreen(
    browseId: String,
    params: String?,
    title: String,
    subtitle: String?
) = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("artist_items/$browseId/$params") { ArtistItemsModel(browseId, params) }
        val albums by model.albums.collectAsState()
        val layoutDirection = LocalLayoutDirection.current

        CollectionScaffold(title = title, subtitle = subtitle, onBack = pop, centered = false) { padding ->
            LoadableContent(
                loadable = albums,
                onRetry = model::load,
                modifier = Modifier.padding(padding)
            ) { content ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                        end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.testTag("artist_items")
                ) {
                    items(items = content.value, key = { it.key }) { album ->
                        BoxWithConstraints {
                            CollectionCard(
                                title = album.info?.name.orEmpty(),
                                subtitle = album.year,
                                artworkUrl = album.thumbnail?.url,
                                onClick = { albumRoute(album.key) },
                                size = maxWidth
                            )
                        }
                    }
                }
            }
        }
    }
}
