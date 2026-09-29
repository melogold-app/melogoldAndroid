package app.melogold.android.ui.shell

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import app.melogold.android.Dependencies
import app.melogold.android.R
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.screens.albumRoute
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.ui.screens.playlistRoute
import app.melogold.android.ui.screens.searchResultRoute
import app.melogold.android.ui.screens.sharedPlaylistRoute
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.playWithRadio
import app.melogold.android.utils.toast
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.links.LinkTarget
import app.melogold.providers.innertube.links.YouTubeLinkParser
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.requests.playlistPage
import app.melogold.providers.innertube.requests.song
import app.melogold.providers.songlink.ExternalLinkResolver
import app.melogold.providers.songlink.ExternalResolution
import app.melogold.providers.songlink.MusicLinkKind
import app.melogold.providers.songlink.MusicServiceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LinkHandler"

/**
 * Opens YouTube / YouTube Music links (REWRITE §2.3, §4.9): shared or opened links and the ⧉
 * button of Search. [YouTubeLinkParser] decides what a link points at:
 *
 * - a search switches to Search and shows the results;
 * - albums, playlists and channels open in the stack of the current section;
 * - a video plays as a single track with its radio;
 * - a playlist someone shared with a link of Melogold opens as "Playlist by link";
 * - a link of Spotify, Apple Music, Yandex Music, Deezer or Tidal finds the track or album on YouTube Music
 *   ([classifyLink], tasks/0017).
 */
@Stable
class LinkHandler internal constructor(
    private val context: Context,
    private val nav: MainNavState,
    private val binder: suspend () -> PlayerService.Binder?,
    private val resolver: ExternalLinkResolver = Dependencies.application.container.externalLinks
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Opens [text] (a link or text shared by another app); `false` (and a toast) if nothing in
     * it can be opened.
     */
    fun open(text: String): Boolean {
        val link = classifyLink(text)
        if (link is AppLink.YouTube && link.target is LinkTarget.Unsupported) {
            context.toast(context.getString(R.string.error_url, text.trim()))
            return false
        }

        open(link)
        return true
    }

    /** Opens what [classifyLink] made of a text: YouTube, a playlist someone shared, a link of another service. */
    fun open(link: AppLink) {
        when (link) {
            is AppLink.YouTube -> open(link.target)
            is AppLink.SharedPlaylist -> scope.launch {
                nav.navigate { sharedPlaylistRoute.ensureGlobal(link.ref.serverUrl, link.ref.shareId) }
            }

            is AppLink.OtherService -> scope.launch { openOtherService(link.link) }
        }
    }

    fun open(uri: Uri) {
        open(uri.toString())
    }

    @Suppress("CyclomaticComplexMethod")
    fun open(target: LinkTarget) {
        Log.d(TAG, "Opening $target")

        scope.launch {
            when (target) {
                is LinkTarget.Search -> nav.navigate(TopLevelDestination.Search) {
                    searchResultRoute.ensureGlobal(target.query, SearchSource.All)
                }

                is LinkTarget.Playlist -> openPlaylist(target.playlistId)
                is LinkTarget.Album -> nav.navigate { albumRoute.ensureGlobal(target.browseId) }
                is LinkTarget.Channel -> nav.navigate { artistRoute.ensureGlobal(target.channelId) }
                is LinkTarget.Video -> playVideo(target)

                // resolve_url and importing come later (R2.9, 0.2)
                is LinkTarget.Handle, is LinkTarget.LegacyChannel -> showError(R.string.link_channel_later)
                is LinkTarget.External -> showError(R.string.link_import_later)
                is LinkTarget.Unsupported -> showError(R.string.link_unsupported)
            }
        }
    }

    /**
     * A link of Spotify, Apple Music, Yandex Music, Deezer or Tidal (tasks/0017): the same track or album on YouTube Music
     * when song.link finds it, else a search for its name and artist. Playlists come with the import, later.
     */
    private suspend fun openOtherService(link: MusicServiceLink) {
        if (link.kind == MusicLinkKind.Playlist) {
            showError(R.string.link_import_later)
            return
        }

        withContext(Dispatchers.Main) { context.toast(context.getString(R.string.link_finding)) }
        when (val found = resolver.resolve(link)) {
            is ExternalResolution.OnYouTube -> {
                val target = YouTubeLinkParser.parse(found.url)
                if (target is LinkTarget.Unsupported) showError(R.string.link_unsupported) else open(target)
            }

            is ExternalResolution.Search -> open(LinkTarget.Search(found.query))
            ExternalResolution.NotFound -> showError(R.string.link_unsupported)
        }
    }

    private suspend fun openPlaylist(playlistId: String) {
        val browseId = "VL$playlistId"

        // An album playlist: open the album of its first track
        if (playlistId.startsWith("OLAK5uy_")) Innertube.playlistPage(body = BrowseBody(browseId = browseId))
            ?.getOrNull()
            ?.songsPage
            ?.items
            ?.firstOrNull()
            ?.album
            ?.endpoint
            ?.browseId
            ?.let { albumId -> nav.navigate { albumRoute.ensureGlobal(albumId) } }
            ?: showError(R.string.link_unsupported)
        else nav.navigate {
            playlistRoute.ensureGlobal(
                p0 = browseId,
                p1 = null,
                p2 = null,
                p3 = playlistId.startsWith("RDCLAK5uy_")
            )
        }
    }

    private suspend fun playVideo(target: LinkTarget.Video) {
        val song = Innertube.song(target.videoId)?.getOrNull()
        if (song == null) {
            showError(R.string.link_unsupported)
            return
        }

        val binder = binder() ?: return
        withContext(Dispatchers.Main) {
            binder.playWithRadio(song.asMediaItem)
            target.startMs?.let { binder.player.seekTo(it) }
        }
    }

    private suspend fun showError(message: Int) = withContext(Dispatchers.Main) {
        context.toast(context.getString(message))
    }
}

val LocalLinkHandler = staticCompositionLocalOf<LinkHandler> { error("No LinkHandler provided") }
