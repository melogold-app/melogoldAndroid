package app.melogold.android.ui.screens.library.collections

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.melogold.android.Database
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.repo.PendingMutation
import app.melogold.android.data.repo.applyingHistory
import app.melogold.android.data.repo.applyingPlays
import app.melogold.android.data.repo.withPending
import app.melogold.android.models.Song
import app.melogold.android.models.SongWithLastPlayed
import app.melogold.android.models.SongWithPlayTime
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.m3e.ConnectedToggleGroup
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.kit.formatListeningTime
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.shell.AppSnackbar
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.playWithRadio
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val KEEP_WHILE_HIDDEN_MS = 5_000L

enum class HistoryMode { Recent, MostPlayed }

/** The periods of "Most played" (REWRITE §3.2.4); null days: all time. */
enum class HistoryPeriod(val days: Long?, @param:StringRes val label: Int) {
    Week(7, R.string.history_week),
    Month(30, R.string.history_month),
    Year(365, R.string.history_year),
    AllTime(null, R.string.history_all_time)
}

/**
 * History › Recent and Most played, forgetting a track and clearing all; both wait for "Undo"
 * ([PendingMutation]) and the lists hide what they delete meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryModel : ScreenModel() {
    val period = MutableStateFlow(HistoryPeriod.Month)

    val recent: StateFlow<List<SongWithLastPlayed>?> = Database.recentlyPlayed()
        .withPending { applyingHistory(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val mostPlayed: StateFlow<List<SongWithPlayTime>?> = period
        .flatMapLatest { period -> mostPlayedIn(period) }
        .withPending { applyingHistory(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    val playCount: StateFlow<Int> = Database.eventCount()
        .withPending { applyingPlays(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), 0)

    private fun mostPlayedIn(period: HistoryPeriod): Flow<List<SongWithPlayTime>> = Database.mostPlayed(
        since = period.days?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it) } ?: 0L
    )

    /** Forgets every play of [song] so far (likes and playlists stay), with "Undo". */
    fun forget(song: Song, snackbar: AppSnackbar, message: String) = scope.launch {
        val before = System.currentTimeMillis()
        val plays = withContext(Dispatchers.IO) { Database.eventCountOf(song.id) }
        snackbar.undoable(message, PendingMutation.ForgetTrack(song.id, before = before, plays = plays))
    }

    /** Deletes every play so far, with "Undo". */
    fun clear(snackbar: AppSnackbar, message: String) =
        snackbar.undoable(message, PendingMutation.ClearHistory(before = System.currentTimeMillis()))
}

/**
 * History (REWRITE §3.2.4): "Recent · Most played". Recent groups the last plays by day with the
 * time, a tap plays the track with its radio; Most played numbers the tracks of a period by
 * listening time, a tap plays the list from there.
 */
@Route
@Composable
fun HistoryScreen(initialMode: HistoryMode) = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("library/history") { HistoryModel() }
        val binder = LocalPlayerServiceBinder.current
        val menuState = LocalMenuState.current
        val snackbar = LocalAppSnackbar.current
        val nav = LocalMainNav.current
        val (playingId, _) = playingSong(binder)

        var mode by rememberSaveable { mutableStateOf(initialMode) }
        val period by model.period.collectAsState()
        val recent by model.recent.collectAsState()
        val mostPlayed by model.mostPlayed.collectAsState()
        val playCount by model.playCount.collectAsState()

        var menu by remember { mutableStateOf(false) }
        var clearing by rememberSaveable { mutableStateOf(false) }
        val removedMessage = stringResource(R.string.history_removed)
        val clearedMessage = stringResource(R.string.history_cleared)

        fun showMenu(song: Song) = menuState.display {
            NonQueuedMediaItemMenu(
                onDismiss = menuState::hide,
                mediaItem = song.asMediaItem,
                onHideFromDatabase = {
                    menuState.hide()
                    model.forget(song, snackbar, removedMessage)
                }
            )
        }

        CollectionScaffold(
            title = stringResource(R.string.library_history),
            subtitle = null,
            onBack = pop,
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ms_more_vert),
                            contentDescription = stringResource(R.string.more_options)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.history_clear)) },
                            enabled = playCount > 0,
                            onClick = {
                                menu = false
                                clearing = true
                            }
                        )
                    }
                }
            }
        ) { padding ->
            LazyColumn(contentPadding = padding, modifier = Modifier.testTag("history_list")) {
                if (DataPreferences.pauseHistory) item(key = "paused") {
                    PausedBanner(onResume = { DataPreferences.pauseHistory = false })
                }

                item(key = "mode") {
                    ConnectedToggleGroup(
                        options = persistentListOf(HistoryMode.Recent, HistoryMode.MostPlayed),
                        selected = mode,
                        onSelect = { mode = it },
                        label = {
                            stringResource(if (it == HistoryMode.Recent) R.string.history_recent else R.string.history_most_played)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                when (mode) {
                    HistoryMode.Recent -> recentItems(
                        songs = recent,
                        playingId = playingId,
                        onPlay = { song -> binder?.playWithRadio(song.asMediaItem) },
                        onMenu = ::showMenu,
                        onFindMusic = { nav.openSearch() }
                    )

                    HistoryMode.MostPlayed -> {
                        item(key = "periods") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp)
                            ) {
                                HistoryPeriod.entries.forEach { entry ->
                                    FilterChip(
                                        selected = entry == period,
                                        onClick = { model.period.value = entry },
                                        label = { Text(text = stringResource(entry.label)) }
                                    )
                                }
                            }
                        }

                        mostPlayedItems(
                            songs = mostPlayed,
                            playingId = playingId,
                            onPlay = { list, index ->
                                binder?.stopRadio()
                                binder?.player?.forcePlayAtIndex(list.map { it.song.asMediaItem }, index)
                            },
                            onMenu = ::showMenu
                        )
                    }
                }
            }
        }

        if (clearing) AlertDialog(
            onDismissRequest = { clearing = false },
            text = { Text(text = stringResource(R.string.history_clear_prompt, playCount)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearing = false
                        model.clear(snackbar, clearedMessage)
                    }
                ) { Text(text = stringResource(R.string.history_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) { Text(text = stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recentItems(
    songs: List<SongWithLastPlayed>?,
    playingId: String?,
    onPlay: (Song) -> Unit,
    onMenu: (Song) -> Unit,
    onFindMusic: () -> Unit
) {
    when {
        songs == null -> item(key = "loading") { Loading() }
        songs.isEmpty() -> item(key = "empty") {
            EmptyCollection(text = R.string.history_empty, onFindMusic = onFindMusic, modifier = Modifier.padding(top = 32.dp))
        }
        else -> {
            val zone = ZoneId.systemDefault()
            songs.groupBy { Instant.ofEpochMilli(it.lastPlayed).atZone(zone).toLocalDate() }.forEach { (day, group) ->
                item(key = "day_$day") { DayHeader(day) }
                items(items = group, key = { it.song.id }) { (song, lastPlayed) ->
                    TrackRow(
                        title = song.title,
                        subtitle = song.artistsText,
                        artworkUrl = song.thumbnailUrl,
                        onClick = { onPlay(song) },
                        onMenu = { onMenu(song) },
                        isPlaying = song.id == playingId,
                        explicit = song.explicit,
                        duration = timeOfDay(lastPlayed),
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .animateItem()
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.mostPlayedItems(
    songs: List<SongWithPlayTime>?,
    playingId: String?,
    onPlay: (List<SongWithPlayTime>, Int) -> Unit,
    onMenu: (Song) -> Unit
) {
    when {
        songs == null -> item(key = "loading") { Loading() }
        songs.isEmpty() -> item(key = "empty") {
            EmptyCollection(text = R.string.history_period_empty, onFindMusic = null, modifier = Modifier.padding(top = 32.dp))
        }
        else -> itemsIndexed(items = songs, key = { _, it -> "top_${it.song.id}" }) { index, (song, playTime) ->
            TrackRow(
                title = song.title,
                subtitle = listOfNotNull(song.artistsText, formatListeningTime(playTime)).joinToString(" · "),
                artworkUrl = song.thumbnailUrl,
                onClick = { onPlay(songs, index) },
                onMenu = { onMenu(song) },
                number = index + 1,
                showArtwork = false,
                isPlaying = song.id == playingId,
                explicit = song.explicit,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .animateItem()
            )
        }
    }
}

/** "Today", "Yesterday" or "Mon, 21 September". */
@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    val text = when (day) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> remember(day) {
            day.format(DateTimeFormatter.ofPattern("EEE, d MMMM", Locale.getDefault()))
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() }
    )
}

/** "14:02" in the 12 or 24 hour clock of the device. */
@Composable
private fun timeOfDay(millis: Long): String {
    val context = LocalContext.current
    return remember(millis) { DateFormat.getTimeFormat(context).format(Date(millis)) }
}

/** "History is not saved · Turn on" while the history is paused in the settings. */
@Composable
private fun PausedBanner(onResume: () -> Unit) = Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.history_paused),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onResume) { Text(text = stringResource(R.string.history_resume)) }
    }
}

@Composable
private fun Loading() = Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
        .fillMaxSize()
        .padding(48.dp)
) {
    DelayedLoadingIndicator()
}
