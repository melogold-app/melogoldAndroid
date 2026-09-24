package app.melogold.android.ui.screens.mood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.models.toUiMood
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.LoadableContent
import app.melogold.android.ui.kit.MoodTile
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.moodRoute
import app.melogold.compose.routing.RouteHandler
import app.melogold.providers.innertube.Innertube

/** "All moods" (REWRITE §3.9): the tiles of moods and genres under their headings. */
@Route
@Composable
fun MoreMoodsScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("moods/all") { BrowseModel(browseId = "FEmusic_moods_and_genres") }
        val page by model.page.collectAsState()
        val layoutDirection = LocalLayoutDirection.current

        CollectionScaffold(title = stringResource(R.string.moods_and_genres), subtitle = null, onBack = pop) { padding ->
            LoadableContent(loadable = page, onRetry = model::load, modifier = Modifier.padding(padding)) { content ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                        end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("moods_grid")
                ) {
                    content.value.items.forEachIndexed { index, section ->
                        val moods = section.items.filterIsInstance<Innertube.Mood.Item>()
                        if (moods.isEmpty()) return@forEachIndexed

                        item(key = "title_$index", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(title = section.title.orEmpty(), startPadding = 0.dp)
                        }
                        items(items = moods, key = { "mood_${index}_${it.key}" }) { mood ->
                            MoodTile(
                                title = mood.title,
                                stripeColor = Color(mood.stripeColor),
                                onClick = { mood.endpoint.browseId?.let { moodRoute(mood.toUiMood()) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
