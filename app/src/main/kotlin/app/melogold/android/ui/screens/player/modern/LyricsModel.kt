package app.melogold.android.ui.screens.player.modern

import androidx.compose.runtime.Immutable
import app.melogold.android.models.LyricsSource
import app.melogold.domain.lyrics.SyncedLine
import app.melogold.domain.lyrics.SyncedLyrics
import app.melogold.domain.lyrics.VocalSide
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** A gap of at least this length before or between sung lines is shown as an instrumental interlude. */
internal const val INTERLUDE_MIN_GAP_MS = 4_000L

private val noteCharacters = charArrayOf('♪', '♫', '♬', '♩', '…', '.')

/**
 * One row of the synced lyrics view: a sung line or an instrumental interlude (three dots).
 */
@Immutable
sealed interface LyricRow {
    val startMs: Long
    val endMs: Long

    @Immutable
    data class Sung(val line: SyncedLine) : LyricRow {
        override val startMs get() = line.startMs
        override val endMs get() = line.endMs
    }

    /** @param side the side of the line after the gap, where the eye goes next */
    @Immutable
    data class Interlude(
        override val startMs: Long,
        override val endMs: Long,
        val side: VocalSide = VocalSide.Start
    ) : LyricRow
}

/** What the lyrics area of the player shows. */
@Immutable
sealed interface LyricsContent {
    /** Nothing is known yet and nothing is being fetched. */
    data object Unknown : LyricsContent

    data object Loading : LyricsContent

    /**
     * @param startTimeMs where the lyrics start in the track (set by the user for tracks with an intro
     * the lyrics don't know about)
     */
    data class Synced(
        val lyrics: SyncedLyrics,
        val rows: ImmutableList<LyricRow>,
        val startTimeMs: Long,
        val source: LyricsSource? = null
    ) : LyricsContent

    data class Plain(
        val text: String,
        val source: LyricsSource? = null
    ) : LyricsContent

    /** The providers were asked and have nothing. */
    data object NotFound : LyricsContent

    /** Fetching failed because of a network error; nothing was cached. */
    data object Failed : LyricsContent
}

private fun String.isFiller() = isBlank() || trim().all { it in noteCharacters || it.isWhitespace() }

/**
 * The rows of [lyrics]: the sung lines, with an interlude before the first line and in every gap of
 * at least [INTERLUDE_MIN_GAP_MS]. Filler lines ("♪", "…") become interludes (or vanish when short).
 */
fun buildLyricRows(lyrics: SyncedLyrics): ImmutableList<LyricRow> {
    val sung = lyrics.lines.filterNot { it.text.isFiller() }.sortedBy { it.startMs }
    if (sung.isEmpty()) return emptyList<LyricRow>().toImmutableList()

    val rows = mutableListOf<LyricRow>()
    if (sung.first().startMs >= INTERLUDE_MIN_GAP_MS) {
        rows += LyricRow.Interlude(0L, sung.first().startMs, sung.first().side)
    }

    sung.forEachIndexed { index, line ->
        rows += LyricRow.Sung(line)
        val next = sung.getOrNull(index + 1) ?: return@forEachIndexed
        // A filler line in between ends the sung one where it starts
        val gapStart = lyrics.lines
            .firstOrNull { it.startMs in line.startMs + 1..<next.startMs && it.text.isFiller() }
            ?.startMs
            ?.coerceAtMost(line.endMs)
            ?: line.endMs
        if (next.startMs - gapStart >= INTERLUDE_MIN_GAP_MS) {
            rows += LyricRow.Interlude(gapStart, next.startMs, next.side)
        }
    }

    return rows.toImmutableList()
}

/**
 * The index of the last row with `startMs <= positionMs`, or -1 when playback is before the first
 * row. The list must be sorted by [LyricRow.startMs].
 */
fun List<LyricRow>.activeIndexAt(positionMs: Long): Int {
    var low = 0
    var high = size - 1
    var result = -1

    while (low <= high) {
        val mid = (low + high) ushr 1
        if (this[mid].startMs <= positionMs) {
            result = mid
            low = mid + 1
        } else high = mid - 1
    }

    return result
}
