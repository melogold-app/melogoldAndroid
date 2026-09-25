package app.melogold.domain.lyrics

/**
 * How finely lyrics are timed: whole lines, or every word (syllable) of a line.
 */
enum class LyricsTiming { Line, Word }

/**
 * Where a voice sits in a duet (Apple Music style): the first singer at the start edge, the
 * second at the end edge. Mirrored in right-to-left languages.
 */
enum class VocalSide { Start, End }

/**
 * A singer or group of a duet, `ttm:agent` in TTML. [side] follows the order singers are declared
 * or first heard in: the first one at the start, the second at the end, then alternating.
 */
data class LyricsAgent(
    val id: String,
    val side: VocalSide,
    val name: String? = null
)

/**
 * A timed word or syllable. [text] keeps the space that follows it, so joining the words of a line
 * gives the line.
 */
data class SyncedWord(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Backing vocals of a line (`ttm:role="x-bg"`): shown smaller, under the main line.
 */
data class BackingVocals(
    val startMs: Long,
    val endMs: Long,
    val words: List<SyncedWord>
) {
    val text get() = words.joinToString("") { it.text }.trim()
}

/**
 * One line of synced lyrics.
 *
 * @param words the timed words; empty when only the line is timed
 * @param agent the singer id ([SyncedLyrics.agents]); null when the lyrics don't say
 * @param language BCP 47 tag when it differs from, or refines, [SyncedLyrics.language]
 */
data class SyncedLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<SyncedWord> = emptyList(),
    val agent: String? = null,
    val side: VocalSide = VocalSide.Start,
    val language: String? = null,
    val background: BackingVocals? = null,
    val translation: String? = null,
    val transliteration: String? = null
)

/**
 * Time-synced lyrics in Melogold's model: lines, optional word timing, duet sides, backing vocals,
 * languages, translations. Read from LRC, enhanced LRC or TTML ([LyricsFormats]), written to TTML
 * or LRC. The rules are shared with the server and the desktop clients (`docs/spec/lyrics.md`).
 */
data class SyncedLyrics(
    val lines: List<SyncedLine>,
    val timing: LyricsTiming,
    val agents: List<LyricsAgent> = emptyList(),
    val language: String? = null
) {
    val isDuet get() = lines.any { it.side == VocalSide.End }
}

/**
 * Sides by the order singers appear in: first at the start, second at the end, then alternating.
 */
internal fun assignSides(agentIds: List<String>): List<LyricsAgent> = agentIds
    .distinct()
    .mapIndexed { index, id -> LyricsAgent(id = id, side = if (index % 2 == 0) VocalSide.Start else VocalSide.End) }
