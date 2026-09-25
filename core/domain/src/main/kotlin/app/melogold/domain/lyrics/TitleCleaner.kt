package app.melogold.domain.lyrics

/** What a track is called once the noise of a YouTube title is gone. */
data class CleanTitle(val artist: String?, val title: String)

/**
 * The artist and the title of a YouTube track as a lyrics search needs them (REWRITE §4.10.8;
 * vectors `docs/spec/title-cleaner.vectors.json`). In this order: emoji, `【…】`, everything from
 * " | ", brackets that are all noise ("(Official Video)", "[HD]"), a track number, "Artist - Title",
 * "feat.", quotes and spaces.
 *
 * [videoType] is `song`, `video`, `ugc`, `live` or null: songs and videos split "Artist - Title"
 * only when the left part is the channel; the rest (uploads) always split.
 */
object TitleCleaner {
    private val LENTICULAR = Regex("""【[^】]*】""")
    private val PIPE_TAIL = Regex("""\s+\|.*$""")
    private val BRACKETS = Regex("""\s*[(\[]([^()\[\]]*)[)\]]""")

    @Suppress("MaxLineLength")
    private val NOISE = Regex(
        """official( (music|lyric))? (video|audio|visualizer|clip)|official|((music|lyric) )?video|audio|lyrics?|visualizer|hd|hq|4k|8k|1080p|720p|mv|m/v|официальное видео|официальный клип|клип|премьера( клипа)?(,.*)?|текст( песни)?""",
        RegexOption.IGNORE_CASE
    )
    private val TRACK_NUMBER = Regex("""^\d{1,3}\.\s+""")
    private val SEPARATOR = Regex(""" [-–—] """)
    private val FEAT_GROUP = Regex("""\s*[(\[](feat\.?|ft\.?|featuring)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val TOPIC = Regex("""\s*[-–—]\s*(topic|тема)$""", RegexOption.IGNORE_CASE)
    private val QUOTED = Regex("""^[«"“„](.*)[»"”“]$""")
    private val SPACES = Regex("""\s+""")
    private val CHANNEL_TAILS = listOf("vevo", "official")

    fun clean(title: String, channel: String?, videoType: String?): CleanTitle {
        val upload = videoType == null || videoType == "ugc" || videoType == "live"

        var text = LENTICULAR.replace(title.withoutEmoji(), " ")
        text = PIPE_TAIL.replace(text, "")
        text = BRACKETS.replace(text) { match -> if (NOISE.matches(match.groupValues[1].trim())) "" else match.value }
        text = text.collapse()
        if (upload) text = TRACK_NUMBER.replace(text, "")

        val channelArtist = channel?.let { TOPIC.replace(it.trim(), "") }?.takeIf { it.isNotBlank() }
        var artist = channelArtist
        SEPARATOR.find(text)?.let { separator ->
            val left = text.substring(0, separator.range.first).trim()
            val isChannel = channelArtist != null && left.comparable() == channelArtist.comparable()
            if (isChannel || upload) {
                artist = left.withoutFeat()
                text = text.substring(separator.range.last + 1)
            }
        }

        text = text.withoutFeat().collapse()
        text = QUOTED.matchEntire(text)?.groupValues?.get(1)?.collapse() ?: text
        return CleanTitle(artist = artist?.collapse()?.takeIf { it.isNotEmpty() }, title = text)
    }

    private fun String.withoutFeat() = FEAT_TAIL.replace(FEAT_GROUP.replace(this, ""), "").trim()

    private fun String.collapse() = SPACES.replace(this, " ").trim()

    /** A name as the channel and the left part are compared: no feat, lower case, letters and digits only. */
    private fun String.comparable(): String {
        var name = withoutFeat().lowercase().filter(Char::isLetterOrDigit)
        CHANNEL_TAILS.forEach { tail -> if (name.length > tail.length) name = name.removeSuffix(tail) }
        return name
    }

    /** Emoji go: Extended_Pictographic, flags, the variation selector, joiners, keycaps, skin tones. */
    private fun String.withoutEmoji(): String = buildString {
        var index = 0
        while (index < this@withoutEmoji.length) {
            val codePoint = this@withoutEmoji.codePointAt(index)
            if (codePoint.isEmojiPart()) append(' ') else appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
    }

    @Suppress("MagicNumber", "CyclomaticComplexMethod", "ComplexCondition")
    private fun Int.isEmojiPart() = this == 0xFE0F || this == 0x200D || this == 0x20E3 ||
        this in 0x1F000..0x1FAFF || this in 0x1FC00..0x1FFFD || this in 0x2600..0x27BF ||
        this == 0x00A9 || this == 0x00AE || this == 0x203C || this == 0x2049 || this == 0x2122 || this == 0x2139 ||
        this in 0x2194..0x2199 || this in 0x21A9..0x21AA || this in 0x231A..0x231B || this == 0x2328 ||
        this == 0x23CF || this in 0x23E9..0x23F3 || this in 0x23F8..0x23FA || this == 0x24C2 ||
        this in 0x25AA..0x25AB || this == 0x25B6 || this == 0x25C0 || this in 0x25FB..0x25FE ||
        this in 0x2934..0x2935 || this in 0x2B05..0x2B07 || this in 0x2B1B..0x2B1C || this == 0x2B50 ||
        this == 0x2B55 || this == 0x3030 || this == 0x303D || this == 0x3297 || this == 0x3299
}
