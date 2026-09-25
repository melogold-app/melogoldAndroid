package app.melogold.android.ui.screens.library.collections

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.melogold.android.Database
import app.melogold.android.Dependencies
import app.melogold.android.LocalAppContainer
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.data.repo.applyingDownloads
import app.melogold.android.data.repo.withPending
import app.melogold.android.models.DownloadState
import app.melogold.android.models.Song
import app.melogold.android.models.SongWithDownload
import app.melogold.android.preferences.ListSort
import app.melogold.android.preferences.SortPreferences
import app.melogold.android.preferences.toListSort
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.NonQueuedMediaItemMenu
import app.melogold.android.ui.components.menu.downloadStatus
import app.melogold.android.ui.kit.CollectionFilterField
import app.melogold.android.ui.kit.CollectionScaffold
import app.melogold.android.ui.kit.DelayedLoadingIndicator
import app.melogold.android.ui.kit.PlayShuffleButtons
import app.melogold.android.ui.kit.SortChip
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayAtIndex
import app.melogold.android.utils.formatSize
import app.melogold.android.utils.playingSong
import app.melogold.compose.routing.RouteHandler
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val KEEP_WHILE_HIDDEN_MS = 5_000L

/** How "Downloads" orders what is downloaded (REWRITE §3.2.3). */
enum class DownloadSort(@param:StringRes val label: Int) {
    DateDownloaded(R.string.sort_date_downloaded),
    Title(R.string.sort_title),
    Artist(R.string.sort_artist),
    Size(R.string.sort_size)
}

private val DefaultDownloadSort = ListSort(DownloadSort.DateDownloaded, descending = true)

/**
 * Every track with a download; the screen splits them into going, failed and done. Then the tracks the player's
 * cache holds whole (tasks/0001-audio-cache.md): they play without a network too, until newer tracks replace them.
 */
class DownloadsModel : ScreenModel() {
    private val cachedTracks = Dependencies.application.container.cachedTracks

    val tracks: StateFlow<List<SongWithDownload>?> = Database.songsWithDownloads()
        .withPending { applyingDownloads(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), null)

    /** The tracks whole in the cache, most recently played first, with their bytes. */
    val cached: StateFlow<List<Pair<Song, Long>>> = cachedTracks.tracks
        .map { tracks ->
            withContext(Dispatchers.IO) {
                tracks.keys.chunked(SQL_IN_MAX).flatMap { Database.songsNow(it) }.map { it to (tracks[it.id] ?: 0L) }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN_MS), emptyList())

    init {
        cachedTracks.refresh()
    }
}

/** Ids per `IN (…)` query, under SQLite's variable limit. */
private const val SQL_IN_MAX = 900

/**
 * Downloads (REWRITE §3.2.3): what plays without a network. On top what is still downloading, with
 * "Pause · Resume", and what failed, with "Retry all"; then the downloaded tracks, sorted and
 * filtered like the other collections.
 */
@Route
@Composable
fun DownloadsScreen() = RouteHandler {
    GlobalRoutes()

    Content {
        val model = rememberScreenModel("library/downloads") { DownloadsModel() }
        val tracks by model.tracks.collectAsState()
        val cachedAll by model.cached.collectAsState()
        val downloads = LocalAppContainer.current.downloads
        val paused by downloads.paused.collectAsState()
        val binder = LocalPlayerServiceBinder.current
        val menuState = LocalMenuState.current
        val nav = LocalMainNav.current
        val context = LocalContext.current
        val (playingId, _) = playingSong(binder)

        val sort = SortPreferences.downloads.toListSort(DefaultDownloadSort)
        var filtering by rememberSaveable { mutableStateOf(false) }
        var filter by rememberSaveable { mutableStateOf("") }

        val all = tracks
        val active = remember(all) { all.orEmpty().filter { it.download?.state.isGoing } }
        val failed = remember(all) { all.orEmpty().filter { it.download?.state == DownloadState.Failed } }
        val done = remember(all, sort, filter) {
            all.orEmpty()
                .filter { it.download?.state == DownloadState.Completed }
                .sortedAs(sort)
                .filter { it.matches(filter) }
        }
        val doneCount = all.orEmpty().count { it.download?.state == DownloadState.Completed }
        // Whole in the cache and not downloaded: downloaded ones show above
        val cached = remember(cachedAll, all, filter) {
            val downloaded = all.orEmpty().mapTo(HashSet()) { it.song.id }
            cachedAll.filter { (song, _) -> song.id !in downloaded && SongWithDownload(song, null).matches(filter) }
        }
        val bytes = all.orEmpty().sumOf { track ->
            track.download?.takeIf { it.state == DownloadState.Completed }?.let { it.contentLength ?: it.bytesDownloaded } ?: 0L
        }

        val activeTitle = stringResource(R.string.downloads_active, active.size)
        val pauseLabel = stringResource(if (paused) R.string.downloads_resume else R.string.downloads_pause)
        val failedTitle = stringResource(R.string.downloads_failed, failed.size)
        val retryAllLabel = stringResource(R.string.downloads_retry_all)
        val pausedStatus = stringResource(R.string.download_state_paused)

        fun play(list: List<SongWithDownload>, index: Int) {
            binder?.stopRadio()
            binder?.player?.forcePlayAtIndex(list.map { it.song.asMediaItem }, index)
        }

        fun showMenu(track: SongWithDownload) = menuState.display {
            NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = track.song.asMediaItem)
        }

        CollectionScaffold(
            title = stringResource(R.string.library_downloads),
            subtitle = if (doneCount > 0) {
                "${pluralStringResource(R.plurals.library_tracks_count, doneCount, doneCount)} · ${context.formatSize(bytes)}"
            } else null,
            onBack = pop,
            actions = {
                if (doneCount > 0) IconButton(onClick = { filtering = !filtering; if (!filtering) filter = "" }) {
                    Icon(
                        painter = painterResource(R.drawable.ms_search),
                        contentDescription = stringResource(R.string.collection_filter)
                    )
                }
            }
        ) { padding ->
            when {
                all == null -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    DelayedLoadingIndicator()
                }

                all.isEmpty() && cached.isEmpty() -> EmptyCollection(
                    text = R.string.downloads_empty,
                    onFindMusic = { nav.openSearch() },
                    modifier = Modifier.padding(padding)
                )

                else -> LazyColumn(contentPadding = padding, modifier = Modifier.testTag("downloads_list")) {
                    if (filtering) item(key = "filter") {
                        CollectionFilterField(
                            value = filter,
                            onValueChange = { filter = it },
                            onClose = {
                                filtering = false
                                filter = ""
                            }
                        )
                    }

                    if (doneCount > 0) item(key = "actions") {
                        val completed = all.filter { it.download?.state == DownloadState.Completed }.sortedAs(sort)
                        PlayShuffleButtons(
                            onPlay = { play(completed, 0) },
                            onShuffle = { play(completed.shuffled(), 0) },
                            enabled = completed.isNotEmpty()
                        )
                    }

                    if (active.isNotEmpty()) section(
                        key = "active",
                        title = activeTitle,
                        action = pauseLabel,
                        onAction = { if (paused) downloads.resumeAll() else downloads.pauseAll() },
                        tracks = active,
                        status = { download -> if (paused) pausedStatus else downloadStatus(download) },
                        playingId = playingId,
                        onPlay = { play(active, it) },
                        onMenu = ::showMenu
                    )

                    if (failed.isNotEmpty()) section(
                        key = "failed",
                        title = failedTitle,
                        action = retryAllLabel,
                        onAction = { failed.forEach { downloads.retry(it.song.id) } },
                        tracks = failed,
                        status = { downloadStatus(it) },
                        playingId = playingId,
                        onPlay = { play(failed, it) },
                        onMenu = ::showMenu
                    )

                    if (doneCount > 0) item(key = "sort") {
                        SortChip(
                            options = persistentListOf(*DownloadSort.entries.toTypedArray()),
                            selected = sort.field,
                            descending = sort.descending,
                            label = { stringResource(it.label) },
                            onSelect = { option, down -> SortPreferences.downloads = ListSort(option, down).encode() },
                            startsDescending = { it == DownloadSort.DateDownloaded || it == DownloadSort.Size },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    if (doneCount > 0 && done.isEmpty()) item(key = "no_match") {
                        Text(
                            text = stringResource(R.string.collection_no_match, filter),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        )
                    }

                    itemsIndexed(items = done, key = { _, track -> track.song.id }) { index, track ->
                        TrackRow(
                            title = track.song.title,
                            videoId = track.song.id,
                            subtitle = track.song.artistsText,
                            artworkUrl = track.song.thumbnailUrl,
                            onClick = { play(done, index) },
                            onMenu = { showMenu(track) },
                            isPlaying = track.song.id == playingId,
                            explicit = track.song.explicit,
                            duration = track.song.durationText,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .animateItem()
                        )
                    }

                    if (cached.isNotEmpty()) cachedSection(
                        tracks = cached,
                        playingId = playingId,
                        onPlay = { index -> play(cached.map { SongWithDownload(it.first, null) }, index) },
                        onMenu = { song -> showMenu(SongWithDownload(song, null)) }
                    )
                }
            }
        }
    }
}

/**
 * The tracks whole in the player's cache (tasks/0001-audio-cache.md): "In the cache (N) · size", what that means,
 * then the tracks.
 */
private fun LazyListScope.cachedSection(
    tracks: List<Pair<Song, Long>>,
    playingId: String?,
    onPlay: (Int) -> Unit,
    onMenu: (Song) -> Unit
) {
    item(key = "cached_title") {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.downloads_cached, tracks.size) + " · " +
                    context.formatSize(tracks.sumOf { it.second }),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.downloads_cached_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    itemsIndexed(items = tracks, key = { _, (song, _) -> "cached_${song.id}" }) { index, (song, _) ->
        TrackRow(
            title = song.title,
            videoId = song.id,
            subtitle = song.artistsText,
            artworkUrl = song.thumbnailUrl,
            onClick = { onPlay(index) },
            onMenu = { onMenu(song) },
            isPlaying = song.id == playingId,
            explicit = song.explicit,
            duration = song.durationText,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .animateItem()
        )
    }
}

/** A block above the downloaded tracks: its title with one action, then its tracks with their status. */
@Suppress("LongParameterList")
private fun LazyListScope.section(
    key: String,
    title: String,
    action: String,
    onAction: () -> Unit,
    tracks: List<SongWithDownload>,
    status: @Composable (app.melogold.android.models.TrackDownload) -> String,
    playingId: String?,
    onPlay: (Int) -> Unit,
    onMenu: (SongWithDownload) -> Unit
) {
    item(key = "${key}_title") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAction) { Text(text = action) }
        }
    }

    items(items = tracks, key = { "${key}_${it.song.id}" }) { track ->
        val index = tracks.indexOf(track)
        TrackRow(
            title = track.song.title,
            videoId = track.song.id,
            subtitle = track.download?.let { status(it) } ?: track.song.artistsText,
            artworkUrl = track.song.thumbnailUrl,
            onClick = { onPlay(index) },
            onMenu = { onMenu(track) },
            isPlaying = track.song.id == playingId,
            explicit = track.song.explicit,
            duration = track.song.durationText,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .animateItem()
        )
    }
}

private val DownloadState?.isGoing
    get() = this == DownloadState.Queued || this == DownloadState.Waiting ||
        this == DownloadState.Downloading || this == DownloadState.Paused

private fun List<SongWithDownload>.sortedAs(sort: ListSort<DownloadSort>): List<SongWithDownload> {
    val ascending = when (sort.field) {
        DownloadSort.DateDownloaded -> sortedBy { it.download?.completedAt ?: 0L }
        DownloadSort.Title -> sortedBy { it.song.title.lowercase() }
        DownloadSort.Artist -> sortedBy { it.song.artistsText.orEmpty().lowercase() }
        DownloadSort.Size -> sortedBy { it.download?.contentLength ?: 0L }
    }
    return if (sort.descending) ascending.asReversed() else ascending
}

private fun SongWithDownload.matches(filter: String): Boolean {
    val query = filter.trim()
    return query.isEmpty() || song.title.contains(query, ignoreCase = true) ||
        song.artistsText.orEmpty().contains(query, ignoreCase = true)
}
