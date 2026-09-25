package app.melogold.domain.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TTML lyrics as Apple Music and the AMLL TTML database write them: `p` lines with `span` words,
 * `ttm:agent` singers, `ttm:role="x-bg"` backing vocals, `x-translation` and `x-roman` parts and
 * `xml:lang`. This is the format Melogold saves lyrics in and shares them as.
 */
object TtmlFormat {
    private const val NS_TTML = "http://www.w3.org/ns/ttml"
    private const val NS_TTM = "http://www.w3.org/ns/ttml#metadata"
    private const val NS_ITUNES = "http://music.apple.com/lyric-ttml-internal"
    private const val NS_XML = "http://www.w3.org/XML/1998/namespace"

    private const val ROLE_BACKGROUND = "x-bg"
    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"

    /**
     * Whether [text] looks like TTML.
     */
    fun matches(text: String): Boolean {
        val head = text.trimStart().take(512)
        return (head.startsWith("<?xml") || head.startsWith("<tt")) && "<tt" in head
    }

    /**
     * Parses TTML; null when it is not TTML or has no timed line.
     */
    fun parse(text: String): SyncedLyrics? {
        val root = runCatching { parseXml(text).documentElement }.getOrNull() ?: return null
        if (root.localName != "tt") return null

        val declaredAgents = root.elements()
            .filter { it.localName == "agent" }
            .mapNotNull { agent ->
                agent.attr(NS_XML, "id")?.let { id ->
                    id to agent.elements().firstOrNull { it.localName == "name" }?.textContent?.trim()?.ifEmpty { null }
                }
            }
            .toList()

        val parsed = root.elements()
            .filter { it.localName == "p" }
            .mapNotNull(::parseLine)
            .sortedBy { it.startMs }
            .toList()
        if (parsed.isEmpty()) return null

        val agentOrder = (declaredAgents.map { it.first } + parsed.mapNotNull { it.agent }).distinct()
        val names = declaredAgents.toMap()
        val agents = assignSides(agentOrder).map { it.copy(name = names[it.id]) }
        val sides = agents.associate { it.id to it.side }

        val timing = when (root.attr(NS_ITUNES, "timing") ?: root.getAttribute("itunes:timing").ifEmpty { null }) {
            "Line" -> LyricsTiming.Line
            "Word" -> LyricsTiming.Word
            else -> if (parsed.any { it.words.isNotEmpty() }) LyricsTiming.Word else LyricsTiming.Line
        }

        return SyncedLyrics(
            lines = parsed.map { line -> line.copy(side = line.agent?.let { sides[it] } ?: VocalSide.Start) },
            timing = timing,
            agents = agents,
            language = root.attr(NS_XML, "lang")
        )
    }

    /**
     * Writes Apple/AMLL-compatible TTML.
     */
    fun write(lyrics: SyncedLyrics): String = buildString {
        // Duets without declared singers get v1 (start) and v2 (end)
        val agents = lyrics.agents.ifEmpty {
            if (lyrics.isDuet) listOf(LyricsAgent("v1", VocalSide.Start), LyricsAgent("v2", VocalSide.End)) else emptyList()
        }
        val agentBySide = agents.groupBy { it.side }.mapValues { it.value.first().id }
        val end = lyrics.lines.maxOfOrNull { it.endMs } ?: 0L

        append("""<tt xmlns="$NS_TTML" xmlns:ttm="$NS_TTM" xmlns:itunes="$NS_ITUNES"""")
        append(""" itunes:timing="${if (lyrics.timing == LyricsTiming.Word) "Word" else "Line"}"""")
        lyrics.language?.let { append(""" xml:lang="${escape(it)}"""") }
        append('>')

        append("<head><metadata>")
        agents.forEach { agent ->
            append("""<ttm:agent type="person" xml:id="${escape(agent.id)}"""")
            if (agent.name == null) append("/>")
            else append("><ttm:name type=\"full\">").append(escape(agent.name)).append("</ttm:name></ttm:agent>")
        }
        append("</metadata></head>")

        append("""<body dur="${time(end)}"><div begin="${time(lyrics.lines.firstOrNull()?.startMs ?: 0)}" end="${time(end)}">""")
        lyrics.lines.forEach { line ->
            append("""<p begin="${time(line.startMs)}" end="${time(line.endMs)}"""")
            (line.agent ?: agentBySide[line.side].takeIf { agents.isNotEmpty() })
                ?.let { append(""" ttm:agent="${escape(it)}"""") }
            line.language?.let { append(""" xml:lang="${escape(it)}"""") }
            append('>')

            if (line.words.isEmpty()) append(escape(line.text)) else appendWords(line.words)

            line.background?.let { background ->
                append("""<span ttm:role="$ROLE_BACKGROUND">""")
                appendWords(background.words)
                append("</span>")
            }
            line.translation?.let { append("""<span ttm:role="$ROLE_TRANSLATION">""").append(escape(it)).append("</span>") }
            line.transliteration?.let { append("""<span ttm:role="$ROLE_ROMAN">""").append(escape(it)).append("</span>") }
            append("</p>")
        }
        append("</div></body></tt>")
    }

    private fun StringBuilder.appendWords(words: List<SyncedWord>) = words.forEach { word ->
        append("""<span begin="${time(word.startMs)}" end="${time(word.endMs)}">""")
        append(escape(word.text.trimEnd()))
        append("</span>")
        // A space between words is a text node between the spans, as Apple writes it
        if (word.text.isNotEmpty() && word.text.last().isWhitespace()) append(' ')
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parseLine(p: Element): SyncedLine? {
        val words = mutableListOf<SyncedWord>()
        val backgroundWords = mutableListOf<SyncedWord>()
        var backgroundText = ""
        val plainText = StringBuilder()
        var translation: String? = null
        var transliteration: String? = null

        fun collect(parent: Node, target: MutableList<SyncedWord>, plain: StringBuilder?) {
            parent.childNodes.forEachNode { node ->
                when (node.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                        val value = node.nodeValue.orEmpty()
                        if (target.isNotEmpty() && value.isNotEmpty() && value.isBlank()) {
                            val last = target.last()
                            if (!last.text.endsWith(' ')) target[target.lastIndex] = last.copy(text = last.text + " ")
                        } else plain?.append(value)
                    }

                    Node.ELEMENT_NODE -> {
                        val element = node as Element
                        if (element.localName != "span") return@forEachNode
                        when (element.attr(NS_TTM, "role")) {
                            ROLE_BACKGROUND -> {
                                collect(element, backgroundWords, null)
                                if (backgroundWords.isEmpty()) backgroundText = element.textContent.trim()
                            }

                            ROLE_TRANSLATION -> translation = element.textContent.trim().ifEmpty { null }
                            ROLE_ROMAN -> transliteration = element.textContent.trim().ifEmpty { null }
                            else -> {
                                val begin = element.attr(null, "begin")?.let(::parseTime)
                                val end = element.attr(null, "end")?.let(::parseTime)
                                if (begin != null && end != null) target += SyncedWord(begin, end, element.textContent)
                                else collect(element, target, plain)
                            }
                        }
                    }
                }
            }
        }

        collect(p, words, plainText)

        val start = p.attr(null, "begin")?.let(::parseTime) ?: words.firstOrNull()?.startMs ?: return null
        val end = p.attr(null, "end")?.let(::parseTime) ?: words.lastOrNull()?.endMs ?: return null
        val text = if (words.isEmpty()) plainText.toString().trim() else words.joinToString("") { it.text }.trim()
        if (text.isEmpty()) return null

        val background = when {
            backgroundWords.isNotEmpty() -> BackingVocals(
                startMs = backgroundWords.first().startMs,
                endMs = backgroundWords.last().endMs,
                words = backgroundWords.trimLast()
            )

            backgroundText.isNotEmpty() -> BackingVocals(start, end, listOf(SyncedWord(start, end, backgroundText)))
            else -> null
        }

        return SyncedLine(
            startMs = start,
            endMs = end,
            text = text,
            words = words.trimLast(),
            agent = p.attr(NS_TTM, "agent"),
            language = p.attr(NS_XML, "lang"),
            background = background,
            translation = translation,
            transliteration = transliteration
        )
    }

    /** The last word keeps no trailing space. */
    private fun List<SyncedWord>.trimLast() =
        if (isEmpty()) this else dropLast(1) + last().copy(text = last().text.trimEnd())

    /**
     * TTML times: clock ("1:02:03.450", "02:03.45", "3.5") or offset ("12.3s", "450ms", "2m").
     */
    fun parseTime(value: String): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null

        return runCatching {
            when {
                text.endsWith("ms") -> text.dropLast(2).toDouble().toLong()
                text.endsWith("s") -> (text.dropLast(1).toDouble() * 1000).toLong()
                text.endsWith("m") -> (text.dropLast(1).toDouble() * 60_000).toLong()
                text.endsWith("h") -> (text.dropLast(1).toDouble() * 3_600_000).toLong()
                else -> {
                    val parts = text.split(':')
                    val seconds = parts.last().toDouble()
                    val minutes = parts.getOrNull(parts.size - 2)?.toLong() ?: 0L
                    val hours = parts.getOrNull(parts.size - 3)?.toLong() ?: 0L
                    ((hours * 3600 + minutes * 60) * 1000 + Math.round(seconds * 1000))
                }
            }
        }.getOrNull()
    }

    private fun time(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val hours = total / 3_600_000
        val minutes = total / 60_000 % 60
        val seconds = total / 1000 % 60
        val millis = total % 1000
        return if (hours > 0) "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
        else "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun escape(text: String) = buildString(text.length) {
        text.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(it)
            }
        }
    }

    private fun parseXml(text: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        // No DTDs, no external entities: lyrics files come from anywhere
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false
        ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
    }.newDocumentBuilder().parse(InputSource(StringReader(text)))

    private fun Element.attr(namespace: String?, name: String): String? {
        val value = if (namespace == null) getAttribute(name) else getAttributeNS(namespace, name)
        return value?.takeIf { it.isNotEmpty() }
    }

    /** Every element below this one, in document order. */
    private fun Element.elements(): Sequence<Element> = sequence {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                yield(child)
                yieldAll(child.elements())
            }
        }
    }

    private inline fun org.w3c.dom.NodeList.forEachNode(action: (Node) -> Unit) {
        for (index in 0 until length) action(item(index))
    }
}

/**
 * Picks the parser for a lyrics file by its content.
 */
object LyricsFormats {
    enum class Format { Ttml, Lrc, Plain }

    fun detect(text: String): Format = when {
        TtmlFormat.matches(text) -> Format.Ttml
        LrcFormat.matches(text) -> Format.Lrc
        else -> Format.Plain
    }

    /**
     * Time-synced lyrics from TTML or LRC; null for plain text or a broken file.
     */
    fun parseSynced(text: String): SyncedLyrics? = when (detect(text)) {
        Format.Ttml -> TtmlFormat.parse(text)
        Format.Lrc -> LrcFormat.parse(text)
        Format.Plain -> null
    }
}
