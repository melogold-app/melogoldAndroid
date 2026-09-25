package app.melogold.providers.lrclib.models

import app.melogold.providers.lrclib.LrcParser
import app.melogold.providers.lrclib.toLrcFile
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.time.Duration

@Serializable
data class Track(
    val id: Int,
    val trackName: String,
    val artistName: String,
    val duration: Double,
    val plainLyrics: String?,
    val syncedLyrics: String?
) {
    val lrc by lazy { syncedLyrics?.let { LrcParser.parse(it)?.toLrcFile() } }
}

/**
 * The version of the track whose length is closest to [duration], when it is near it (3 s or 10 %):
 * synced lyrics of a recording of another length (a live take, an edit) run off. Without a length,
 * the one whose title is closest to [title].
 */
internal fun List<Track>.bestMatchingFor(title: String, duration: Duration): Track? {
    val seconds = duration.inWholeSeconds
    if (seconds <= 0) return minByOrNull { abs(it.trackName.length - title.length) }
    val closest = minByOrNull { abs(it.duration - seconds) } ?: return null
    return closest.takeIf { abs(it.duration - seconds) <= maxOf(3.0, seconds * 0.1) }
}
