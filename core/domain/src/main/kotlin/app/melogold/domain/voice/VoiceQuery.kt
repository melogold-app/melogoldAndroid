package app.melogold.domain.voice

/**
 * Reads what «включи X» asks for from the extras of `MEDIA_PLAY_FROM_SEARCH` and `onPlayFromSearch`
 * (REWRITE §3.14.3, step 1): the search query, or the field the `android.intent.extra.focus` points at.
 */
object VoiceQuery {
    /** `MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE` and the others, as the system sends them. */
    const val FOCUS_ANY = "vnd.android.cursor.item/*"
    const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
    const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
    const val FOCUS_GENRE = "vnd.android.cursor.item/genre"
    const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"
    const val FOCUS_TITLE = "vnd.android.cursor.item/audio"

    /**
     * An artist, an album or a playlist goes to its own command; a title, a genre or free text is a song search.
     * Nothing to search for continues the last queue.
     */
    @Suppress("LongParameterList")
    fun request(
        focus: String?,
        query: String?,
        text: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        title: String? = null,
        playlist: String? = null
    ): VoiceRequest {
        val free = query.clean() ?: text.clean()

        return when (focus) {
            FOCUS_ARTIST -> artist.clean()?.let(VoiceRequest::Artist)
            FOCUS_ALBUM -> album.clean()?.let(VoiceRequest::Album)
            FOCUS_PLAYLIST -> playlist.clean()?.let(VoiceRequest::Playlist)
            FOCUS_GENRE -> genre.clean()?.let(VoiceRequest::Song)
            FOCUS_TITLE -> listOfNotNull(title.clean(), artist.clean(), album.clean().takeIf { title.clean() == null })
                .joinToString(" ")
                .ifEmpty { null }
                ?.let(VoiceRequest::Song)

            else -> null
        } ?: free?.let(VoiceRequest::Song) ?: VoiceRequest.Resume
    }

    private fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }
}
