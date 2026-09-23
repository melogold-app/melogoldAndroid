package app.melogold.android.ui.screens.player.modern

import androidx.compose.runtime.Immutable
import app.melogold.providers.lrclib.LrcParser
import app.melogold.providers.lrclib.toLrcFile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** A gap of at least this length between two sung lines is shown as an instrumental interlude. */
internal const val INTERLUDE_MIN_GAP_MS = 4_000L

/** How long the last line stays "active" when nothing follows it. */
private const val LAST_LINE_DURATION_MS = 5_000L

private val noteCharacters = charArrayOf('♪', '♫', '♬', '♩')

/**
 * One row of time-synced lyrics.
 *
 * @param startMs when the line starts, in LRC time
 * @param endMs when the next row starts (or [startMs] + 5 s for the last row)
 * @param isInterlude whether this row is an instrumental gap (shown as three dots, [text] is empty)
 */
@Immutable
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isInterlude: Boolean
)

/** What the lyrics area of the player shows. */
@Immutable
sealed interface LyricsContent {
    /** Nothing is known yet and nothing is being fetched. */
    data object Unknown : LyricsContent

    data object Loading : LyricsContent

    data class Synced(
        val lines: ImmutableList<LyricLine>,
        val offsetMs: Long,
        val startTimeMs: Long
    ) : LyricsContent

    data class Plain(val text: String) : LyricsContent

    /** The providers were asked and have nothing. */
    data object NotFound : LyricsContent

    /** Fetching failed because of a network error; nothing was cached. */
    data object Failed : LyricsContent
}

private fun String.isFiller() = isBlank() || trim().all { it in noteCharacters || it.isWhitespace() }

/**
 * Parses [raw] LRC into rows, adding interlude rows for long instrumental gaps.
 *
 * Returns null when [raw] has no sung line at all (which includes invalid LRC).
 * Lines with several timestamps (`[a][b]text`) are not supported by [LrcParser].
 *
 * @return the rows and the file's `[offset:]` in milliseconds
 */
fun buildLyricLines(raw: String?): Pair<ImmutableList<LyricLine>, Long>? {
    if (raw.isNullOrBlank()) return null

    val file = LrcParser.parse(raw)?.toLrcFile() ?: return null
    // The map keeps insertion order, and toLrcFile seeds it with 0 -> ""
    val entries = file.lines.entries
        .sortedBy { it.key }
        .map { it.key to it.value.trim() }

    val firstSung = entries.firstOrNull { !it.second.isFiller() } ?: return null

    val rows = mutableListOf<Pair<Long, String?>>() // null text = interlude

    if (firstSung.first >= INTERLUDE_MIN_GAP_MS) rows += 0L to null

    entries.forEachIndexed { index, entry ->
        val start = entry.first
        val text = entry.second
        if (start < firstSung.first) return@forEachIndexed

        if (!text.isFiller()) {
            rows += start to text
            return@forEachIndexed
        }

        val nextSung = entries
            .subList(index + 1, entries.size)
            .firstOrNull { !it.second.isFiller() }
            ?: return@forEachIndexed

        val previousIsInterlude = rows.lastOrNull()?.let { it.second == null } == true
        if (!previousIsInterlude && nextSung.first - start >= INTERLUDE_MIN_GAP_MS) rows += start to null
    }

    val lastEntryStart = entries.last().first

    val lines = rows.mapIndexed { index, row ->
        val start = row.first
        val text = row.second
        val end = rows.getOrNull(index + 1)?.first
            ?: lastEntryStart.takeIf { it > start }
            ?: (start + LAST_LINE_DURATION_MS)

        LyricLine(
            startMs = start,
            endMs = end,
            text = text.orEmpty(),
            isInterlude = text == null
        )
    }.toImmutableList()

    return lines to (file.offset?.inWholeMilliseconds ?: 0L)
}

/**
 * The index of the last line with `startMs <= positionMs`, or -1 when playback is before the first
 * line. The list must be sorted by [LyricLine.startMs].
 */
fun List<LyricLine>.activeIndexAt(positionMs: Long): Int {
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
