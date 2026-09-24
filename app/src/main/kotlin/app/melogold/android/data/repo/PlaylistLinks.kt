package app.melogold.android.data.repo

import app.melogold.android.Database
import app.melogold.android.internal
import app.melogold.android.models.Playlist
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.models.YtLinkMode
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.classify
import app.melogold.android.utils.asMediaItem
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.models.bodies.ContinuationBody
import app.melogold.providers.innertube.requests.playlistPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A whole YouTube playlist: its page (title, cover, author) and every track, in order. */
class WholePlaylist(
    val page: Innertube.PlaylistOrAlbumPage,
    val songs: List<Innertube.SongItem>
)

/** A page of the playlist failed after others came: the list is incomplete and must not be applied. */
class IncompletePlaylistException(cause: Throwable?) : Exception("YouTube returned an incomplete playlist", cause)

/** What a refresh from YouTube did (REWRITE §3.8.1). */
sealed interface RefreshResult {
    data class Updated(val added: Int, val removed: Int) : RefreshResult

    /** "Append" without a snapshot: YouTube's list is remembered, nothing is added this time. */
    data object SnapshotTaken : RefreshResult

    /** YouTube returned an incomplete list: the playlist is unchanged. */
    data object Incomplete : RefreshResult

    data class Failed(val kind: Loadable.Error.Kind) : RefreshResult
}

/**
 * Playlists saved from YouTube and how they follow it (REWRITE §3.8): saving with a link mode,
 * refreshing by it. A refresh is applied only when YouTube gave the whole list.
 */
object PlaylistLinks {
    /** Every track of [browseId]; a failure if any page fails. */
    suspend fun fetchWhole(browseId: String, params: String? = null): Result<WholePlaylist> =
        withContext(Dispatchers.IO) {
            runCatching {
                val page = Innertube.playlistPage(BrowseBody(browseId = browseId, params = params))
                    ?.getOrThrow()
                    ?: error("No playlist page")

                val songs = page.songsPage?.items.orEmpty().toMutableList()
                var continuation = page.songsPage?.continuation
                while (continuation != null) {
                    val next = Innertube.playlistPage(ContinuationBody(continuation = continuation))
                        ?.getOrElse { throw IncompletePlaylistException(it) }
                        ?: throw IncompletePlaylistException(null)
                    songs += next.items.orEmpty()
                    continuation = next.continuation?.takeIf { it != continuation }
                }

                WholePlaylist(page = page, songs = songs.distinctBy { it.key })
            }
        }

    /**
     * Saves [whole] into the Library as [name], following YouTube by [mode] ([YtLinkMode.Off]: a
     * copy that remembers where it came from). Returns the id of the new playlist.
     */
    suspend fun save(browseId: String, name: String, whole: WholePlaylist, mode: YtLinkMode): Long =
        withContext(Dispatchers.IO) {
            Database.internal.runInTransaction<Long> {
                val id = Database.insert(
                    Playlist(
                        name = name,
                        browseId = browseId,
                        thumbnail = whole.page.thumbnail?.url,
                        ytLinkMode = mode,
                        ytSyncedAt = System.currentTimeMillis(),
                        ytSnapshot = whole.songs.snapshot()
                    )
                )
                whole.songs.forEachIndexed { position, song ->
                    Database.insert(song.asMediaItem)
                    Database.insert(SongPlaylistMap(songId = song.key, playlistId = id, position = position))
                }
                id
            }
        }

    /** Refreshes [playlist] from YouTube by its link mode; a playlist that isn't linked is left alone. */
    suspend fun refresh(playlist: Playlist): RefreshResult {
        val browseId = playlist.browseId
        val mode = playlist.ytLinkMode
        if (browseId == null || mode == null || mode == YtLinkMode.Off) return RefreshResult.Failed(Loadable.Error.Kind.Unknown)

        val whole = fetchWhole(browseId).getOrElse { error ->
            return if (error is IncompletePlaylistException) RefreshResult.Incomplete
            else RefreshResult.Failed(classify(error))
        }

        return withContext(Dispatchers.IO) {
            Database.internal.runInTransaction<RefreshResult> {
                val now = System.currentTimeMillis()
                val present = Database.playlistSongIds(playlist.id)

                when (mode) {
                    YtLinkMode.Mirror -> {
                        Database.clearPlaylist(playlist.id)
                        whole.songs.forEachIndexed { position, song ->
                            Database.insert(song.asMediaItem)
                            Database.insert(SongPlaylistMap(songId = song.key, playlistId = playlist.id, position = position))
                        }
                        val ids = whole.songs.map { it.key }.toSet()
                        Database.setYtSynced(playlist.id, now, whole.songs.snapshot())
                        RefreshResult.Updated(
                            added = ids.count { it !in present },
                            removed = present.count { it !in ids }
                        )
                    }

                    else -> {
                        val snapshot = playlist.ytSnapshot?.lines()?.toSet()
                        Database.setYtSynced(playlist.id, now, whole.songs.snapshot())

                        // Without a snapshot the deleted tracks can't be told from the new ones:
                        // remember the list, add from the next refresh on (§4.3, п. 8)
                        if (snapshot == null) return@runInTransaction RefreshResult.SnapshotTaken

                        val known = present.toSet()
                        val new = whole.songs.filter { it.key !in snapshot && it.key !in known }
                        var position = Database.songCountOf(playlist.id)
                        new.forEach { song ->
                            Database.insert(song.asMediaItem)
                            Database.insert(SongPlaylistMap(songId = song.key, playlistId = playlist.id, position = position++))
                        }
                        RefreshResult.Updated(added = new.size, removed = 0)
                    }
                }
            }
        }
    }

    private fun List<Innertube.SongItem>.snapshot() = joinToString("\n") { it.key }
}
