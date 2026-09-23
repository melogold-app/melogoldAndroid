package app.melogold.android.ui.shell

/**
 * Where a search looks (REDESIGN-M3E §2.2): the segments "All · Music · YouTube" of the search
 * results. [MainNavState.openSearch] hands the requested one to the Search section, which takes it
 * with [MainNavState.consumeSearchSource]; e.g. "Other versions" in the track menu opens Search on
 * [YouTube].
 */
enum class SearchSource {
    /** YouTube Music and plain YouTube side by side, the default for a new query */
    All,

    /** The YouTube Music catalog (songs, albums, artists, music videos, playlists) */
    Music,

    /** Plain YouTube: re-uploads, covers, live recordings */
    YouTube
}
