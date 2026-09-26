package app.melogold.android.utils

import androidx.media3.common.MediaItem
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.ContinuationBody
import app.melogold.providers.innertube.models.bodies.NextBody
import app.melogold.providers.innertube.requests.nextPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class YouTubeRadio(
    private val videoId: String? = null,
    private var playlistId: String? = null,
    private var playlistSetVideoId: String? = null,
    private var parameters: String? = null
) {
    private var nextContinuation: String? = null

    /** The next tracks of the radio; none when YouTube fails. */
    suspend fun process(): List<MediaItem> = next().getOrNull().orEmpty()

    /** The next tracks of the radio, or why YouTube did not give them (no network, YouTube down). */
    suspend fun next(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        val continuation = nextContinuation

        val page = if (continuation == null) {
            Innertube.nextPage(
                NextBody(
                    videoId = videoId,
                    playlistId = playlistId,
                    params = parameters,
                    playlistSetVideoId = playlistSetVideoId
                )
            )?.map { nextResult ->
                playlistId = nextResult.playlistId
                parameters = nextResult.params
                playlistSetVideoId = nextResult.playlistSetVideoId

                nextResult.itemsPage
            }
        } else {
            Innertube.nextPage(ContinuationBody(continuation = continuation))
        } ?: Result.failure(IOException("YouTube did not answer"))

        val songsPage = page.getOrNull()
        nextContinuation = songsPage?.continuation?.takeUnless { nextContinuation == it }
        page.map { it?.items?.map(Innertube.SongItem::asMediaItem).orEmpty() }
    }
}
