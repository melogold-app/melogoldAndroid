package app.melogold.providers.songlink

/** Where a link of another service leads in Melogold (tasks/0017). */
sealed interface ExternalResolution {
    /** The same track or album on YouTube Music or YouTube: open [url] like any YouTube link. */
    data class OnYouTube(val url: String, val isAlbum: Boolean) : ExternalResolution

    /** No such link, but its name and artist are known: search for [query]. */
    data class Search(val query: String) : ExternalResolution

    /** The link says nothing the app can use. */
    data object NotFound : ExternalResolution
}

/**
 * Finds a link of Spotify, Apple Music, Yandex Music, Deezer or Tidal in YouTube (tasks/0017), the ways it can:
 *
 * 1. song.link, for a track or an album, with a key ([SongLinkClient]): the link on YouTube Music, else on YouTube, and
 *    when it knows neither, the name and artist it gives;
 * 2. the page of the link itself: the name and artist in its title ([PageTitles]) to search for.
 *
 * An artist is looked for by the name on its page. Playlists are not handled here: importing them is another task.
 */
class ExternalLinkResolver(private val songLink: SongLinkClient, private val pages: PageFetcher) {
    suspend fun resolve(link: MusicServiceLink): ExternalResolution {
        if (link.kind != MusicLinkKind.Artist && link.kind != MusicLinkKind.Playlist) {
            val result = songLink.resolve(link.url)
            if (result is SongLinkResult.Found) {
                val answer = result.answer
                answer.youtubeUrl?.let { return ExternalResolution.OnYouTube(it, answer.isAlbum) }
                answer.searchText?.let { return ExternalResolution.Search(it) }
            }
        }

        val page = pages.fetch(link.url) ?: return ExternalResolution.NotFound
        return PageTitles.searchText(link, page)?.let { ExternalResolution.Search(it) } ?: ExternalResolution.NotFound
    }
}
