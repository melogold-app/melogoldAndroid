@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.library.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.stats.ListeningStats
import app.melogold.android.data.stats.StatsBar
import app.melogold.android.data.stats.StatsPeriod
import app.melogold.android.data.stats.StatsWindow
import app.melogold.android.data.stats.TopAlbum
import app.melogold.android.data.stats.TopArtist
import app.melogold.android.data.stats.TopTrack
import app.melogold.android.models.Song
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.formatListeningTime
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.library.collections.DeviceFilter
import app.melogold.android.ui.screens.library.collections.HistoryDevice
import app.melogold.android.ui.screens.library.collections.HistoryDeviceEntry
import app.melogold.android.ui.screens.wrappedRoute
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.SearchSource
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import kotlinx.collections.immutable.ImmutableList
import java.text.NumberFormat
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** How many of a top show at first, and how many "Show all" opens ([app.melogold.android.data.stats.TOP_LIMIT]). */
private const val TOP_SHOWN = 10

/**
 * Insights (tasks/0016): how much you listened in a week, month, year or ever, and to what. Everything is counted on
 * the device from the History, with the plays of the account's other devices once the sync brought them.
 */
@Route
@Composable
fun StatsScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("library/stats") { StatsModel() }
        val binder = LocalPlayerServiceBinder.current
        val menuState = LocalMenuState.current
        val nav = LocalMainNav.current
        val (playingId, _) = playingSong(binder)

        val query by model.query.collectAsState()
        val stats by model.stats.collectAsState()
        val devices by model.devices.collectAsState()

        StatsContent(
            stats = stats,
            query = query,
            devices = devices,
            playingId = playingId,
            onBack = pop,
            onPeriod = model::select,
            onDevice = model::select,
            onStep = model::step,
            onPlayTracks = { tracks, index ->
                binder?.stopRadio()
                binder?.player?.forcePlayAtIndex(tracks.map { it.song.asMediaItem }, index)
            },
            onTrackMenu = { song ->
                menuState.display { NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = song.asMediaItem) }
            },
            onArtist = { artist ->
                if (artist.id != null) artistRoute(artist.id) else nav.openSearch(artist.name, SearchSource.Music)
            },
            onAlbum = { album ->
                if (album.id != null) albumRoute(album.id) else nav.openSearch(album.title, SearchSource.Music)
            },
            onWrapped = { year -> wrappedRoute(year) }
        )
    }
}

/**
 * The screen without its sources: what is asked ([query]), what is counted ([stats], null while it is), and what a
 * touch does. The tops open in place up to 50.
 */
@Composable
fun StatsContent(
    stats: ListeningStats?,
    query: StatsQuery,
    devices: ImmutableList<HistoryDeviceEntry>,
    playingId: String?,
    onBack: () -> Unit,
    onPeriod: (StatsPeriod) -> Unit,
    onDevice: (HistoryDevice) -> Unit,
    onStep: (Int) -> Unit,
    onPlayTracks: (List<TopTrack>, Int) -> Unit,
    onTrackMenu: (Song) -> Unit,
    onArtist: (TopArtist) -> Unit,
    onAlbum: (TopAlbum) -> Unit,
    onWrapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now()
) {
    var allTracks by rememberSaveable { mutableStateOf(false) }
    var allArtists by rememberSaveable { mutableStateOf(false) }
    var allAlbums by rememberSaveable { mutableStateOf(false) }

    CollectionScaffold(
        title = stringResource(R.string.stats_title),
        subtitle = null,
        onBack = onBack,
        modifier = modifier
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.testTag("stats_list")) {
            if (devices.isNotEmpty() || query.device != HistoryDevice.All) item(key = "device") {
                DeviceFilter(
                    selected = query.device,
                    devices = devices,
                    onSelect = onDevice,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item(key = "periods") { PeriodChips(selected = query.period, onSelect = onPeriod) }

            if (stats != null && query.period != StatsPeriod.AllTime) item(key = "navigator") {
                PeriodNavigator(
                    title = statsWindowTitle(stats.window, today, Locale.getDefault(), allTime = ""),
                    canGoBack = stats.hasEarlier,
                    canGoForward = !stats.window.isCurrent,
                    onStep = onStep
                )
            }

            when {
                stats == null -> item(key = "loading") { Loading() }
                stats.isEmpty -> {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.stats_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 48.dp)
                                .testTag("stats_empty")
                        )
                    }
                    if (query.period == StatsPeriod.AllTime) item(key = "note") { AllTimeNote() }
                }

                else -> {
                    if (query.period == StatsPeriod.Year) item(key = "wrapped") {
                        FilledTonalButton(
                            onClick = { onWrapped(stats.window.firstDay?.year ?: today.year) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("stats_wrapped")
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ms_star_fill),
                                contentDescription = null,
                                modifier = Modifier.width(18.dp)
                            )
                            Text(text = stringResource(R.string.stats_wrapped), modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    item(key = "numbers") { Numbers(stats = stats) }

                    topTracks(
                        tracks = stats.topTracks,
                        all = allTracks,
                        onToggle = { allTracks = !allTracks },
                        playingId = playingId,
                        onPlay = onPlayTracks,
                        onMenu = onTrackMenu
                    )
                    topArtists(artists = stats.topArtists, all = allArtists, onToggle = { allArtists = !allArtists }, onOpen = onArtist)
                    topAlbums(albums = stats.topAlbums, all = allAlbums, onToggle = { allAlbums = !allAlbums }, onOpen = onAlbum)

                    item(key = "when") { WhenSection(stats = stats, today = today) }

                    stats.discoveries?.takeIf { it.count > 0 }?.let { discoveries ->
                        item(key = "discoveries/header") { SectionHeader(title = stringResource(R.string.stats_discoveries)) }
                        item(key = "discoveries/count") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(
                                    text = pluralStringResource(R.plurals.stats_discovered, discoveries.count, discoveries.count),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.stats_discoveries_text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        trackRows(
                            keyPrefix = "discovery",
                            tracks = discoveries.top,
                            playingId = playingId,
                            onPlay = onPlayTracks,
                            onMenu = onTrackMenu
                        )
                    }

                    if (query.period == StatsPeriod.AllTime) item(key = "note") { AllTimeNote() }
                }
            }

            item(key = "bottom") { Box(modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }
}

@Composable
private fun PeriodChips(selected: StatsPeriod, onSelect: (StatsPeriod) -> Unit) = Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp)
) {
    StatsPeriod.entries.forEach { period ->
        FilterChip(
            selected = period == selected,
            onClick = { onSelect(period) },
            label = { Text(text = stringResource(period.label)) },
            modifier = Modifier.testTag("stats_period_${period.name}")
        )
    }
}

private val StatsPeriod.label: Int
    get() = when (this) {
        StatsPeriod.Week -> R.string.stats_week
        StatsPeriod.Month -> R.string.stats_month
        StatsPeriod.Year -> R.string.stats_year
        StatsPeriod.AllTime -> R.string.stats_all_time
    }

/** "‹ September 2026 ›": the arrows walk through the periods, forward only up to now. */
@Composable
private fun PeriodNavigator(title: String, canGoBack: Boolean, canGoForward: Boolean, onStep: (Int) -> Unit) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp)
) {
    IconButton(onClick = { onStep(-1) }, enabled = canGoBack, modifier = Modifier.testTag("stats_previous")) {
        Icon(painter = painterResource(R.drawable.ms_chevron_left), contentDescription = stringResource(R.string.stats_previous))
    }
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .weight(1f)
            .semantics { heading() }
            .testTag("stats_window_title")
    )
    IconButton(onClick = { onStep(1) }, enabled = canGoForward, modifier = Modifier.testTag("stats_next")) {
        Icon(painter = painterResource(R.drawable.ms_chevron_right), contentDescription = stringResource(R.string.stats_next))
    }
}

/** The listening time with its comparison, and plays, tracks and artists under it. */
@Composable
private fun Numbers(stats: ListeningStats) = Column(
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stats_time")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = stringResource(R.string.stats_listening_time), style = MaterialTheme.typography.labelLarge)
            Text(
                text = listeningTimeText(stats.totalMs),
                style = MaterialTheme.typography.displaySmallEmphasized,
                modifier = Modifier.testTag("stats_time_value")
            )
            changeText(stats)?.let { change ->
                Text(text = change, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("stats_change"))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // "Plays" and "Artists" are the long words; a short one gives them room
        NumberTile(value = stats.plays, label = R.string.stats_plays, modifier = Modifier.weight(1.3f), tag = "stats_plays")
        NumberTile(value = stats.tracks, label = R.string.stats_tracks, modifier = Modifier.weight(0.8f), tag = "stats_tracks")
        NumberTile(value = stats.artists, label = R.string.stats_artists, modifier = Modifier.weight(1.1f), tag = "stats_artists")
    }
}

@Composable
private fun NumberTile(value: Int, label: Int, tag: String, modifier: Modifier = Modifier) = Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = modifier
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(
            text = NumberFormat.getIntegerInstance().format(value),
            style = MaterialTheme.typography.titleLargeEmphasized,
            maxLines = 1,
            modifier = Modifier.testTag(tag)
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "38 h 12 min" (an hour or more) or "40 min". */
@Composable
private fun listeningTimeText(ms: Long) = formatListeningTime(ms)

/** "+12 % vs August", or nothing without a period to compare with. */
@Composable
private fun changeText(stats: ListeningStats): String? {
    val percent = stats.changePercent ?: return null
    val window = stats.window
    val months = stringArrayResource(R.array.stats_months_compare)
    val target = when (window.period) {
        StatsPeriod.Week -> stringResource(R.string.stats_vs_last_week)
        StatsPeriod.Year -> stringResource(R.string.stats_vs_year, (window.firstDay?.year ?: 0) - 1)
        StatsPeriod.Month -> {
            val previous = window.firstDay?.minusMonths(1) ?: return null
            val name = months[previous.monthValue - 1]
            // The month of another year says the year, so "vs December 2025" is not read as this year's
            if (previous.year == LocalDate.now().year) stringResource(R.string.stats_vs_month, name)
            else stringResource(R.string.stats_vs_month_year, name, previous.year)
        }

        StatsPeriod.AllTime -> return null
    }
    return stringResource(R.string.stats_change, signedPercent(percent), target)
}

/** "+12 %", "−8 %", "0 %": the real minus sign, and a no-break space before the sign of percent in Russian. */
fun signedPercent(percent: Int, locale: Locale = Locale.getDefault()): String {
    val sign = when {
        percent > 0 -> "+"
        percent < 0 -> "−"
        else -> ""
    }
    val space = if (locale.language == "ru") " " else ""
    return "$sign${kotlin.math.abs(percent)}$space%"
}

private fun LazyListScope.topTracks(
    tracks: List<TopTrack>,
    all: Boolean,
    onToggle: () -> Unit,
    playingId: String?,
    onPlay: (List<TopTrack>, Int) -> Unit,
    onMenu: (Song) -> Unit
) {
    if (tracks.isEmpty()) return
    item(key = "tracks/header") { SectionHeader(title = stringResource(R.string.stats_top_tracks)) }
    trackRows(keyPrefix = "top", tracks = if (all) tracks else tracks.take(TOP_SHOWN), playingId = playingId, onPlay = onPlay, onMenu = onMenu, queue = tracks)
    if (tracks.size > TOP_SHOWN) item(key = "tracks/all") { ShowAll(all = all, onToggle = onToggle, tag = "stats_tracks_all") }
}

/** The tracks as rows numbered from 1, with the time and the plays; a tap plays [queue] (else [tracks]) from there. */
private fun LazyListScope.trackRows(
    keyPrefix: String,
    tracks: List<TopTrack>,
    playingId: String?,
    onPlay: (List<TopTrack>, Int) -> Unit,
    onMenu: (Song) -> Unit,
    queue: List<TopTrack> = tracks
) {
    itemsIndexedTracks(keyPrefix, tracks) { index, track ->
        TrackRow(
            title = track.title,
            videoId = track.song.id,
            subtitle = track.artist,
            detail = pluralStringResource(R.plurals.stats_plays_count, track.plays, track.plays),
            artworkUrl = track.song.thumbnailUrl,
            onClick = { onPlay(queue, index) },
            onMenu = { onMenu(track.song) },
            number = index + 1,
            isPlaying = track.song.id == playingId,
            explicit = track.song.explicit,
            duration = formatListeningTime(track.ms),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

private inline fun LazyListScope.itemsIndexedTracks(
    keyPrefix: String,
    tracks: List<TopTrack>,
    crossinline row: @Composable (Int, TopTrack) -> Unit
) = tracks.forEachIndexed { index, track ->
    item(key = "${keyPrefix}_${track.song.id}") { row(index, track) }
}

private fun LazyListScope.topArtists(artists: List<TopArtist>, all: Boolean, onToggle: () -> Unit, onOpen: (TopArtist) -> Unit) {
    if (artists.isEmpty()) return
    item(key = "artists/header") { SectionHeader(title = stringResource(R.string.stats_top_artists)) }
    (if (all) artists else artists.take(TOP_SHOWN)).forEachIndexed { index, artist ->
        item(key = "artist_${artist.key}") {
            RankRow(
                rank = index + 1,
                title = artist.name,
                plays = artist.plays,
                ms = artist.ms,
                artworkUrl = artist.thumbnailUrl,
                round = true,
                onClick = { onOpen(artist) }
            )
        }
    }
    if (artists.size > TOP_SHOWN) item(key = "artists/all") { ShowAll(all = all, onToggle = onToggle, tag = "stats_artists_all") }
}

private fun LazyListScope.topAlbums(albums: List<TopAlbum>, all: Boolean, onToggle: () -> Unit, onOpen: (TopAlbum) -> Unit) {
    if (albums.isEmpty()) return
    item(key = "albums/header") { SectionHeader(title = stringResource(R.string.stats_top_albums)) }
    (if (all) albums else albums.take(TOP_SHOWN)).forEachIndexed { index, album ->
        item(key = "album_${album.key}") {
            RankRow(
                rank = index + 1,
                title = album.title,
                plays = album.plays,
                ms = album.ms,
                artworkUrl = album.thumbnailUrl,
                round = false,
                onClick = { onOpen(album) }
            )
        }
    }
    if (albums.size > TOP_SHOWN) item(key = "albums/all") { ShowAll(all = all, onToggle = onToggle, tag = "stats_albums_all") }
}

/** An artist or an album of a top: its place, cover, name, plays and time. */
@Composable
private fun RankRow(
    rank: Int,
    title: String,
    plays: Int,
    ms: Long,
    artworkUrl: String?,
    round: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = modifier
        .padding(horizontal = 8.dp)
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
) {
    Text(
        text = rank.toString(),
        style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(32.dp)
    )
    Artwork(url = artworkUrl, size = 56.dp, shape = if (round) CircleShape else RoundedCornerShape(12.dp))
    Column(modifier = Modifier.weight(1f)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = pluralStringResource(R.plurals.stats_plays_count, plays, plays),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
    Text(
        text = formatListeningTime(ms),
        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}

@Composable
private fun ShowAll(all: Boolean, onToggle: () -> Unit, tag: String) = Box(
    contentAlignment = Alignment.CenterStart,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp)
) {
    TextButton(onClick = onToggle, modifier = Modifier.testTag(tag)) {
        Text(text = stringResource(if (all) R.string.stats_show_less else R.string.stats_show_all))
    }
}

/** "When you listened" (the days, months or years) and "Time of day" (the 24 hours). */
@Composable
private fun WhenSection(stats: ListeningStats, today: LocalDate) {
    val locale = Locale.getDefault()
    val window = stats.window
    val bars = stats.bars

    SectionHeader(title = stringResource(R.string.stats_when))
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val busiest = stats.busiestBar
        val description = busiest?.let {
            stringResource(R.string.stats_chart_busiest, barTitle(window, it, locale), formatListeningTime(it.ms))
        }.orEmpty()

        StatsBarChart(
            values = bars.map { it.ms },
            labelOf = { index -> barLabel(window, bars[index], index, bars.size, locale) },
            description = description,
            modifier = Modifier.testTag("stats_chart")
        )

        Text(
            text = stringResource(R.string.stats_time_of_day),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        val peak = stats.peakHour
        StatsBarChart(
            values = stats.hours,
            labelOf = { hour -> if (hour % HOUR_LABEL_STEP == 0) "%02d".format(hour) else null },
            description = peak?.let {
                stringResource(R.string.stats_hours_busiest, "%02d:00".format(it), stringResource(dayPartName(stats.favoriteDayPart)))
            }.orEmpty(),
            height = 96.dp,
            modifier = Modifier.testTag("stats_hours")
        )
    }
}

private const val HOUR_LABEL_STEP = 6

@Composable
private fun AllTimeNote() = Text(
    text = stringResource(R.string.stats_all_time_note),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
        .padding(horizontal = 16.dp, vertical = 16.dp)
        .testTag("stats_all_time_note")
)

@Composable
private fun Loading() = Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
        .fillMaxSize()
        .padding(48.dp)
) {
    DelayedLoadingIndicator()
}

/** The name of a part of the day, for words on a card. */
fun dayPartName(part: app.melogold.android.data.stats.DayPart?): Int = when (part) {
    app.melogold.android.data.stats.DayPart.Night -> R.string.stats_daypart_night
    app.melogold.android.data.stats.DayPart.Morning -> R.string.stats_daypart_morning
    app.melogold.android.data.stats.DayPart.Afternoon -> R.string.stats_daypart_afternoon
    app.melogold.android.data.stats.DayPart.Evening, null -> R.string.stats_daypart_evening
}

/**
 * What the arrows are between: "September 2026", "21–27 September" ("28 Sep – 4 Oct" across months, with the year when
 * it is not this one), "2026", or [allTime].
 */
fun statsWindowTitle(window: StatsWindow, today: LocalDate, locale: Locale, allTime: String): String {
    val first = window.firstDay ?: return allTime
    val last = window.endDay?.minusDays(1) ?: return allTime
    val year = if (first.year != today.year || last.year != today.year) " ${last.year}" else ""
    return when (window.period) {
        StatsPeriod.AllTime -> allTime
        StatsPeriod.Year -> first.year.toString()
        StatsPeriod.Month -> "${monthName(first.month, locale)} ${first.year}"
        StatsPeriod.Week -> if (first.month == last.month) {
            "${first.dayOfMonth}–${last.dayOfMonth} ${genitiveMonth(first, locale)}$year"
        } else {
            "${first.dayOfMonth} ${shortMonth(first, locale)} – ${last.dayOfMonth} ${shortMonth(last, locale)}$year"
        }
    }
}

/** The month on its own: "September", capitalized. */
fun monthName(month: Month, locale: Locale): String =
    month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }

/** The month after a day of it: "21 September" (Russian: "сентября"). */
private fun genitiveMonth(date: LocalDate, locale: Locale): String = date.format(DateTimeFormatter.ofPattern("MMMM", locale))

private fun shortMonth(date: LocalDate, locale: Locale): String = date.format(DateTimeFormatter.ofPattern("MMM", locale)).trimEnd('.')

/** The label under a bar: the weekday, every 7th day of a month, the first letter of a month, a year. */
fun barLabel(window: StatsWindow, bar: StatsBar, index: Int, count: Int, locale: Locale): String? = when (window.period) {
    StatsPeriod.Week -> bar.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    StatsPeriod.Month -> if (index % MONTH_LABEL_STEP == 0) bar.date.dayOfMonth.toString() else null
    StatsPeriod.Year -> bar.date.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale)
    StatsPeriod.AllTime -> if (count <= ALL_TIME_LABELS || index % 2 == 0) bar.date.year.toString() else null
}

private const val MONTH_LABEL_STEP = 7
private const val ALL_TIME_LABELS = 6

/** What a bar is: "September 14", "September", "2026". */
fun barTitle(window: StatsWindow, bar: StatsBar, locale: Locale): String = when (window.period) {
    StatsPeriod.Week, StatsPeriod.Month -> bar.date.format(DateTimeFormatter.ofPattern("d MMMM", locale))
    StatsPeriod.Year -> monthName(bar.date.month, locale)
    StatsPeriod.AllTime -> bar.date.year.toString()
}
