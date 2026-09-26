package app.melogold.android.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import app.melogold.android.Dependencies
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsPin
import app.melogold.android.models.LyricsSource
import app.melogold.android.models.serverName
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.core.ui.utils.songBundle
import app.melogold.domain.lyrics.TitleCleaner
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.NextBody
import app.melogold.providers.innertube.requests.lyricsBrowseIdOf
import app.melogold.providers.innertube.requests.lyricsOf
import app.melogold.providers.innertube.requests.timedLyricsOf
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
 * @param startTime where the synced lyrics start, when the user's own version from the server says so
 * @param fixedRef the id of [fixed] at its provider (tasks/0013)
 * @param syncedRef the id of [synced] at its provider
 */
data class LyricsFetchResult(
    val fixed: String?,
    val synced: String?,
    val anyFailure: Boolean,
    val fixedSource: LyricsSource? = null,
    val syncedSource: LyricsSource? = null,
    val startTime: Long? = null,
    /** Taken from the user's own version on the server: chosen, it stays theirs (tasks/0009). */
    val chosen: Boolean = false,
    val fixedRef: String? = null,
    val syncedRef: String? = null
)

/** The lyrics a pin refers to, fetched from their provider (tasks/0013). */
private class PinnedLyrics(val source: LyricsSource, val ref: String, val synced: String?, val plain: String?)

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
 * The Melogold server comes first and last (API §4.10): the user's own version synced from another device wins over
 * every provider, and when no provider has synced lyrics, the server's own or shared version fills the gap.
 *
 * Pinned lyrics ([pin], tasks/0013) come right after the user's own: the account kept them, so every device shows
 * them instead of searching its own. When their provider does not give them, the search goes on as usual.
 *
 * Sides already present in [current] are not fetched again.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
suspend fun fetchLyrics(
    mediaId: String,
    metadata: MediaMetadata,
    durationMs: Long,
    current: Lyrics?,
    pin: LyricsPin? = null
): LyricsFetchResult {
    val rawArtist = metadata.artist?.toString().orEmpty()
    val rawTitle = metadata.title?.toString().orEmpty().let {
        if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
            .substringBeforeLast('.')
            .trim()
        else it
    }
    // The user's own names are asked first (fan uploads are found by them), then YouTube's (tasks/0012)
    val bundle = metadata.extras?.songBundle
    val overridden = bundle?.overridden == true
    val youTubeAlbum = if (overridden) bundle.originalAlbum else metadata.albumTitle?.toString()
    val isSong = youTubeAlbum != null || bundle?.albumId != null
    val clean = TitleCleaner.clean(title = rawTitle, channel = rawArtist.ifBlank { null }, videoType = if (isSong) "song" else null)
    val artist = clean.artist ?: rawArtist
    val title = clean.title.ifBlank { rawTitle }
    val youTubeName = if (overridden) {
        val youTubeTitle = bundle.originalTitle.orEmpty()
        val youTubeArtist = bundle.originalArtist.orEmpty()
        val cleaned = TitleCleaner.clean(
            title = youTubeTitle,
            channel = youTubeArtist.ifBlank { null },
            videoType = if (isSong) "song" else null
        )
        ((cleaned.artist ?: youTubeArtist) to cleaned.title.ifBlank { youTubeTitle }).takeIf { it != (artist to title) }
    } else null
    val duration = durationMs.milliseconds
    val isLocal = mediaId.startsWith(LOCAL_KEY_PREFIX)
    val sync = Dependencies.application.container.sync

    // The user's own version, synced from another device before this one stored the track: its sides count as known
    val own = if (isLocal) null else withContext(Dispatchers.IO) { sync.ownLyricsFromSync(mediaId) }
    val ownSynced = own?.synced != null && current?.synced == null
    val known = if (own == null) current else Lyrics(
        songId = mediaId,
        fixed = current?.fixed ?: own.fixed,
        synced = current?.synced ?: own.synced,
        startTime = if (ownSynced) own.startTime else current?.startTime,
        fixedSource = if (current?.fixed != null) current.fixedSource else own.fixedSource,
        syncedSource = if (current?.synced != null) current.syncedSource else own.syncedSource,
        fixedRef = current?.fixedRef.takeIf { current?.fixed != null },
        syncedRef = current?.syncedRef.takeIf { current?.synced != null }
    )

    var anyFailure = false

    fun <T> Result<T>?.track(): T? {
        this?.exceptionOrNull()?.let { if (it.isNetworkError()) anyFailure = true }
        return this?.getOrNull()
    }

    // What the account pinned, unless the user's own lyrics are here (tasks/0013)
    val pinned = pin?.takeIf { !isLocal && known?.synced == null }?.let { pinnedLyrics(it) { result -> result.track() } }

    // The browse id of YouTube Music's lyrics of this video: asked once, for both sides
    var browseId: String? = null
    var browseIdAsked = false
    suspend fun youTubeMusicBrowseId(): String? {
        if (isLocal) return null
        if (!browseIdAsked) {
            browseIdAsked = true
            browseId = Innertube.lyricsBrowseIdOf(NextBody(videoId = mediaId)).track()
        }
        return browseId
    }

    /** LrcLib's lyrics by a name, with their record id. */
    suspend fun lrcLibFound(artist: String, title: String, synced: Boolean): Pair<String, String?>? =
        LrcLib.bestLyrics(artist = artist, title = title, duration = duration, synced = synced).track()
            ?.let { it.text to it.id?.toString() }

    var fixedSource = known?.fixedSource
    var fixedRef = known?.fixedRef
    val fixed = known?.fixed
        ?: pinned?.plain?.also {
            fixedSource = pinned.source
            fixedRef = pinned.ref
        }
        ?: youTubeMusicBrowseId()?.let { id ->
            Innertube.lyricsOf(id).track()?.also {
                fixedSource = LyricsSource.YouTubeMusic
                fixedRef = id
            }
        }
        ?: (lrcLibFound(artist, title, synced = false)
            ?: youTubeName?.let { (youTubeArtist, youTubeTitle) -> lrcLibFound(youTubeArtist, youTubeTitle, synced = false) })
            ?.let { (text, ref) ->
                fixedSource = LyricsSource.LrcLib
                fixedRef = ref
                text
            }

    // Where the synced lyrics found below came from
    var syncedFrom: LyricsSource? = null
    var syncedFromRef: String? = null

    suspend fun youTubeMusic(): String? = youTubeMusicBrowseId()?.let { id ->
        Innertube.timedLyricsOf(id).track()?.also {
            syncedFrom = LyricsSource.YouTubeMusic
            syncedFromRef = id
        }
    }

    suspend fun lrcLib(): String? = (
        lrcLibFound(artist, title, synced = true)
            // The name as the track has it, when cleaning changed it
            ?: (if (artist != rawArtist || title != rawTitle) lrcLibFound(rawArtist, rawTitle, synced = true) else null)
            // What YouTube calls the track, under the user's own names
            ?: youTubeName?.let { (youTubeArtist, youTubeTitle) -> lrcLibFound(youTubeArtist, youTubeTitle, synced = true) }
        )?.let { (text, ref) ->
        syncedFrom = LyricsSource.LrcLib
        syncedFromRef = ref
        text
    }

    var syncedSource = known?.syncedSource
    var syncedRef = known?.syncedRef
    val synced = known?.synced
        ?: pinned?.synced?.also {
            syncedSource = pinned.source
            syncedRef = pinned.ref
        }
        ?: run {
            val found = if (isSong) youTubeMusic() ?: lrcLib() else lrcLib() ?: youTubeMusic()
            found?.also {
                syncedSource = syncedFrom
                syncedRef = syncedFromRef
            } ?: KuGou.lyricsWithRef(artist = artist, title = title, duration = durationMs / 1000).track()?.let { kugou ->
                syncedSource = LyricsSource.KuGou
                syncedRef = kugou.ref
                kugou.lyrics.value
            }
        }

    // No provider has synced lyrics: the user's own on the server, else what other users share
    if (synced == null && !isLocal) sync.serverLyrics(mediaId)?.let { server ->
        val mine = server.mine?.text
        val text = mine ?: server.shared?.text ?: return@let
        val source = { own: String? -> if (mine == null) LyricsSource.Melogold else own.toLyricsSource() }
        text.synced?.takeIf { it.isNotEmpty() } ?: return@let
        return LyricsFetchResult(
            fixed = fixed ?: text.plain,
            synced = text.synced,
            anyFailure = anyFailure,
            fixedSource = if (fixed != null) fixedSource else text.plain?.let { source(text.plainSource) },
            syncedSource = source(text.syncedSource),
            startTime = text.startTimeMs,
            chosen = mine != null
        )
    }

    return LyricsFetchResult(
        fixed = fixed,
        synced = synced,
        anyFailure = anyFailure,
        fixedSource = fixedSource,
        syncedSource = syncedSource,
        startTime = when {
            ownSynced -> own?.startTime
            pinned?.synced != null && synced == pinned.synced -> pin.startTimeMs
            else -> null
        },
        fixedRef = fixedRef.takeIf { fixed != null },
        syncedRef = syncedRef.takeIf { synced != null },
        // A side taken from the user's own version on the server: the row stays their chosen lyrics
        chosen = current?.chosen == true ||
            (own != null && ((fixed != null && fixed == own.fixed) || (synced != null && synced == own.synced)))
    )
}

private fun String?.toLyricsSource() = when (this) {
    "user" -> LyricsSource.User
    "file" -> LyricsSource.File
    "youtube_music" -> LyricsSource.YouTubeMusic
    "lrclib" -> LyricsSource.LrcLib
    "kugou" -> LyricsSource.KuGou
    else -> null
}

/**
 * The lyrics [pin] refers to, from its provider (tasks/0013); null when the provider does not answer or has nothing
 * there. [track] notes a network failure.
 */
private suspend fun pinnedLyrics(pin: LyricsPin, track: (Result<*>?) -> Any?): PinnedLyrics? {
    @Suppress("UNCHECKED_CAST")
    fun <T> Result<T>?.value(): T? = track(this) as T?

    return when (pin.source) {
        LyricsSource.LrcLib.serverName -> pin.ref.toIntOrNull()?.let { LrcLib.byId(it).value() }?.let { record ->
            PinnedLyrics(
                source = LyricsSource.LrcLib,
                ref = pin.ref,
                synced = record.syncedLyrics?.takeIf { it.isNotBlank() },
                plain = record.plainLyrics?.takeIf { it.isNotBlank() }
            )
        }

        LyricsSource.YouTubeMusic.serverName -> {
            val synced = Innertube.timedLyricsOf(pin.ref).value()
            val plain = Innertube.lyricsOf(pin.ref).value()
            if (synced == null && plain == null) null
            else PinnedLyrics(source = LyricsSource.YouTubeMusic, ref = pin.ref, synced = synced, plain = plain)
        }

        LyricsSource.KuGou.serverName -> KuGou.lyricsByRef(pin.ref).value()?.let {
            PinnedLyrics(source = LyricsSource.KuGou, ref = pin.ref, synced = it.value, plain = null)
        }

        else -> null
    }?.takeIf { it.synced != null || it.plain != null }
}
