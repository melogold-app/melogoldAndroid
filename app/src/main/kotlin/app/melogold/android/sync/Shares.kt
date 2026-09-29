package app.melogold.android.sync

import app.melogold.android.models.Song
import app.melogold.android.models.TrackOverride
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.CreateShareRequest
import app.melogold.android.sync.api.MelogoldApi
import app.melogold.android.sync.api.ShareDto
import app.melogold.android.sync.api.TrackInput
import app.melogold.android.ui.kit.parseDuration
import app.melogold.domain.share.ShareLinks
import app.melogold.domain.share.ShareRef

/** Most tracks in a snapshot, and its name (API §4.11, §11). */
const val SHARE_MAX_TRACKS = 1000
private const val SHARE_NAME_MAX = 200
private const val VIDEO_ID_LENGTH = 11

/** What sharing an own playlist came to (tasks/0017). */
sealed interface PlaylistShare {
    /** A snapshot on the server of the account: [url] opens in the app, and on the web for anyone. */
    data class OnServer(val url: String, val shareId: String) : PlaylistShare

    /**
     * No server to make it on: the first [shown] of [total] videos as one list on YouTube, which everyone can open
     * (the caption "The link opens the first 50 tracks on YouTube" says so).
     */
    data class OnYouTube(val url: String, val shown: Int, val total: Int) : PlaylistShare

    /** No track of it is a YouTube video: files of the device cannot be shared. */
    data object NoTracks : PlaylistShare

    /** The account has as many links as the server keeps (`409 share_limit_reached`): delete old ones first. */
    data class LimitReached(val max: Int) : PlaylistShare
}

/**
 * Links to own playlists (API §4.11, tasks/0017): a snapshot on the server of the account, the list of the account's
 * links, and the snapshot behind a link of any Melogold server. Without an account, or on a server that does not know
 * `features.share`, an own playlist is shared as a list of its first 50 videos on YouTube.
 */
class Shares(private val account: Account) {
    /** Whether the server of the signed-in account makes snapshots (`features.share`); false if it cannot be asked. */
    suspend fun available(): Boolean =
        account.session != null && runCatching { account.serverInfo().features.share != null }.getOrDefault(false)

    /** Makes the link of an own playlist [name] with [tracks], on the server when it can, else on YouTube. */
    suspend fun sharePlaylist(name: String, tracks: List<TrackInput>): PlaylistShare {
        val shareable = tracks.filter { it.videoId.isVideoId }.take(SHARE_MAX_TRACKS)
        if (shareable.isEmpty()) return PlaylistShare.NoTracks

        if (available()) try {
            val created = account.authorized { api, token ->
                api.createShare(token, CreateShareRequest(kind = "playlist", name = name.trim().take(SHARE_NAME_MAX), tracks = shareable))
            }
            return PlaylistShare.OnServer(created.url, created.shareId)
        } catch (e: ApiException) {
            if (e.code == "share_limit_reached") return PlaylistShare.LimitReached(SHARE_MAX_SNAPSHOTS)
            // Any other trouble (no network, a busy server): the list on YouTube still works
        }

        val ids = shareable.map { it.videoId }
        val url = ShareLinks.watchVideos(ids) ?: return PlaylistShare.NoTracks
        return PlaylistShare.OnYouTube(url, shown = minOf(ids.size, ShareLinks.WATCH_VIDEOS_LIMIT), total = ids.size)
    }

    /** The links of this account, the newest first. */
    suspend fun mine(): List<ShareDto> = account.authorized { api, token -> api.shares(token).shares }

    /** The link stops opening. */
    suspend fun delete(shareId: String) = account.authorized { api, token -> api.deleteShare(token, shareId) }

    /**
     * The snapshot a link points at, without signing in: the server of a link is often another one than the account's,
     * so it is asked by its own address.
     */
    suspend fun open(ref: ShareRef): ShareDto {
        if (ref.serverUrl == account.serverUrl) return account.api().share(ref.shareId)
        val api = MelogoldApi(ref.serverUrl)
        try {
            return api.share(ref.shareId)
        } finally {
            api.close()
        }
    }

    private companion object {
        const val SHARE_MAX_SNAPSHOTS = 200
    }
}

private val String.isVideoId get() = length == VIDEO_ID_LENGTH && !startsWith(LOCAL_KEY_PREFIX)

/**
 * The song as a snapshot carries it: the names the person gave it (tasks/0012) over YouTube's, so a collected album
 * arrives as one.
 */
fun Song.toShareInput(override: TrackOverride? = null) = TrackInput(
    videoId = id,
    title = override?.title ?: title,
    artistsText = override?.artistsText ?: artistsText,
    albumTitle = override?.albumTitle,
    durationMs = parseDuration(durationText),
    durationText = durationText,
    thumbnailUrl = thumbnailUrl,
    explicit = explicit.takeIf { it }
)
