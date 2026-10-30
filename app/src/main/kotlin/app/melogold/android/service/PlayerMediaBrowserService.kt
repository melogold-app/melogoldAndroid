package app.melogold.android.service

import android.content.ComponentName
import android.content.ContentResolver
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.service.media.MediaBrowserService
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import app.melogold.android.data.repo.applying
import app.melogold.android.data.repo.pendingMutations
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Album
import app.melogold.android.models.PlaylistPreview
import app.melogold.android.models.Song
import app.melogold.android.preferences.TOP_LIST_LENGTH
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.intent
import app.melogold.core.data.utils.CallValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.stream.IntStream
import android.media.MediaDescription as BrowserMediaDescription
import android.media.browse.MediaBrowser.MediaItem as BrowserMediaItem

class PlayerMediaBrowserService : MediaBrowserService(), ServiceConnection {

    private var bound = false

    private val callValidator by lazy {
        CallValidator(applicationContext, R.xml.allowed_media_browser_callers)
    }

    override fun onDestroy() {
        if (bound) unbindService(this)
        super.onDestroy()
    }

    /**
     * Android Auto controls the Media3 session of the player through its token: play, pause, the queue, «play X»
     * and the lists here by their ids ([queueOf], called from the session's callback in [PlayerService]).
     */
    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        if (service !is PlayerService.Binder) return
        bound = true
        sessionToken = service.mediaSession.platformToken
    }

    override fun onServiceDisconnected(name: ComponentName) = Unit

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ) = if (callValidator.canCall(clientPackageName, clientUid)) {
        bindService(intent<PlayerService>(), this, BIND_AUTO_CREATE)
        BrowserRoot(
            MediaId.ROOT.id,
            Bundle().apply { putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) }
        )
    } else null

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<BrowserMediaItem>>
    ) = runBlocking(Dispatchers.IO) {
        result.sendResult(
            when (MediaId(parentId)) {
                MediaId.ROOT -> mutableListOf(
                    songsBrowserMediaItem,
                    playlistsBrowserMediaItem,
                    albumsBrowserMediaItem
                )

                MediaId.SONGS ->
                    Database
                        .songsByPlayTimeDesc(limit = SONGS_LIMIT)
                        .first()
                        .map { it.asBrowserMediaItem }
                        .toMutableList()
                        .apply {
                            if (isNotEmpty()) add(0, shuffleBrowserMediaItem)
                        }

                MediaId.PLAYLISTS ->
                    Database
                        .playlistPreviewsByDateAddedDesc()
                        .first()
                        .applying(pendingMutations.pending.value)
                        .map { it.asBrowserMediaItem }
                        .toMutableList()
                        .apply {
                            add(0, favoritesBrowserMediaItem)
                            add(1, offlineBrowserMediaItem)
                            add(2, topBrowserMediaItem)
                        }

                MediaId.ALBUMS ->
                    Database
                        .albumsByRowIdDesc()
                        .first()
                        .map { it.asBrowserMediaItem }
                        .toMutableList()

                else -> mutableListOf()
            }
        )
    }

    private fun uriFor(@DrawableRes id: Int) = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(resources.getResourcePackageName(id))
        .appendPath(resources.getResourceTypeName(id))
        .appendPath(resources.getResourceEntryName(id))
        .build()

    private val shuffleBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.SHUFFLE.id)
                .setTitle(getString(R.string.shuffle))
                .setIconUri(uriFor(R.drawable.shuffle))
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    private val songsBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.SONGS.id)
                .setTitle(getString(R.string.songs))
                .setIconUri(uriFor(R.drawable.musical_notes))
                .build(),
            BrowserMediaItem.FLAG_BROWSABLE
        )

    private val playlistsBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.PLAYLISTS.id)
                .setTitle(getString(R.string.playlists))
                .setIconUri(uriFor(R.drawable.playlist))
                .build(),
            BrowserMediaItem.FLAG_BROWSABLE
        )

    private val albumsBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.ALBUMS.id)
                .setTitle(getString(R.string.albums))
                .setIconUri(uriFor(R.drawable.disc))
                .build(),
            BrowserMediaItem.FLAG_BROWSABLE
        )

    private val favoritesBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.FAVORITES.id)
                .setTitle(getString(R.string.favorites))
                .setIconUri(uriFor(R.drawable.heart))
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    private val offlineBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.OFFLINE.id)
                .setTitle(getString(R.string.offline))
                .setIconUri(uriFor(R.drawable.airplane))
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    private val topBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(MediaId.TOP.id)
                .setTitle(
                    getString(
                        R.string.format_my_top_playlist,
                        TOP_LIST_LENGTH.toString()
                    )
                )
                .setIconUri(uriFor(R.drawable.trending))
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    private val Song.asBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId((MediaId.SONGS / id).id)
                .setTitle(title)
                .setSubtitle(artistsText)
                .setIconUri(thumbnailUrl?.toUri())
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    private val PlaylistPreview.asBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId((MediaId.PLAYLISTS / playlist.id.toString()).id)
                .setTitle(playlist.name)
                .setSubtitle(
                    resources.getQuantityString(
                        R.plurals.library_tracks_count,
                        songCount,
                        songCount
                    )
                )
                .setIconUri(uriFor(R.drawable.playlist))
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    private val Album.asBrowserMediaItem
        inline get() = BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId((MediaId.ALBUMS / id).id)
                .setTitle(title)
                .setSubtitle(authorsText)
                .setIconUri(thumbnailUrl?.toUri())
                .build(),
            BrowserMediaItem.FLAG_PLAYABLE
        )

    companion object {
        /** The songs of the «Songs» list: the most played. */
        private const val SONGS_LIMIT = 30

        /**
         * The queue for an id of this browser and the index to start from; `null` — not one of its ids. «Shuffle» and
         * a song of «Songs» are the list as it is shown; favorites, offline and playlists play shuffled.
         */
        suspend fun queueOf(mediaId: String): Pair<List<MediaItem>, Int>? {
            val data = mediaId.split('/')
            var index = 0
            val songs = when (data.getOrNull(0)?.let { MediaId(it) }) {
                MediaId.SHUFFLE -> Database.songsByPlayTimeDesc(limit = SONGS_LIMIT).first().shuffled()

                MediaId.SONGS -> data.getOrNull(1)?.let { songId ->
                    Database.songsByPlayTimeDesc(limit = SONGS_LIMIT).first()
                        .also { songs -> index = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0) }
                }

                MediaId.FAVORITES -> Database.favorites().first().shuffled()

                MediaId.OFFLINE -> Database.downloadedSongs().first().shuffled()

                MediaId.TOP ->
                    Database
                        .songsByPlayTimeDesc(limit = TOP_LIST_LENGTH)
                        .distinctUntilChanged()
                        .cancellable()
                        .first()

                MediaId.PLAYLISTS ->
                    data
                        .getOrNull(1)
                        ?.toLongOrNull()
                        ?.let(Database::playlistWithSongs)
                        ?.first()
                        ?.songs
                        ?.shuffled()

                MediaId.ALBUMS ->
                    data
                        .getOrNull(1)
                        ?.let(Database::albumSongs)
                        ?.first()

                else -> return null
            } ?: return null
            return songs.map(Song::asMediaItem) to index.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        }
    }

    @JvmInline
    private value class MediaId(val id: String) : CharSequence by id {
        companion object {
            val ROOT = MediaId("root")
            val SONGS = MediaId("songs")
            val PLAYLISTS = MediaId("playlists")
            val ALBUMS = MediaId("albums")

            val FAVORITES = MediaId("favorites")
            val OFFLINE = MediaId("offline")
            val TOP = MediaId("top")
            val SHUFFLE = MediaId("shuffle")
        }

        operator fun div(other: String) = MediaId("$id/$other")
        override fun chars(): IntStream = id.chars()
        override fun codePoints(): IntStream = id.codePoints()
    }
}
