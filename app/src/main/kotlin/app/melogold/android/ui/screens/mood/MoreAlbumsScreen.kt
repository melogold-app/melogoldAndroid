package app.melogold.android.ui.screens.mood

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.ui.kit.CollectionCard
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.LoadableContent
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.compose.routing.RouteHandler
import app.melogold.providers.innertube.Innertube

/** "New albums and singles" (REWRITE §3.9): a grid of the releases with what each one is. */
@Route
@Composable
fun MoreAlbumsScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("new_releases") { BrowseModel(browseId = "FEmusic_new_releases_albums") }
        val page by model.page.collectAsState()
        val layoutDirection = LocalLayoutDirection.current

        CollectionScaffold(title = stringResource(R.string.new_releases_title), subtitle = null, onBack = pop) { padding ->
            LoadableContent(loadable = page, onRetry = model::load, modifier = Modifier.padding(padding)) { content ->
                val albums = content.value.items.firstOrNull()?.items.orEmpty().filterIsInstance<Innertube.AlbumItem>()

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
                    modifier = Modifier.testTag("new_releases_grid")
                ) {
                    items(items = albums, key = { it.key }) { album ->
                        BoxWithConstraints {
                            CollectionCard(
                                title = album.info?.name.orEmpty(),
                                subtitle = album.caption(),
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
