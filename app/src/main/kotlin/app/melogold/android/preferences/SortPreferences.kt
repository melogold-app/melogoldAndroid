package app.melogold.android.preferences

import androidx.compose.runtime.Immutable
import app.melogold.android.GlobalPreferencesHolder

/**
 * The sort of every sortable list (REWRITE §3.11.8, §4.11.5), kept between launches: one key per
 * list holding the field and the direction, e.g. "Title:desc".
 */
object SortPreferences : GlobalPreferencesHolder() {
    /** Favorites, and an artist's tracks in them. */
    var favorites by string(defaultValue = "", name = "sort.favorites")
    var allTracks by string(defaultValue = "", name = "sort.allTracks")
    var downloads by string(defaultValue = "", name = "sort.downloads")

    /** The tracks of every own playlist. */
    var playlistItems by string(defaultValue = "", name = "sort.playlistItems")
}

/** How a list is sorted: by [field], [descending] or not. */
@Immutable
data class ListSort<T : Enum<T>>(val field: T, val descending: Boolean) {
    /** The stored form, "Title:desc". */
    fun encode() = "${field.name}:${if (descending) "desc" else "asc"}"
}

/** Reads the stored form of a [ListSort]; an empty or unknown value is [default]. */
inline fun <reified T : Enum<T>> String.toListSort(default: ListSort<T>): ListSort<T> {
    val field = enumValues<T>().firstOrNull { it.name == substringBefore(':') } ?: return default
    return ListSort(field = field, descending = substringAfter(':') == "desc")
}
