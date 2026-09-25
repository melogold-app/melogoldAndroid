package app.melogold.android.ui.screens.mood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.melogold.android.models.Mood
import app.melogold.android.ui.kit.ArtistAvatar
import app.melogold.android.ui.kit.CollectionCard
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.LoadableContent
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.compose.routing.RouteHandler
import app.melogold.providers.innertube.Innertube

/** The link back to all moods that some mood pages carry: the screen doesn't need it. */
private const val ALL_MOODS_BROWSE_ID = "FEmusic_moods_and_genres_category"

/**
 * A mood or genre (REWRITE §3.9): its carousels of playlists and albums in the order YouTube Music
 * gives them, no tabs.
 */
@Route
@Composable
fun MoodScreen(mood: Mood) = RouteHandler {
    GlobalRoutes()

    Content {
        val browseId = mood.browseId ?: ALL_MOODS_BROWSE_ID
        val model = rememberScreenModel("mood/$browseId/${mood.params}") { BrowseModel(browseId, mood.params) }
        val page by model.page.collectAsState()

        CollectionScaffold(title = mood.name, subtitle = null, onBack = pop) { padding ->
            LoadableContent(loadable = page, onRetry = model::load, modifier = Modifier.padding(padding)) { content ->
                LazyColumn(contentPadding = padding, modifier = Modifier.testTag("mood_sections")) {
                    content.value.items.forEachIndexed { index, section ->
                        val items = section.items.filter { it.key != ALL_MOODS_BROWSE_ID }
                        if (items.isEmpty()) return@forEachIndexed

                        item(key = "title_$index") { SectionHeader(title = section.title.orEmpty()) }
                        item(key = "row_$index") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(items = items, key = { it.key }) { item ->
                                    when (item) {
                                        is Innertube.AlbumItem -> CollectionCard(
                                            title = item.info?.name.orEmpty(),
                                            subtitle = item.caption(),
                                            artworkUrl = item.thumbnail?.url,
                                            onClick = { albumRoute(item.key) },
                                            size = 140.dp
                                        )

                                        is Innertube.PlaylistItem -> CollectionCard(
                                            title = item.info?.name.orEmpty(),
                                            subtitle = item.channel?.name,
                                            artworkUrl = item.thumbnail?.url,
                                            onClick = {
                                                item.info?.endpoint?.let { endpoint ->
                                                    endpoint.browseId?.let { playlistRoute(it, endpoint.params, null, false) }
                                                }
                                            },
                                            size = 140.dp
                                        )

                                        is Innertube.ArtistItem -> ArtistAvatar(
                                            name = item.info?.name.orEmpty(),
                                            artworkUrl = item.thumbnail?.url,
                                            onClick = { artistRoute(item.key) }
                                        )

                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
