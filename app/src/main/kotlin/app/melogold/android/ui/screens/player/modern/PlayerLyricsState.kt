package app.melogold.android.ui.screens.player.modern

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.models.Lyrics
import app.melogold.android.transaction
import app.melogold.android.ui.screens.player.awaitDuration
import app.melogold.android.ui.screens.player.fetchLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val TAG = "PlayerLyrics"

/**
 * The lyrics of one song as seen by the new player.
 *
 * [raw] is the cached Room row (always observed, no network), [content] is what the lyrics area
 * shows.
 */
@Stable
class PlayerLyricsState internal constructor(val mediaId: String) {
    var raw by mutableStateOf<Lyrics?>(null)
        internal set

    var content by mutableStateOf<LyricsContent>(LyricsContent.Unknown)
        internal set

    internal var retryKey by mutableIntStateOf(0)
        private set

    /** Fetches again after [LyricsContent.Failed]. */
    fun retry() {
        retryKey++
    }
}

/**
 * Observes the cached lyrics of [mediaItem] and fetches the missing ones while [fetchEnabled].
 *
 * Unlike the classic overlay, a fetch that failed because of the network is not cached as "no
 * lyrics": it shows [LyricsContent.Failed] instead, and [PlayerLyricsState.retry] tries again.
 */
@Composable
fun rememberPlayerLyrics(
    mediaItem: MediaItem,
    fetchEnabled: Boolean,
    preferSynced: Boolean
): PlayerLyricsState {
    val binder = LocalPlayerServiceBinder.current
    val mediaId = mediaItem.mediaId
    val state = remember(mediaId) { PlayerLyricsState(mediaId) }
    val currentMediaItem by rememberUpdatedState(mediaItem)

    LaunchedEffect(mediaId, fetchEnabled, preferSynced, state.retryKey) {
        runCatching {
            withContext(Dispatchers.IO) {
                Database
                    .lyrics(mediaId)
                    .distinctUntilChanged()
                    .collect { row ->
                        state.raw = row

                        if (fetchEnabled && (row?.fixed == null || row.synced == null)) {
                            state.content = LyricsContent.Loading

                            val item = currentMediaItem
                            val duration = awaitDuration {
                                binder?.player?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                                    ?.duration ?: C.TIME_UNSET
                            }
                            val result = fetchLyrics(
                                mediaId = mediaId,
                                metadata = item.mediaMetadata,
                                durationMs = duration,
                                current = row
                            )

                            // A side that was missing and could not be fetched because of the
                            // network is not cached as "no lyrics": show what we have instead
                            val fixedStillMissing = row?.fixed == null && result.fixed == null
                            val syncedStillMissing = row?.synced == null && result.synced == null

                            if (result.anyFailure && (fixedStillMissing || syncedStillMissing)) {
                                val partial = Lyrics(
                                    songId = mediaId,
                                    fixed = result.fixed,
                                    synced = result.synced,
                                    startTime = row?.startTime
                                ).toContent(preferSynced = preferSynced, fetchEnabled = false)

                                state.content = when (partial) {
                                    is LyricsContent.Synced, is LyricsContent.Plain -> partial
                                    else -> LyricsContent.Failed
                                }
                                return@collect
                            }

                            transaction {
                                runCatching {
                                    Database.insert(item)
                                    Database.upsert(
                                        Lyrics(
                                            songId = mediaId,
                                            fixed = result.fixed.orEmpty(),
                                            synced = result.synced.orEmpty(),
                                            startTime = row?.startTime
                                        )
                                    )
                                }.onFailure {
                                    Log.e(TAG, "Could not save the lyrics of $mediaId", it)
                                    state.content = LyricsContent.Failed
                                }
                            }
                        } else state.content = withContext(Dispatchers.Default) {
                            row.toContent(preferSynced = preferSynced, fetchEnabled = fetchEnabled)
                        }
                    }
            }
        }.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            Log.e(TAG, "Could not load the lyrics of $mediaId", it)
            state.content = LyricsContent.Failed
        }
    }

    return state
}

private fun Lyrics?.toContent(preferSynced: Boolean, fetchEnabled: Boolean): LyricsContent {
    val rawFixed = this?.fixed
    val rawSynced = this?.synced
    val syncedLines = buildLyricLines(rawSynced)

    return when {
        preferSynced && syncedLines != null -> LyricsContent.Synced(
            lines = syncedLines.first,
            offsetMs = syncedLines.second,
            startTimeMs = this?.startTime ?: 0L
        )

        rawFixed != null && rawFixed.isNotBlank() -> LyricsContent.Plain(rawFixed)

        syncedLines != null -> LyricsContent.Plain(
            syncedLines.first
                .filterNot { it.isInterlude }
                .joinToString(separator = "\n") { it.text }
        )

        rawFixed != null && rawSynced != null -> LyricsContent.NotFound

        fetchEnabled -> LyricsContent.Loading

        else -> LyricsContent.Unknown
    }
}
