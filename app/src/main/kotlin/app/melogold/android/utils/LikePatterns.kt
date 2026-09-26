package app.melogold.android.utils

/**
 * The LIKE patterns of a library search (REWRITE §3.1.2), escaped for `ESCAPE '\'`. SQLite folds only ASCII case,
 * so [text] comes as typed, lowercase and capitalized: «кино» also finds «Кино».
 */
fun likePatterns(text: String): Triple<String, String, String> {
    val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return Triple(
        "%$escaped%",
        "%${escaped.lowercase()}%",
        "%${escaped.replaceFirstChar { it.uppercase() }}%"
    )
}
