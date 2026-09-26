package app.melogold.domain.voice

import java.util.Locale

/** How well a name matches what the person said; a stronger match has a larger ordinal. */
enum class MatchLevel {
    /** Every word said is in the name or its authors, in any order: «кино солнце» for «Звезда по имени Солнце». */
    Words,

    /** The name is inside what was said, or what was said is inside the name. */
    Contains,

    /** Whole words: «Группа крови» for «группа крови кино», «Кино» for «кино 1988». */
    Prefix,

    /** The same words, perhaps with the artist before or after: «Группа крови Кино». */
    Exact
}

/**
 * Matches names the way a person says them: case, «ё», punctuation and extra spaces do not count
 * (SQLite folds only ASCII, so the library is matched here, not in a query).
 */
object NameMatch {
    /** A name shorter than this matches inside what was said only as a whole word. */
    private const val MIN_INSIDE = 3

    fun normalize(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    /** How [name] (made by [authors]) matches [wanted]; null when it does not. */
    fun level(name: String, authors: String?, wanted: String): MatchLevel? {
        val said = normalize(wanted)
        if (said.isEmpty()) return null

        val title = normalize(name)
        val by = authors?.let(::normalize)?.takeIf { it.isNotEmpty() }
        val forms = listOfNotNull(title, by?.let { "$title $it" }, by?.let { "$it $title" }).filter { it.isNotEmpty() }
        if (forms.isEmpty()) return null

        val level = forms.mapNotNull { form ->
            when {
                form == said -> MatchLevel.Exact
                form.startsWith("$said ") || said.startsWith("$form ") -> MatchLevel.Prefix
                said in form -> MatchLevel.Contains
                form.length >= MIN_INSIDE && form in said -> MatchLevel.Contains
                " $form " in " $said " -> MatchLevel.Contains
                else -> null
            }
        }.maxOrNull()
        if (level != null) return level

        val all = forms.last()
        return MatchLevel.Words.takeIf { said.split(' ').all { it in all } }
    }

    /** The longest word of [wanted]: the library is asked for it, [level] sorts out what comes back. */
    fun keyWord(wanted: String): String? =
        normalize(wanted).split(' ').maxByOrNull { it.length }?.takeIf { it.isNotEmpty() }

    fun level(collection: VoiceCollection, wanted: String) = level(collection.name, collection.authors, wanted)

    /**
     * The item of [items] that matches [wanted] best, with its level; of equal matches the first, so the order of
     * [items] (a search's relevance, the library's own order) decides.
     */
    fun <T> best(items: List<T>, wanted: String, level: (T, String) -> MatchLevel?): Pair<T, MatchLevel>? {
        var best: Pair<T, MatchLevel>? = null
        for (item in items) {
            val found = level(item, wanted) ?: continue
            if (best == null || found > best.second) best = item to found
        }
        return best
    }

    fun best(collections: List<VoiceCollection>, wanted: String) = best(collections, wanted, ::level)
}
