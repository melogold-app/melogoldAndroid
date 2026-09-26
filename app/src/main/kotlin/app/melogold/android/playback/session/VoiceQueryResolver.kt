package app.melogold.android.playback.session

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.R
import app.melogold.android.data.NetworkMonitor
import app.melogold.android.data.repo.applying
import app.melogold.android.data.repo.applyingHidden
import app.melogold.android.data.repo.applyingTo
import app.melogold.android.data.repo.pendingMutations
import app.melogold.android.models.Song
import app.melogold.android.service.PlayerService
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.forcePlayFromBeginning
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.likePatterns
import app.melogold.android.utils.playWithRadio
import app.melogold.android.utils.toast
import app.melogold.core.ui.utils.SongBundleAccessor
import app.melogold.core.ui.utils.activityIntentBundle
import app.melogold.domain.voice.CollectionKind
import app.melogold.domain.voice.VoiceCatalog
import app.melogold.domain.voice.VoiceCollection
import app.melogold.domain.voice.VoiceCommands
import app.melogold.domain.voice.VoiceException
import app.melogold.domain.voice.VoicePlayer
import app.melogold.domain.voice.VoiceQuery
import app.melogold.domain.voice.VoiceRadio
import app.melogold.domain.voice.VoiceRequest
import app.melogold.domain.voice.VoiceTrack
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.MusicShelfRenderer
import app.melogold.providers.innertube.models.NavigationEndpoint
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.models.bodies.SearchBody
import app.melogold.providers.innertube.requests.albumPage
import app.melogold.providers.innertube.requests.artistPage
import app.melogold.providers.innertube.requests.playlistPage
import app.melogold.providers.innertube.requests.searchPage
import app.melogold.providers.innertube.requests.song
import app.melogold.providers.innertube.utils.from
import app.melogold.providers.innertube.youtube.YouTubeItem
import app.melogold.providers.innertube.youtube.YouTubeSearchFilter
import app.melogold.providers.innertube.youtube.youTubeSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "VoiceQueryResolver"

/**
 * The voice rule of the app (REWRITE §3.14.3, tasks/0006-gemini-app-functions.md): [VoiceCommands] over the
 * library, YouTube Music, YouTube and the player service. «включи X» from Google Assistant, Android Auto and
 * media controllers and the functions Gemini calls (AppFunctions) all come here.
 */
object VoiceQueryResolver {
    /** What «включи X» asks for: [query], or else what the extras of `MEDIA_PLAY_FROM_SEARCH` say. */
    fun request(query: String?, extras: Bundle?): VoiceRequest {
        val bundle = extras?.activityIntentBundle
        return VoiceQuery.request(
            focus = bundle?.mediaFocus,
            query = query?.takeIf { it.isNotBlank() } ?: bundle?.query,
            text = bundle?.text,
            artist = bundle?.artist,
            album = bundle?.album,
            genre = bundle?.genre,
            title = bundle?.title,
            playlist = bundle?.playlist
        )
    }

    /** The commands, driving the player behind [binder]. */
    fun commands(context: Context, binder: suspend () -> PlayerService.Binder): VoiceCommands {
        val catalog = AppVoiceCatalog((context.applicationContext as MainApplication).container.network)
        return VoiceCommands(catalog, BinderVoicePlayer(binder, catalog))
    }

    /**
     * «включи X» from the system. What cannot be done shows as a toast: the old media session has no error the
     * caller would read.
     */
    suspend fun playFromSystem(context: Context, binder: PlayerService.Binder, request: VoiceRequest) {
        try {
            commands(context) { binder }.play(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VoiceException) {
            Log.i(TAG, "$request: ${e.message}")
            context.toast(message(context, request, e))
        }
    }

    private fun message(context: Context, request: VoiceRequest, error: VoiceException): String {
        val what = when (request) {
            VoiceRequest.Resume -> return context.getString(R.string.voice_nothing_to_resume)
            is VoiceRequest.Song -> request.query
            is VoiceRequest.Artist -> request.name
            is VoiceRequest.Album -> request.name
            is VoiceRequest.Playlist -> request.name
        }
        return when (error.kind) {
            VoiceException.Kind.Unavailable -> context.getString(R.string.voice_unavailable, what)
            VoiceException.Kind.NotFound, VoiceException.Kind.InvalidArgument ->
                context.getString(R.string.voice_not_found, what)
        }
    }
}

/**
 * The library (Room, with the deletions waiting for «Undo» applied) and YouTube Music / YouTube (Innertube). It
 * keeps the queue item of every track it hands out, so what plays has the cover, the album and the artists.
 */
internal class AppVoiceCatalog(private val network: NetworkMonitor) : VoiceCatalog {
    private val items = ConcurrentHashMap<String, MediaItem>()

    override val isOnline: Boolean
        get() = network.isOnline.value

    /** The queue item of [track]: the one it came with, or one made of what [track] knows. */
    fun mediaItem(track: VoiceTrack): MediaItem = items[track.id] ?: MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(track.id)
        .setCustomCacheKey(track.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artists)
                .setExtras(SongBundleAccessor.bundle { durationText = track.durationText })
                .build()
        )
        .build()

    // region The library
    override suspend fun libraryTracks(query: String, limit: Int): List<VoiceTrack> {
        val (asTyped, lower, capitalized) = likePatterns(query)
        return Database.searchSongs(asTyped, lower, capitalized, limit).first().visible().map { it.track() }
    }

    override suspend fun libraryTrack(videoId: String): VoiceTrack? =
        listOfNotNull(Database.song(videoId).first()).visible().firstOrNull()?.track()

    override suspend fun favorites(): List<VoiceTrack> =
        Database.favoritesByLikedAtDesc().first().visible().map { it.track() }

    override suspend fun library(kind: CollectionKind): List<VoiceCollection> = when (kind) {
        CollectionKind.Playlist -> Database.playlistPreviewsByDateAddedDesc().first()
            .applying(pendingMutations.pending.value)
            .map { VoiceCollection(kind, it.id.toString(), it.name) }

        CollectionKind.Artist -> Database.artistsByNameAsc().first()
            .mapNotNull { artist -> artist.name?.let { VoiceCollection(kind, artist.id, it) } }

        CollectionKind.Album -> Database.albumsByTitleAsc().first()
            .mapNotNull { album -> album.title?.let { VoiceCollection(kind, album.id, it, album.authorsText) } }
    }

    override suspend fun libraryTracks(collection: VoiceCollection): List<VoiceTrack> = when (collection.kind) {
        CollectionKind.Playlist -> collection.id.toLongOrNull()?.let { id ->
            Database.playlistSongs(id).first().applyingTo(id, pendingMutations.pending.value)
        }.orEmpty()

        // What the person played or liked of them
        CollectionKind.Artist ->
            (Database.artistSongs(collection.id).first() + Database.artistFavorites(collection.id).first())
                .distinctBy { it.id }

        CollectionKind.Album -> Database.albumSongs(collection.id).first()
    }.visible().map { it.track() }

    private fun List<Song>.visible() = filterNot { it.blacklisted }.applyingHidden(pendingMutations.pending.value)

    private fun Song.track(): VoiceTrack {
        items[id] = asMediaItem
        return VoiceTrack(id, title, artistsText, durationText, inLibrary = likedAt != null || totalPlayTimeMs > 0)
    }
    // endregion

    // region YouTube Music and YouTube
    override suspend fun searchSongs(query: String): List<VoiceTrack> =
        music(query, Innertube.SearchFilter.Song, Innertube.SongItem.Companion::from).mapNotNull { it.track() }.known()

    override suspend fun searchVideos(query: String): List<VoiceTrack> =
        Innertube.youTubeSearch(query, YouTubeSearchFilter.Videos).answer().items
            .filterIsInstance<YouTubeItem.Video>()
            .filterNot { it.isLive }
            .map { video ->
                items[video.videoId] = video.asMediaItem
                VoiceTrack(video.videoId, video.title, video.channelName, video.durationText)
            }
            .known()

    override suspend fun search(kind: CollectionKind, query: String): List<VoiceCollection> = when (kind) {
        CollectionKind.Artist -> music(query, Innertube.SearchFilter.Artist, Innertube.ArtistItem::from)
            .mapNotNull { artist -> collection(kind, artist.info, authors = null) }

        CollectionKind.Album -> music(query, Innertube.SearchFilter.Album, Innertube.AlbumItem::from)
            .mapNotNull { album ->
                collection(kind, album.info, album.authors?.joinToString("") { it.name.orEmpty() })
            }

        CollectionKind.Playlist ->
            music(query, Innertube.SearchFilter.CommunityPlaylist, Innertube.PlaylistItem::from)
                .mapNotNull { playlist -> collection(kind, playlist.info, playlist.channel?.name) }
    }

    override suspend fun track(videoId: String): VoiceTrack? = Innertube.song(videoId).answer()?.track()

    override suspend fun tracks(collection: VoiceCollection): List<VoiceTrack> {
        val body = BrowseBody(browseId = collection.id)
        val songs = when (collection.kind) {
            CollectionKind.Album -> Innertube.albumPage(body).answer().songsPage?.items
            CollectionKind.Playlist -> Innertube.playlistPage(body).answer().songsPage?.items
            CollectionKind.Artist -> Innertube.artistPage(body).answer().songs
        }
        return songs.orEmpty().mapNotNull { it.track() }.visibleOnline()
    }

    override suspend fun artistMix(artist: VoiceCollection): VoiceRadio? {
        val page = Innertube.artistPage(BrowseBody(browseId = artist.id)).answer()
        return (page.shuffleEndpoint ?: page.radioEndpoint)?.let { endpoint ->
            VoiceRadio(endpoint.videoId, endpoint.playlistId, endpoint.params, endpoint.playlistSetVideoId)
        }
    }

    private suspend fun <T : Innertube.Item> music(
        query: String,
        filter: Innertube.SearchFilter,
        from: (MusicShelfRenderer.Content) -> T?
    ): List<T> = Innertube.searchPage(SearchBody(query = query, params = filter.value), from).answer()?.items.orEmpty()

    private fun Innertube.SongItem.track(): VoiceTrack? {
        val id = info?.endpoint?.videoId ?: return null
        items[id] = asMediaItem
        return VoiceTrack(
            id = id,
            title = info?.name ?: return null,
            artists = authors?.joinToString("") { it.name.orEmpty() }?.takeIf { it.isNotBlank() },
            durationText = durationText
        )
    }

    private fun collection(
        kind: CollectionKind,
        info: Innertube.Info<NavigationEndpoint.Endpoint.Browse>?,
        authors: String?
    ): VoiceCollection? {
        val id = info?.endpoint?.browseId ?: return null
        val name = info.name ?: return null
        return VoiceCollection(kind, id, name, authors?.takeIf { it.isNotBlank() })
    }

    /** Marks what the person liked or played before, and drops what they hid. */
    private fun List<VoiceTrack>.known(): List<VoiceTrack> {
        val rows = Database.songsNow(map { it.id }).associateBy { it.id }
        return visibleOnline(rows).map { track ->
            val row = rows[track.id]
            track.copy(inLibrary = row != null && (row.likedAt != null || row.totalPlayTimeMs > 0))
        }
    }

    /** Without what the person hid. */
    private fun List<VoiceTrack>.visibleOnline(
        rows: Map<String, Song> = Database.songsNow(map { it.id }).associateBy { it.id }
    ): List<VoiceTrack> {
        val hiding = emptySet<String>().applyingHidden(pendingMutations.pending.value)
        return filterNot { it.id in hiding || rows[it.id]?.blacklisted == true }
    }

    /**
     * The answer of an Innertube call: its failure (no network, YouTube down) and a cancelled call (null) are
     * thrown. A successful null is YouTube having nothing (a search without results): not found, not a failure.
     */
    private fun <T> Result<T>?.answer(): T =
        (this ?: throw IOException("YouTube did not answer")).getOrElse { throw IOException("YouTube did not answer", it) }
    // endregion
}

/** The player service: the same actions as the screens (search results, albums, the artist's mix). */
internal class BinderVoicePlayer(
    private val binder: suspend () -> PlayerService.Binder,
    private val catalog: AppVoiceCatalog
) : VoicePlayer {
    override suspend fun playWithSimilar(track: VoiceTrack) {
        val item = catalog.mediaItem(track)
        onPlayer { playWithRadio(item) }
    }

    override suspend fun playTracks(tracks: List<VoiceTrack>) {
        val items = tracks.map(catalog::mediaItem)
        onPlayer {
            stopRadio()
            player.forcePlayFromBeginning(items)
        }
    }

    override suspend fun playRadio(radio: VoiceRadio) = onPlayer {
        stopRadio()
        playRadio(
            NavigationEndpoint.Endpoint.Watch(
                params = radio.params,
                playlistId = radio.playlistId,
                videoId = radio.videoId,
                playlistSetVideoId = radio.playlistSetVideoId
            )
        )
    }

    override suspend fun pause(): VoiceTrack? = onPlayer {
        player.currentMediaItem?.also { player.pause() }?.track()
    }

    override suspend fun resume(): VoiceTrack? {
        val binder = binder()
        // A service that has just started restores the last queue in a moment
        repeat(RESTORE_TRIES) {
            val track = withContext(Dispatchers.Main) {
                binder.player.currentMediaItem?.let { item ->
                    if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                    if (binder.player.playbackState == Player.STATE_ENDED) binder.player.seekToDefaultPosition()
                    binder.player.play()
                    item.track()
                }
            }
            if (track != null) return track
            delay(RESTORE_WAIT)
        }
        return null
    }

    override suspend fun next(): VoiceTrack? = onPlayer {
        if (player.mediaItemCount == 0) return@onPlayer null
        player.forceSeekToNext()
        player.play()
        player.currentMediaItem?.track()
    }

    override suspend fun needsApp(): Boolean {
        // The foreground is asked for on the main thread right after the queue changes
        delay(FOREGROUND_WAIT)
        val refused = onPlayer { foregroundRefused }
        val visible = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        return refused && !visible
    }

    private suspend fun <T> onPlayer(block: PlayerService.Binder.() -> T): T {
        val binder = binder()
        return withContext(Dispatchers.Main) { binder.block() }
    }

    private fun MediaItem.track() = VoiceTrack(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artists = mediaMetadata.artist?.toString()
    )

    private companion object {
        const val RESTORE_TRIES = 20
        val RESTORE_WAIT = 100.milliseconds
        val FOREGROUND_WAIT = 400.milliseconds
    }
}
