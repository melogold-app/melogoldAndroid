package app.melogold.android.data.lyrics

import app.melogold.android.Database
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsPin
import app.melogold.android.models.LyricsSource
import app.melogold.android.models.serverName
import app.melogold.android.service.LOCAL_KEY_PREFIX

/** Played this long with lyrics found automatically, a track pins them (tasks/0013). */
const val PIN_AFTER_MS = 30_000L

private const val VIDEO_ID_LENGTH = 11
private val PIN_SOURCES = setOf(LyricsSource.YouTubeMusic, LyricsSource.LrcLib, LyricsSource.KuGou)

/** Whether these lyrics are the ones [pin] refers to (either side). */
fun Lyrics.shows(pin: LyricsPin): Boolean =
    (!synced.isNullOrEmpty() && syncedRef == pin.ref && syncedSource?.serverName == pin.source) ||
        (!fixed.isNullOrEmpty() && fixedRef == pin.ref && fixedSource?.serverName == pin.source)

/**
 * The pin of [lyrics] found automatically: the side that shows (synced, else plain) with its provider and its id
 * there; null for the user's own lyrics or lyrics without a provider id.
 */
internal fun pinOf(lyrics: Lyrics, now: Long): LyricsPin? {
    if (lyrics.isOwn) return null
    val synced = !lyrics.synced.isNullOrEmpty()
    val source = if (synced) lyrics.syncedSource else lyrics.fixedSource.takeIf { !lyrics.fixed.isNullOrEmpty() }
    val ref = if (synced) lyrics.syncedRef else lyrics.fixedRef
    if (source !in PIN_SOURCES || ref.isNullOrBlank()) return null
    return LyricsPin(
        videoId = lyrics.songId,
        source = source?.serverName ?: return null,
        ref = ref,
        startTimeMs = lyrics.startTime?.takeIf { synced && it > 0 },
        updatedAt = now
    )
}

object LyricsPins {
    /**
     * [videoId] played [PIN_AFTER_MS] with lyrics found automatically and not changed: they are pinned, the same on
     * every device of the account. A track that already has a pin keeps it (the first pin is everyone's), and the
     * user's own lyrics are never pinned. Runs on a database thread.
     */
    fun pinPlayed(videoId: String) {
        if (videoId.startsWith(LOCAL_KEY_PREFIX) || videoId.length != VIDEO_ID_LENGTH) return
        if (Database.lyricsPin(videoId) != null) return
        val lyrics = Database.lyricsNow(videoId) ?: return
        pinOf(lyrics, System.currentTimeMillis())?.let(Database::upsert)
    }

    /** The synced lyrics now start [startTime] later: a pin of them takes the shift along. */
    fun shifted(lyrics: Lyrics, startTime: Long) {
        val pin = Database.lyricsPin(lyrics.songId) ?: return
        if (lyrics.isOwn || !lyrics.shows(pin) || startTime < 0) return
        Database.upsert(pin.copy(startTimeMs = startTime, updatedAt = System.currentTimeMillis()))
    }
}
