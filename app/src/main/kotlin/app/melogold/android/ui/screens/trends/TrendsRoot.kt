package app.melogold.android.ui.screens.trends

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.melogold.android.R
import app.melogold.android.models.toUiMood
import app.melogold.android.ui.kit.LoadableContent
import app.melogold.android.ui.kit.MoodTile
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.kit.StaleChip
import app.melogold.android.ui.kit.TrackGrid
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.moodRoute
import app.melogold.android.ui.screens.moreMoodsRoute
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.TabRootScaffold
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.compose.routing.RouteHandlerScope
import app.melogold.providers.innertube.Innertube

private val MoodTileMinWidth = 168.dp

/**
 * The root of Trends (REWRITE §3.3): the chart of the user's country as a sideways grid, then moods
 * and genres. The first section the app opens.
 */
@Route
@Composable
fun RouteHandlerScope.TrendsRoot() {
    val model = rememberScreenModel("trends/model") { TrendsModel(catalog) }
    val state by model.state.collectAsState()
    val nav = LocalMainNav.current
    val listState = rememberLazyListState()

    TabRootScaffold(
        title = stringResource(R.string.nav_trends),
        onScrollToTop = { listState.animateScrollToItem(0) },
        isRefreshing = (state as? Loadable.Content)?.refreshing == true,
        onRefresh = model::refresh
    ) { contentPadding ->
        LoadableContent(
            loadable = state,
            onRetry = model::refresh,
            onOpenLibrary = { nav.select(TopLevelDestination.Library) },
            modifier = Modifier.padding(contentPadding)
        ) { content ->
            TrendsContent(
                content = content,
                listState = listState,
                contentPadding = contentPadding,
                onRetry = model::refresh,
                onChartClick = { browseId -> playlistRoute(browseId, null, null, true) },
                onMoodClick = { mood -> moodRoute(mood.toUiMood()) },
                onAllMoodsClick = { moreMoodsRoute() }
            )
        }
    }
}

@Composable
private fun TrendsContent(
    content: Loadable.Content<Innertube.DiscoverPage>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    onChartClick: (browseId: String) -> Unit,
    onMoodClick: (Innertube.Mood.Item) -> Unit,
    onAllMoodsClick: () -> Unit
) = BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val page = content.value
    val moodColumns = (maxWidth / MoodTileMinWidth).toInt().coerceIn(2, 4)
    val moodRows = remember(page.moods, moodColumns) { page.moods.chunked(moodColumns) }
    val trackWidth = min(maxWidth - 48.dp, 420.dp)

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize()
    ) {
        // Always present and never zero-height: the list keeps its first visible item in place, so a
        // chip inserted above it would stay scrolled out of view
        item(key = "status") {
            Column {
                val since = content.staleSince
                AnimatedVisibility(visible = since != null) {
                    if (since != null) StaleChip(
                        since = since,
                        reason = content.staleReason,
                        onClick = onRetry,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        if (page.trending.songs.isNotEmpty()) {
            item(key = "trending/header") {
                SectionHeader(
                    title = stringResource(R.string.trends_trending),
                    actionLabel = stringResource(R.string.trends_full_list),
                    onAction = page.trending.endpoint?.browseId?.let { browseId -> { onChartClick(browseId) } }
                )
            }
            item(key = "trending") {
                TrackGrid(songs = page.trending.songs, itemWidth = trackWidth, numbered = true)
            }
        }

        if (page.moods.isNotEmpty()) {
            item(key = "moods/header") {
                SectionHeader(
                    title = stringResource(R.string.trends_moods),
                    actionLabel = stringResource(R.string.kit_show_all),
                    onAction = onAllMoodsClick
                )
            }
            items(items = moodRows, key = { row -> "moods/" + row.first().key }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { mood ->
                        MoodTile(
                            title = mood.title,
                            stripeColor = Color(mood.stripeColor),
                            onClick = { onMoodClick(mood) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(moodColumns - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
    }
}
