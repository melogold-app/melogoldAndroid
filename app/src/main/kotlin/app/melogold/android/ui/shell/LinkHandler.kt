package app.melogold.android.ui.shell

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import app.melogold.android.R
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.android.ui.screens.searchResultRoute
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlay
import app.melogold.android.utils.toast
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.requests.playlistPage
import app.melogold.providers.innertube.requests.song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LinkHandler"

/**
 * Opens YouTube / YouTube Music links (REDESIGN-M3E §1.3): shared or opened links, a link pasted
 * into the search field.
 *
 * - `/search?q=` switches to Search and shows the results;
 * - albums, playlists and channels open in the stack of the current section;
 * - `/watch?v=` and `youtu.be/` play the video.
 */
@Stable
class LinkHandler internal constructor(
    private val context: Context,
    private val nav: MainNavState,
    private val binder: suspend () -> PlayerService.Binder?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Opens [url]; `false` (and a toast) if it is not a link at all.
     */
    fun open(url: String): Boolean {
        val uri = runCatching { url.trim().toUri() }.getOrNull()?.takeIf { it.host != null }
        if (uri == null) {
            context.toast(context.getString(R.string.error_url, url))
            return false
        }

        open(uri)
        return true
    }

    @Suppress("CyclomaticComplexMethod")
    fun open(uri: Uri) {
        val path = uri.pathSegments.firstOrNull()
        Log.d(TAG, "Opening url: $uri ($path)")

        scope.launch {
            when (path) {
                "search" -> uri.getQueryParameter("q")?.let { query ->
                    nav.navigate(TopLevelDestination.Search) { searchResultRoute.ensureGlobal(query) }
                }

                "playlist" -> uri.getQueryParameter("list")?.let { playlistId ->
                    val browseId = "VL$playlistId"

                    if (playlistId.startsWith("OLAK5uy_")) Innertube.playlistPage(
                        body = BrowseBody(browseId = browseId)
                    )
                        ?.getOrNull()
                        ?.songsPage
                        ?.items
                        ?.firstOrNull()
                        ?.album
                        ?.endpoint
                        ?.browseId
                        ?.let { albumId -> nav.navigate { albumRoute.ensureGlobal(albumId) } }
                        ?: showError(uri)
                    else nav.navigate {
                        playlistRoute.ensureGlobal(
                            p0 = browseId,
                            p1 = uri.getQueryParameter("params"),
                            p2 = null,
                            p3 = playlistId.startsWith("RDCLAK5uy_")
                        )
                    }
                }

                "channel", "c" -> uri.lastPathSegment?.let { channelId ->
                    nav.navigate { artistRoute.ensureGlobal(channelId) }
                }

                else -> when {
                    path == "watch" -> uri.getQueryParameter("v")
                    uri.host == "youtu.be" -> path
                    else -> {
                        showError(uri)
                        null
                    }
                }?.let { videoId ->
                    Innertube.song(videoId)?.getOrNull()?.let { song ->
                        val player = binder()?.player
                        withContext(Dispatchers.Main) {
                            player?.forcePlay(song.asMediaItem)
                        }
                    } ?: showError(uri)
                }
            }
        }
    }

    private suspend fun showError(uri: Uri) = withContext(Dispatchers.Main) {
        context.toast(context.getString(R.string.error_url, uri))
    }
}

val LocalLinkHandler = staticCompositionLocalOf<LinkHandler> { error("No LinkHandler provided") }
