@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.library.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.R
import app.melogold.android.data.overrides.TrackOverrides
import app.melogold.android.data.stats.ListeningStats
import app.melogold.android.data.stats.StatsPeriod
import app.melogold.android.data.stats.StatsRepository
import app.melogold.android.data.stats.TopArtist
import app.melogold.android.data.stats.TopTrack
import app.melogold.android.data.stats.statsWindow
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.formatListeningTime
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.compose.routing.RouteHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The cards of the year in review, in their order (tasks/0016). A card with nothing to say is left out. */
enum class WrappedCard { Minutes, TrackOfYear, TopArtists, TopTracks, FavoriteTime, Discoveries }

/** The cards that have something to show for [stats] (the year of it, on all devices). */
fun wrappedCards(stats: ListeningStats): List<WrappedCard> = buildList {
    if (stats.isEmpty) return@buildList
    add(WrappedCard.Minutes)
    if (stats.topTracks.isNotEmpty()) add(WrappedCard.TrackOfYear)
    if (stats.topArtists.isNotEmpty()) add(WrappedCard.TopArtists)
    if (stats.topTracks.isNotEmpty()) add(WrappedCard.TopTracks)
    add(WrappedCard.FavoriteTime)
    if ((stats.discoveries?.count ?: 0) > 0) add(WrappedCard.Discoveries)
}

/** Whole minutes of listening, for "23 104 minutes of music". */
val ListeningStats.minutes: Long get() = totalMs / MS_IN_MINUTE

private const val MS_IN_MINUTE = 60_000L
private const val LIST_SHOWN = 5

/** The state of the year in review: the year's statistics on all devices, counted on the device. */
class WrappedModel(val year: Int) : ScreenModel() {
    val stats: StateFlow<ListeningStats?> = MutableStateFlow<ListeningStats?>(null).also { state ->
        scope.launch {
            state.value = withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                StatsRepository.load(
                    window = statsWindow(StatsPeriod.Year, offset = year - today.year, today = today, zone = zone),
                    overrides = TrackOverrides.all.value,
                    zone = zone,
                    today = today
                )
            }
        }
    }
}

/**
 * The year in review (tasks/0016): six full-screen cards, moved by a tap on the right or left of the screen, by the
 * arrows, or by a swipe. "Share" makes a picture of 1080×1920 with the track of the year.
 */
@Route
@Composable
fun WrappedScreen(year: Int) = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("library/wrapped/$year") { WrappedModel(year) }
        val stats by model.stats.collectAsState()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var sharing by remember { mutableStateOf<WrappedShareInput?>(null) }

        WrappedContent(
            stats = stats,
            year = year,
            onClose = pop,
            onShare = {
                stats?.let { current ->
                    scope.launch { sharing = WrappedShare.prepare(context, current, year) }
                }
            }
        )
        WrappedShareRenderer(input = sharing) { bitmap ->
            sharing = null
            WrappedShare.share(context, bitmap, year)
        }
    }
}

/**
 * The pager of cards without its sources. [stats] null is loading; an empty year says so.
 */
@Composable
fun WrappedContent(
    stats: ListeningStats?,
    year: Int,
    onClose: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 0
) {
    val cards = remember(stats) { stats?.let(::wrappedCards).orEmpty() }
    val pager = rememberPagerState(initialPage = initialPage) { cards.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when {
            stats == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DelayedLoadingIndicator() }
            cards.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.stats_wrapped_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("wrapped_empty")
                )
            }

            else -> {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(cards.size) {
                            // A tap on the left third goes back, anywhere else forward, as in stories
                            detectTapGestures { offset ->
                                val target = if (offset.x < size.width / 3f) pager.currentPage - 1 else pager.currentPage + 1
                                if (target in 0 until cards.size) scope.launch { pager.animateScrollToPage(target) }
                            }
                        }
                ) { page ->
                    WrappedCardPage(card = cards[page], stats = stats, year = year, page = page)
                }
            }
        }

        // The top: which card, and Close
        val pageDescription = wrappedPageDescription(pager.currentPage, cards.size)
        val (cardColor, onCard) = if (cards.isEmpty()) MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
        else wrappedColors(cards[pager.currentPage.coerceIn(cards.indices)])
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp)
        ) {
            if (cards.isNotEmpty()) Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = pageDescription }
                    .testTag("wrapped_progress")
            ) {
                cards.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(onCard.copy(alpha = if (index <= pager.currentPage) 0.9f else 0.28f))
                    )
                }
            } else Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.testTag("wrapped_close")) {
                Icon(
                    painter = painterResource(R.drawable.ms_close),
                    contentDescription = stringResource(R.string.stats_wrapped_close),
                    tint = onCard
                )
            }
        }

        // The bottom: the arrows and Share
        if (cards.isNotEmpty()) Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                enabled = pager.currentPage > 0,
                modifier = Modifier.testTag("wrapped_back")
            ) {
                Icon(painter = painterResource(R.drawable.ms_chevron_left), contentDescription = stringResource(R.string.stats_wrapped_back), tint = onCard)
            }
            // The card is a container of the theme, so the button is its opposite: dark on it, or light
            FilledTonalButton(
                onClick = onShare,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = onCard, contentColor = cardColor),
                modifier = Modifier.testTag("wrapped_share")
            ) {
                Icon(painter = painterResource(R.drawable.ms_share), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text = stringResource(R.string.stats_share), modifier = Modifier.padding(start = 8.dp))
            }
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                enabled = pager.currentPage < cards.lastIndex,
                modifier = Modifier.testTag("wrapped_next")
            ) {
                Icon(painter = painterResource(R.drawable.ms_chevron_right), contentDescription = stringResource(R.string.stats_wrapped_next), tint = onCard)
            }
        }
    }
}

/** The background and the text color of a card: the containers of the theme in turn. */
@Composable
private fun wrappedColors(card: WrappedCard): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (card.ordinal % 3) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
}

@Composable
private fun WrappedCardPage(card: WrappedCard, stats: ListeningStats, year: Int, page: Int) {
    val (background, content) = wrappedColors(card)
    val locale = Locale.getDefault()

    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 72.dp)
            .testTag("wrapped_card_${card.name}")
    ) {
        Text(
            text = stringResource(R.string.stats_wrapped_title, year),
            style = MaterialTheme.typography.labelLarge,
            color = content.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        CompositionLocalContent(content) {
            when (card) {
                WrappedCard.Minutes -> MinutesCard(stats)
                WrappedCard.TrackOfYear -> TrackCard(stats.topTracks.first())
                WrappedCard.TopArtists -> ListCard(
                    title = stringResource(R.string.stats_top_artists),
                    rows = stats.topArtists.take(LIST_SHOWN).map { it.toRow() }
                )

                WrappedCard.TopTracks -> ListCard(
                    title = stringResource(R.string.stats_top_tracks),
                    rows = stats.topTracks.take(LIST_SHOWN).map { it.toRow() }
                )

                WrappedCard.FavoriteTime -> FavoriteCard(stats, locale)
                WrappedCard.Discoveries -> DiscoveriesCard(stats)
            }
        }
    }
}

/** A row of a list card: what, and how long. */
private class WrappedRow(val title: String, val subtitle: String?, val artworkUrl: String?, val ms: Long, val round: Boolean)

private fun TopArtist.toRow() = WrappedRow(name, null, thumbnailUrl, ms, round = true)

private fun TopTrack.toRow() = WrappedRow(title, artist, song.thumbnailUrl, ms, round = false)

@Composable
private fun CompositionLocalContent(color: Color, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color,
        content = content
    )
}

@Composable
private fun MinutesCard(stats: ListeningStats) {
    val minutes = stats.minutes
    Text(
        text = NumberFormat.getIntegerInstance().format(minutes),
        style = MaterialTheme.typography.displayLargeEmphasized,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .testTag("wrapped_minutes")
            .semantics { heading() }
    )
    Text(
        text = pluralStringResource(R.plurals.stats_wrapped_minutes, minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
        style = MaterialTheme.typography.headlineSmallEmphasized,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = listOf(
            pluralStringResource(R.plurals.stats_plays_count, stats.plays, stats.plays),
            pluralStringResource(R.plurals.library_tracks_count, stats.tracks, stats.tracks)
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun TrackCard(track: TopTrack) {
    Text(
        text = stringResource(R.string.stats_wrapped_track),
        style = MaterialTheme.typography.headlineSmallEmphasized,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(modifier = Modifier.height(20.dp))
    Artwork(url = track.song.thumbnailUrl, size = 240.dp, shape = RoundedCornerShape(28.dp))
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = track.title,
        style = MaterialTheme.typography.headlineMediumEmphasized,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("wrapped_track_title")
    )
    track.artist?.let {
        Text(text = it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(
            R.string.stats_wrapped_track_detail,
            pluralStringResource(R.plurals.stats_plays_count, track.plays, track.plays),
            formatListeningTime(track.ms)
        ),
        style = MaterialTheme.typography.labelLarge
    )
}

@Composable
private fun ListCard(title: String, rows: List<WrappedRow>) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmallEmphasized,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(modifier = Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.headlineSmallEmphasized.copy(fontFeatureSettings = "tnum"),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(28.dp)
                )
                Artwork(url = row.artworkUrl, size = 56.dp, shape = if (row.round) CircleShape else RoundedCornerShape(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.subtitle?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(text = formatListeningTime(row.ms), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FavoriteCard(stats: ListeningStats, locale: Locale) {
    val month = stats.busiestBar
    Text(
        text = stringResource(R.string.stats_wrapped_month),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Text(
        text = month?.let { monthName(it.date.month, locale) }.orEmpty(),
        style = MaterialTheme.typography.displayMediumEmphasized,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("wrapped_month")
    )
    month?.let { Text(text = formatListeningTime(it.ms), style = MaterialTheme.typography.bodyLarge) }
    Spacer(modifier = Modifier.height(36.dp))
    Text(
        text = stringResource(R.string.stats_wrapped_time),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Text(
        text = stringResource(dayPartName(stats.favoriteDayPart)),
        style = MaterialTheme.typography.displayMediumEmphasized,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("wrapped_daypart")
    )
    stats.peakHour?.let {
        Text(text = stringResource(R.string.stats_wrapped_peak, "%02d:00".format(it)), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DiscoveriesCard(stats: ListeningStats) {
    val discoveries = stats.discoveries ?: return
    Text(
        text = stringResource(R.string.stats_wrapped_discoveries),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Text(
        text = NumberFormat.getIntegerInstance().format(discoveries.count),
        style = MaterialTheme.typography.displayLargeEmphasized,
        modifier = Modifier.testTag("wrapped_discovered")
    )
    Text(
        text = pluralStringResource(R.plurals.stats_wrapped_new_tracks, discoveries.count),
        style = MaterialTheme.typography.headlineSmallEmphasized
    )
    Spacer(modifier = Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        discoveries.top.forEachIndexed { index, track ->
            val row = track.toRow()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(24.dp)
                )
                Artwork(url = row.artworkUrl, size = 44.dp, shape = RoundedCornerShape(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.subtitle?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** For the page indicator that TalkBack reads: "Card 2 of 6". */
@Composable
fun wrappedPageDescription(page: Int, count: Int): String = stringResource(R.string.stats_wrapped_page, page + 1, count)
