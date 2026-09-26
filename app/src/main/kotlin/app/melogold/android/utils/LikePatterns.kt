package app.melogold.android.utils

/**
 * The LIKE patterns of a library search (REWRITE §3.1.2), escaped for `ESCAPE '\'`. SQLite folds only ASCII case,
 * so [text] comes as typed, lowercase and capitalized: «кино» also finds «Кино». With [yoAsYe] «е» and «ё» match
 * either letter (the wildcard `_`), as a person does not care which one a title has: «еще» finds «Ещё».
 */
fun likePatterns(text: String, yoAsYe: Boolean = false): Triple<String, String, String> {
    val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        .let { if (yoAsYe) it.replace(YE_OR_YO, "_") else it }
    return Triple(
        "%$escaped%",
        "%${escaped.lowercase()}%",
        "%${escaped.replaceFirstChar { it.uppercase() }}%"
    )
}

private val YE_OR_YO = Regex("[еёЕЁ]")
