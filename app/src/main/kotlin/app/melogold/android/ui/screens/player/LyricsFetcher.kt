package app.melogold.android.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsSource
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.core.ui.utils.songBundle
import app.melogold.domain.lyrics.TitleCleaner
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.NextBody
import app.melogold.providers.innertube.requests.lyrics
import app.melogold.providers.innertube.requests.timedLyrics
import app.melogold.providers.kugou.KuGou
import app.melogold.providers.lrclib.LrcLib
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Result of [fetchLyrics].
 *
 * @param fixed the plain lyrics, or null if no provider had them
 * @param synced the LRC lyrics, or null if no provider had them
 * @param anyFailure whether at least one provider failed because of a network error (as opposed
 * to answering "nothing found"); callers can use this to avoid caching an empty result
 * @param fixedSource where [fixed] came from
 * @param syncedSource where [synced] came from
 */
data class LyricsFetchResult(
    val fixed: String?,
    val synced: String?,
    val anyFailure: Boolean,
    val fixedSource: LyricsSource? = null,
    val syncedSource: LyricsSource? = null
)

/**
 * Waits until [provider] reports a known duration (the player reports [C.TIME_UNSET] while the
 * media item is still loading). The provider is always read on the main thread.
 */
suspend fun awaitDuration(provider: () -> Long): Long {
    var duration = withContext(Dispatchers.Main) { provider() }

    while (duration == C.TIME_UNSET) {
        delay(100.milliseconds)
        duration = withContext(Dispatchers.Main) { provider() }
    }

    return duration
}

private fun Throwable.isNetworkError(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is IOException || current is UnresolvedAddressException) return true
        current = current.cause
        depth++
    }
    return false
}

/**
 * The lyrics provider chain of the player (REWRITE §4.10.8). What the track is called goes through
 * [TitleCleaner] first: YouTube titles ("Кино - Группа крови (Official Video)", a channel for the
 * artist) find nothing on LrcLib as they are.
 *
 * Synced lyrics, preferred: for a song (it has an album) YouTube Music's own timed lyrics of this
 * very track, then LrcLib by the cleaned name and the track's length, then KuGou; for a video or an
 * upload LrcLib first (a video may be timed differently from the song YouTube Music knows).
 * Plain lyrics: YouTube Music, then LrcLib.
 *
 * Sides already present in [current] are not fetched again.
 */
@Suppress("CyclomaticComplexMethod")
suspend fun fetchLyrics(
    mediaId: String,
    metadata: MediaMetadata,
    durationMs: Long,
    current: Lyrics?
): LyricsFetchResult {
    val rawArtist = metadata.artist?.toString().orEmpty()
    val rawTitle = metadata.title?.toString().orEmpty().let {
        if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
            .substringBeforeLast('.')
            .trim()
        else it
    }
    val isSong = metadata.albumTitle != null || metadata.extras?.songBundle?.albumId != null
    val clean = TitleCleaner.clean(title = rawTitle, channel = rawArtist.ifBlank { null }, videoType = if (isSong) "song" else null)
    val artist = clean.artist ?: rawArtist
    val title = clean.title.ifBlank { rawTitle }
    val duration = durationMs.milliseconds
    val isLocal = mediaId.startsWith(LOCAL_KEY_PREFIX)

    var anyFailure = false

    fun <T> Result<T>?.track(): T? {
        this?.exceptionOrNull()?.let { if (it.isNetworkError()) anyFailure = true }
        return this?.getOrNull()
    }

    var fixedSource = current?.fixedSource
    val fixed = current?.fixed
        ?: (if (isLocal) null else Innertube.lyrics(NextBody(videoId = mediaId)).track())
            ?.also { fixedSource = LyricsSource.YouTubeMusic }
        ?: LrcLib.bestLyrics(artist = artist, title = title, duration = duration, synced = false)
            ?.map { it?.text }.track()?.also { fixedSource = LyricsSource.LrcLib }

    // Where the synced lyrics found below came from
    var syncedFrom: LyricsSource? = null

    suspend fun youTubeMusic() = if (isLocal) null
    else Innertube.timedLyrics(NextBody(videoId = mediaId)).track()?.also { syncedFrom = LyricsSource.YouTubeMusic }

    suspend fun lrcLib() = LrcLib.bestLyrics(artist = artist, title = title, duration = duration)
        ?.map { it?.text }.track()
        // The name as the track has it, when cleaning changed it
        ?: (if (artist != rawArtist || title != rawTitle) {
            LrcLib.bestLyrics(artist = rawArtist, title = rawTitle, duration = duration)?.map { it?.text }.track()
        } else null)

    var syncedSource = current?.syncedSource
    val synced = current?.synced ?: run {
        val found = if (isSong) {
            youTubeMusic() ?: lrcLib()?.also { syncedFrom = LyricsSource.LrcLib }
        } else {
            lrcLib()?.also { syncedFrom = LyricsSource.LrcLib } ?: youTubeMusic()
        }
        found?.also { syncedSource = syncedFrom }
            ?: KuGou.lyrics(artist = artist, title = title, duration = durationMs / 1000)
                ?.map { it?.value }.track()?.also { syncedSource = LyricsSource.KuGou }
    }

    return LyricsFetchResult(
        fixed = fixed,
        synced = synced,
        anyFailure = anyFailure,
        fixedSource = fixedSource,
        syncedSource = syncedSource
    )
}
