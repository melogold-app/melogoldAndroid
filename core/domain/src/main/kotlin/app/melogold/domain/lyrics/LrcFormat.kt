package app.melogold.domain.lyrics

/**
 * LRC and enhanced LRC (A2): `[mm:ss.xx]line`, several timestamps per line, `[offset:±ms]`,
 * `<mm:ss.xx>` word tags and the "walaoke" duet prefixes `M:`, `F:`, `D:`.
 */
object LrcFormat {
    private val lineTagRegex = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metaTagRegex = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")
    private val wordTagRegex = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val walaokeRegex = Regex("""^\s*([MFD]):\s?""")

    private const val LAST_LINE_MS = 5_000L

    /**
     * Whether [text] looks like LRC: at least one line starting with a timestamp.
     */
    fun matches(text: String) = text.lineSequence().any { lineTagRegex.containsMatchIn(it.trim()) }

    /**
     * Parses LRC; null when no line is timed.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun parse(text: String): SyncedLyrics? {
        var offsetMs = 0L
        var agent: String? = null
        val raw = mutableListOf<RawLine>()

        text.lineSequence().forEach { sourceLine ->
            var line = sourceLine.trim()
            if (line.isEmpty()) return@forEach

            metaTagRegex.matchEntire(line)?.let { match ->
                if (match.groupValues[1].equals("offset", ignoreCase = true))
                    offsetMs = match.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                return@forEach
            }

            // All the timestamps in front of the text
            val starts = mutableListOf<Long>()
            while (true) {
                val match = lineTagRegex.find(line) ?: break
                starts += match.toMillis()
                line = line.substring(match.range.last + 1)
            }
            if (starts.isEmpty()) return@forEach

            walaokeRegex.find(line)?.let { match ->
                agent = match.groupValues[1]
                line = line.substring(match.range.last + 1)
            }

            val words = parseWords(line)
            val lineText = if (words.isNullOrEmpty()) line.trim() else words.joinToString("") { it.second }.trim()

            starts.forEach { start ->
                raw += RawLine(
                    startMs = (start - offsetMs).coerceAtLeast(0),
                    text = lineText,
                    words = words?.let { shiftWords(it, start, start - offsetMs) },
                    agent = agent
                )
            }
        }

        val sorted = raw.sortedBy { it.startMs }
        if (sorted.none { it.text.isNotBlank() }) return null

        val agents = assignSides(sorted.mapNotNull { it.agent })
        val sides = agents.associate { it.id to it.side }
        val wordTimed = sorted.any { !it.words.isNullOrEmpty() }

        val lines = sorted.mapIndexedNotNull { index, line ->
            // An empty timed line only marks where the previous one ends
            if (line.text.isBlank()) return@mapIndexedNotNull null

            val next = sorted.getOrNull(index + 1)?.startMs
            val words = line.words.orEmpty().filter { it.text.isNotEmpty() }
            // A trailing tag without text ("…word<00:13.20>") says when the line ends
            val explicitEnd = line.words?.lastOrNull()?.takeIf { it.text.isEmpty() }?.startMs
            val end = when {
                explicitEnd != null && explicitEnd > line.startMs -> explicitEnd
                next != null && next > line.startMs -> next
                else -> line.startMs + LAST_LINE_MS
            }

            SyncedLine(
                startMs = line.startMs,
                endMs = end,
                text = line.text,
                words = words.map { it.copy(endMs = if (it.endMs > it.startMs) it.endMs else end) },
                agent = line.agent,
                side = line.agent?.let { sides[it] } ?: VocalSide.Start
            )
        }

        return SyncedLyrics(
            lines = lines,
            timing = if (wordTimed) LyricsTiming.Word else LyricsTiming.Line,
            agents = agents
        )
    }

    /**
     * Writes LRC: enhanced (with word tags) when the lyrics are word-timed and [enhanced] is on.
     * Duet agents become walaoke prefixes when there are two or three of them.
     */
    fun write(lyrics: SyncedLyrics, enhanced: Boolean = true): String = buildString {
        val walaoke = lyrics.agents.size in 2..3
        val prefixes = lyrics.agents.mapIndexed { index, agent -> agent.id to "MFD"[index] }.toMap()
        var lastAgent: String? = null

        lyrics.lines.forEachIndexed { index, line ->
            append('[').append(timestamp(line.startMs)).append(']')
            if (walaoke && line.agent != null && line.agent != lastAgent) {
                append(prefixes[line.agent]).append(": ")
                lastAgent = line.agent
            }

            if (enhanced && line.words.isNotEmpty()) {
                line.words.forEach { word -> append('<').append(timestamp(word.startMs)).append('>').append(word.text) }
                append('<').append(timestamp(line.words.last().endMs)).append('>')
            } else append(line.text)
            append('\n')

            // A gap before the next line: close this one with an empty line
            val next = lyrics.lines.getOrNull(index + 1)
            if (next == null || next.startMs > line.endMs) append('[').append(timestamp(line.endMs)).append("]\n")
        }
    }

    private fun parseWords(line: String): List<Pair<Long, String>>? {
        val tags = wordTagRegex.findAll(line).toList()
        if (tags.isEmpty()) return null

        return tags.mapIndexed { index, tag ->
            val textEnd = tags.getOrNull(index + 1)?.range?.first ?: line.length
            tag.toMillis() to line.substring(tag.range.last + 1, textEnd)
        }
    }

    /**
     * Words as (start, text) → timed words; a word ends where the next starts, a trailing empty tag
     * ends the last one.
     */
    private fun shiftWords(words: List<Pair<Long, String>>, tagBase: Long, lineStart: Long): List<SyncedWord> {
        val delta = lineStart - tagBase
        return words.mapIndexed { index, (start, text) ->
            val end = words.getOrNull(index + 1)?.first ?: start
            SyncedWord(
                startMs = (start + delta).coerceAtLeast(0),
                endMs = (end + delta).coerceAtLeast(0),
                text = text
            )
        }
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return (minutes * 60 + seconds) * 1000 + millis
    }

    /**
     * `mm:ss.xx`, or `mm:ss.xxx` when hundredths would lose the time (LRC readers accept both).
     */
    internal fun timestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val minutes = total / 60_000
        val seconds = total / 1000 % 60
        val millis = total % 1000
        return if (millis % 10 == 0L) "%02d:%02d.%02d".format(minutes, seconds, millis / 10)
        else "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    private data class RawLine(
        val startMs: Long,
        val text: String,
        val words: List<SyncedWord>?,
        val agent: String?
    )
}
