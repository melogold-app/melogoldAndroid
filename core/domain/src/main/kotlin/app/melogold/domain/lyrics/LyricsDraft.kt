package app.melogold.domain.lyrics

/** A line ends this long after its start when nothing ends it earlier (as in LRC). */
internal const val DEFAULT_LINE_MS = 5_000L

private val whitespace = Regex("\\s+")

/** The words of a line as the editor times them: split on spaces, punctuation stays attached. */
fun splitWords(text: String): List<String> = text.trim().split(whitespace).filter { it.isNotEmpty() }

/**
 * A line being written in the lyrics editor (`docs/spec/lyrics.md`, "Редактор").
 *
 * @param startMs when the line starts in the track; null while it is not marked
 * @param endMs an explicit end, which leaves a gap before the next line; null: the next line ends it
 * @param wordStarts the start of every word of [text] ([splitWords]) in word mode; null: not marked
 * @param backing backing vocals shown under the line, with their parentheses: "(ooh)"
 */
data class DraftLine(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val wordStarts: List<Long?> = emptyList(),
    val side: VocalSide = VocalSide.Start,
    val backing: String? = null,
    val language: String? = null
) {
    val words get() = splitWords(text)

    /** Whether every word has a start (word mode is complete for this line). */
    val wordsTimed get() = words.isNotEmpty() && wordStarts.size == words.size && wordStarts.all { it != null }

    /** The text as the editor shows it: the line, then its backing vocals. */
    val fullText get() = listOfNotNull(text, backing).joinToString(" ")
}

/**
 * The state of the lyrics editor: the lines, the line (and in word mode the word) the next mark
 * times, and whether marks time lines or words. Every operation returns a new draft, so the editor
 * keeps the previous ones for "Undo".
 */
data class LyricsDraft(
    val lines: List<DraftLine>,
    val cursor: Int = 0,
    val wordCursor: Int = 0,
    val timing: LyricsTiming = LyricsTiming.Line,
    val language: String? = null
) {
    /** Whether at least one line is marked, i.e. the draft makes synced lyrics. */
    val hasTiming get() = lines.any { it.startMs != null }

    /** Whether every line is marked. */
    val complete get() = lines.isNotEmpty() && lines.all { it.startMs != null }

    /** The draft as plain text: one line per line, backing vocals at the end of their line. */
    fun toText(): String = lines.joinToString("\n") { it.fullText }

    /**
     * Times the next line (or word) at [positionMs] and moves the cursor on. A line that starts
     * ends the line before it, unless that one has an explicit end ([markEnd]).
     */
    fun mark(positionMs: Long): LyricsDraft {
        val line = lines.getOrNull(cursor) ?: return this
        val position = positionMs.coerceAtLeast(0L)

        if (timing == LyricsTiming.Line || line.words.isEmpty()) {
            val updated = line.copy(startMs = position, wordStarts = emptyList())
            return copy(lines = lines.replaced(cursor, updated).endingPreviousAt(cursor, position))
                .movedTo(cursor + 1)
        }

        val words = line.words
        val starts = List(words.size) { line.wordStarts.getOrNull(it) }.toMutableList()
        val word = wordCursor.coerceIn(0, words.lastIndex)
        starts[word] = position

        val updated = line.copy(
            startMs = if (word == 0) position else line.startMs ?: position,
            wordStarts = starts
        )
        val newLines = lines.replaced(cursor, updated).let {
            if (word == 0) it.endingPreviousAt(cursor, position) else it
        }

        return if (word < words.lastIndex) copy(lines = newLines, wordCursor = word + 1)
        else copy(lines = newLines).movedTo(cursor + 1)
    }

    /**
     * Ends the last marked line at [positionMs]: the time until the next line starts is a gap
     * (an instrumental interlude when it is long enough).
     */
    fun markEnd(positionMs: Long): LyricsDraft {
        val index = (cursor - 1).coerceAtMost(lines.lastIndex)
        val line = lines.getOrNull(index)?.takeIf { it.startMs != null } ?: return this
        val end = positionMs.coerceAtLeast(line.startMs!! + 1)
        return copy(lines = lines.replaced(index, line.copy(endMs = end)))
    }

    /** Moves the start of line [index] (and its words) by [deltaMs]. */
    fun nudge(index: Int, deltaMs: Long): LyricsDraft {
        val line = lines.getOrNull(index) ?: return this
        val start = line.startMs ?: return this
        fun Long.moved() = (this + deltaMs).coerceAtLeast(0L)

        return copy(
            lines = lines.replaced(
                index,
                line.copy(
                    startMs = start.moved(),
                    endMs = line.endMs?.moved(),
                    wordStarts = line.wordStarts.map { it?.moved() }
                )
            )
        )
    }

    /** Every time moved by [deltaMs]: lyrics with a start offset become track times. */
    fun shiftedBy(deltaMs: Long): LyricsDraft {
        if (deltaMs == 0L) return this
        fun Long.moved() = (this + deltaMs).coerceAtLeast(0L)

        return copy(
            lines = lines.map { line ->
                line.copy(
                    startMs = line.startMs?.moved(),
                    endMs = line.endMs?.moved(),
                    wordStarts = line.wordStarts.map { it?.moved() }
                )
            }
        )
    }

    /** Forgets the timing of line [index]. */
    fun clearTiming(index: Int): LyricsDraft {
        val line = lines.getOrNull(index) ?: return this
        return copy(lines = lines.replaced(index, line.copy(startMs = null, endMs = null, wordStarts = emptyList())))
    }

    /** The next mark times line [index] (from its first word). */
    fun movedTo(index: Int) = copy(cursor = index.coerceIn(0, lines.size), wordCursor = 0)

    fun withSide(index: Int, side: VocalSide) = update(index) { it.copy(side = side) }

    fun withBacking(index: Int, backing: String?) = update(index) {
        it.copy(backing = backing?.trim()?.takeIf { text -> text.isNotEmpty() }?.let(::inParentheses))
    }

    fun withLineLanguage(index: Int, language: String?) = update(index) { it.copy(language = language) }

    /**
     * The draft with its text replaced by [text] (one line per line, backing vocals in parentheses
     * at the end): lines whose text did not change keep their timing, side and language.
     */
    fun withText(text: String): LyricsDraft {
        val new = linesOf(text)
        val matches = matchUnchanged(lines.map { it.fullText }, new.map { it.fullText })

        val merged = new.mapIndexed { index, line ->
            matches[index]?.let { old -> lines[old].copy(text = line.text, backing = line.backing) } ?: line
        }
        return copy(lines = merged).movedTo(merged.indexOfFirst { it.startMs == null }.takeIf { it >= 0 } ?: merged.size)
    }

    /**
     * The synced lyrics of the marked lines, sorted by time, or null when no line is marked.
     * A line without an explicit end lasts until the next one starts (the last one [DEFAULT_LINE_MS]).
     */
    fun toSyncedLyrics(): SyncedLyrics? {
        val timed = lines.filter { it.startMs != null }.sortedBy { it.startMs }
        if (timed.isEmpty()) return null

        val duet = timed.any { it.side == VocalSide.End }
        val wordTimed = timing == LyricsTiming.Word && timed.all { it.wordsTimed }

        val synced = timed.mapIndexed { index, line ->
            val start = line.startMs!!
            val next = timed.getOrNull(index + 1)?.startMs
            val end = (line.endMs ?: next ?: (start + DEFAULT_LINE_MS)).coerceAtLeast(start + 1)

            SyncedLine(
                startMs = start,
                endMs = end,
                text = line.text,
                words = if (wordTimed) wordsOf(line, end) else emptyList(),
                agent = if (duet) agentOf(line.side) else null,
                side = line.side,
                language = line.language,
                background = line.backing?.let { text ->
                    BackingVocals(startMs = start, endMs = end, words = listOf(SyncedWord(start, end, text)))
                }
            )
        }

        return SyncedLyrics(
            lines = synced,
            timing = if (wordTimed) LyricsTiming.Word else LyricsTiming.Line,
            agents = if (duet) listOf(
                LyricsAgent(id = agentOf(VocalSide.Start), side = VocalSide.Start),
                LyricsAgent(id = agentOf(VocalSide.End), side = VocalSide.End)
            ) else emptyList(),
            language = language
        )
    }

    private fun update(index: Int, transform: (DraftLine) -> DraftLine): LyricsDraft {
        val line = lines.getOrNull(index) ?: return this
        return copy(lines = lines.replaced(index, transform(line)))
    }

    companion object {
        /** A draft of [text]: one line per non-empty line, "(…)" at the end of a line is backing vocals. */
        fun fromText(text: String, language: String? = null) = LyricsDraft(lines = linesOf(text), language = language)

        /** A draft of existing synced lyrics, to edit them. */
        fun from(lyrics: SyncedLyrics) = LyricsDraft(
            lines = lyrics.lines.map { line ->
                DraftLine(
                    text = line.text,
                    startMs = line.startMs,
                    endMs = line.endMs,
                    wordStarts = if (line.words.isNotEmpty() && line.words.size == splitWords(line.text).size) {
                        line.words.map { it.startMs }
                    } else emptyList(),
                    side = line.side,
                    backing = line.background?.text?.takeIf { it.isNotBlank() }?.let(::inParentheses),
                    language = line.language
                )
            }.withExplicitEndsOnlyForGaps(),
            cursor = lyrics.lines.size,
            timing = lyrics.timing,
            language = lyrics.language
        )
    }
}

private fun agentOf(side: VocalSide) = if (side == VocalSide.Start) "v1" else "v2"

private fun inParentheses(text: String) = if (text.startsWith("(") && text.endsWith(")")) text else "($text)"

private val backingSuffix = Regex("""^(.*\S)\s+(\([^()]*\))\s*$""")

private fun linesOf(text: String) = text.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        backingSuffix.matchEntire(line)
            ?.let { match -> DraftLine(text = match.groupValues[1], backing = match.groupValues[2]) }
            ?: DraftLine(text = line)
    }

/** The words of [line], each lasting until the next one starts; the last one until [end]. */
private fun wordsOf(line: DraftLine, end: Long): List<SyncedWord> {
    val words = line.words
    val starts = line.wordStarts.map { it!! }
    return words.mapIndexed { index, word ->
        val start = starts[index]
        val wordEnd = (starts.getOrNull(index + 1) ?: end).coerceAtLeast(start + 1)
        SyncedWord(startMs = start, endMs = wordEnd, text = if (index < words.lastIndex) "$word " else word)
    }
}

private fun <T> List<T>.replaced(index: Int, value: T) = toMutableList().also { it[index] = value }

/** The last marked line before [index] ends at [position], unless it ends on its own already. */
private fun List<DraftLine>.endingPreviousAt(index: Int, position: Long): List<DraftLine> {
    val previous = (index - 1 downTo 0).firstOrNull { this[it].startMs != null } ?: return this
    val line = this[previous]
    val end = line.endMs
    return if (end != null && end <= position) this else replaced(previous, line.copy(endMs = null))
}

/** Explicit ends are kept only where they leave a gap: elsewhere the next line ends a line. */
private fun List<DraftLine>.withExplicitEndsOnlyForGaps() = mapIndexed { index, line ->
    val next = getOrNull(index + 1)?.startMs
    if (line.endMs != null && next != null && line.endMs >= next) line.copy(endMs = null) else line
}

/**
 * For each line of [new], the index of the same line in [old] if it is part of the longest common
 * subsequence of the two, so unchanged lines keep their timing when the text is edited.
 */
private fun matchUnchanged(old: List<String>, new: List<String>): Map<Int, Int> {
    val lengths = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
        lengths[i][j] = if (old[i] == new[j]) lengths[i + 1][j + 1] + 1
        else maxOf(lengths[i + 1][j], lengths[i][j + 1])
    }

    val matches = mutableMapOf<Int, Int>()
    var i = 0
    var j = 0
    while (i < old.size && j < new.size) when {
        old[i] == new[j] -> matches[j++] = i++
        lengths[i + 1][j] >= lengths[i][j + 1] -> i++
        else -> j++
    }
    return matches
}
