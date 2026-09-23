package app.melogold.android.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import app.melogold.android.models.Lyrics
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.NextBody
import app.melogold.providers.innertube.requests.lyrics
import app.melogold.providers.kugou.KuGou
import app.melogold.providers.lrclib.LrcLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Result of [fetchLyrics].
 *
 * @param fixed the plain lyrics, or null if no provider had them
 * @param synced the LRC lyrics, or null if no provider had them
 * @param anyFailure whether at least one provider failed because of a network error (as opposed
 * to answering "nothing found"); callers can use this to avoid caching an empty result
 */
data class LyricsFetchResult(
    val fixed: String?,
    val synced: String?,
    val anyFailure: Boolean
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
 * The lyrics provider chain, shared by the classic lyrics overlay and the new player.
 *
 * Plain lyrics: YouTube Music, then LrcLib (plain).
 * Synced lyrics: LrcLib, then LrcLib with the title before "(", then KuGou.
 *
 * Sides already present in [current] are not fetched again.
 */
suspend fun fetchLyrics(
    mediaId: String,
    metadata: MediaMetadata,
    durationMs: Long,
    current: Lyrics?
): LyricsFetchResult {
    val album = metadata.albumTitle?.toString()
    val artist = metadata.artist?.toString().orEmpty()
    val title = metadata.title?.toString().orEmpty().let {
        if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
            .substringBeforeLast('.')
            .trim()
        else it
    }

    var anyFailure = false

    fun <T> Result<T>?.track(): T? {
        this?.exceptionOrNull()?.let { if (it.isNetworkError()) anyFailure = true }
        return this?.getOrNull()
    }

    val fixed = current?.fixed ?: Innertube
        .lyrics(NextBody(videoId = mediaId))
        .track()
        ?: LrcLib.bestLyrics(
            artist = artist,
            title = title,
            duration = durationMs.milliseconds,
            album = album,
            synced = false
        )?.map { it?.text }.track()

    val synced = current?.synced ?: LrcLib.bestLyrics(
        artist = artist,
        title = title,
        duration = durationMs.milliseconds,
        album = album
    )?.map { it?.text }.track() ?: LrcLib.bestLyrics(
        artist = artist,
        title = title.split("(")[0].trim(),
        duration = durationMs.milliseconds,
        album = album
    )?.map { it?.text }.track() ?: KuGou.lyrics(
        artist = artist,
        title = title,
        duration = durationMs / 1000
    )?.map { it?.value }.track()

    return LyricsFetchResult(
        fixed = fixed,
        synced = synced,
        anyFailure = anyFailure
    )
}
