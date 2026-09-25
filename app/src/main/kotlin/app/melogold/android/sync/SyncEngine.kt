package app.melogold.android.sync

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.melogold.android.Database
import app.melogold.android.data.NetworkMonitor
import app.melogold.android.internal
import app.melogold.android.models.Album
import app.melogold.android.models.Artist
import app.melogold.android.models.Playlist
import app.melogold.android.models.Song
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.models.SyncState
import app.melogold.android.models.SyncedBookmark
import app.melogold.android.models.SyncedLike
import app.melogold.android.models.SyncedPlaylist
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.MergePlanInput
import app.melogold.android.sync.api.MergePlanRequest
import app.melogold.android.sync.api.OpResult
import app.melogold.android.sync.api.SyncRequest
import app.melogold.android.sync.api.SyncResponse
import app.melogold.android.sync.api.TrackDto
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "SyncEngine"
private const val MAX_OPS = 500
private const val LOCAL_CHANGE_DELAY_MS = 2_000L
private const val MAX_BACKOFF_MS = 5 * 60_000L
private const val STREAM_LIBRARY = "library"

private const val KEY_BINDING = "binding"
private const val KEY_CURSOR = "cursor"
private const val KEY_MERGE = "needsMerge"
private const val KEY_LAST_SYNC = "lastSyncAt"

/** What the sync is doing, for Settings. */
sealed interface SyncStatus {
    /** No account. */
    data object Off : SyncStatus

    data class Idle(val lastSyncAt: Long?) : SyncStatus

    data object Syncing : SyncStatus

    data class Failed(val offline: Boolean, val lastSyncAt: Long?) : SyncStatus
}

/** One op of `POST /sync` (API §4.8) and what it is about, for its result. */
private class Op(val kind: String, val key: String, val json: JsonObject)

@Serializable
private data class LiveEvent(val id: String, val type: String)

/**
 * Keeps the library of this device and of the account on the Melogold server the same (API §4.8): the Favorites,
 * the playlists with their order, the saved albums and artists.
 *
 * **The snapshot variant of DESIGN §3.13** (REWRITE §4.12a): instead of an outbox written with every change, the
 * engine compares the library with what the server had after the last sync (`Synced*` tables) and sends the
 * difference as ops; then it applies what the server sends back, which updates both the library and the snapshot.
 * The contract with the server is the same; every op it sends is idempotent by state, so a sync cut short is simply
 * done again. Every op carries `base`, the cursor of the snapshot it was computed against, so it wins over what this
 * device had seen and loses to newer changes of other devices by time (API §4.8, DESIGN §3.4). The server answers with
 * the current rows of every key an op touched, whatever its result: a change that lost comes back as the winner.
 *
 * Syncs run one at a time: after a change of the library (2 s later), when the app comes to the front, on
 * `sync.changed` of the live stream (API §6) and on request.
 */
@OptIn(FlowPreview::class)
class SyncEngine(private val account: Account, private val network: NetworkMonitor, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    private val mutableDevicesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The cursor the ops being built were computed against (their `base`); set by [buildOps]. */
    private var base: String? = null

    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    /** The device list changed on the server (`devices.updated`): screens showing it read it again. */
    val devicesChanged: SharedFlow<Unit> = mutableDevicesChanged

    fun start() = scope.launch {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()

        combine(account.state, foreground, network.isOnline) { state, front, online ->
            Triple(state is AccountState.SignedIn, front, online)
        }
            .distinctUntilChanged()
            .collectLatest { (signedIn, front, online) ->
                if (!signedIn) {
                    mutableStatus.value = SyncStatus.Off
                    return@collectLatest
                }
                if (mutableStatus.value == SyncStatus.Off) mutableStatus.value = SyncStatus.Idle(lastSyncAt())
                when {
                    // Nothing to try without a network; when it comes back, this runs again at once
                    !online -> mutableStatus.value = SyncStatus.Failed(offline = true, lastSyncAt = lastSyncAt())
                    front -> coroutineScope {
                        launch { sync(force = true) }
                        launch { followLocalChanges() }
                        launch { followLiveEvents() }
                    }

                    else -> sync(force = false)
                }
            }
    }

    /** Syncs now; [force] also asks the server when nothing changed here. */
    suspend fun sync(force: Boolean = true): Result<Unit> = mutex.withLock {
        if (account.session == null) return@withLock Result.success(Unit)
        val last = lastSyncAt()
        mutableStatus.value = SyncStatus.Syncing
        runCatching { syncOnce(force) }
            .onSuccess { mutableStatus.value = SyncStatus.Idle(lastSyncAt()) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Sync failed", error)
                mutableStatus.value = if (account.session == null) SyncStatus.Off
                else SyncStatus.Failed(offline = (error as? ApiException)?.isNetwork == true, lastSyncAt = last)
            }
    }

    /** A change of the Favorites, playlists or saved albums and artists is sent 2 s later. */
    private suspend fun followLocalChanges() {
        Database.libraryFingerprint()
            .distinctUntilChanged()
            .drop(1)
            .debounce(LOCAL_CHANGE_DELAY_MS)
            .collect { sync(force = false) }
    }

    private suspend fun syncOnce(force: Boolean) {
        val session = account.session ?: return
        val binding = "${session.serverId}:${session.userId}"

        withContext(Dispatchers.IO) {
            if (Database.syncState(KEY_BINDING) != binding) Database.internal.runInTransaction {
                // Another account or server: nothing here was synced with it, everything is sent (and merged)
                Database.clearSyncedLikes()
                Database.clearSyncedPlaylists()
                Database.clearSyncedBookmarks()
                Database.clearPlaylistSyncIds()
                Database.clearSortKeys()
                Database.clearSyncState()
                Database.setSyncState(SyncState(KEY_BINDING, binding))
                Database.setSyncState(SyncState(KEY_MERGE, "1"))
            }
        }
        if (withContext(Dispatchers.IO) { Database.syncState(KEY_MERGE) } == "1") planMerge()

        var ops = withContext(Dispatchers.IO) { Database.internal.runInTransaction<List<Op>> { buildOps() } }
        if (ops.isEmpty() && !force) return

        var cursor = withContext(Dispatchers.IO) { Database.syncState(KEY_CURSOR) }.orEmpty()
        var restarted = false
        while (true) {
            val batch = ops.take(MAX_OPS)
            val response = try {
                account.authorized { api, token ->
                    api.sync(token, SyncRequest(cursor = cursor, ops = batch.map { it.json }, streams = listOf(STREAM_LIBRARY)))
                }
            } catch (e: ApiException) {
                // The server was restored or forgot this cursor: read everything again (API §4.8, 410)
                if (e.status == 410 && !restarted) {
                    restarted = true
                    cursor = ""
                    continue
                }
                throw e
            }
            ops = ops.drop(batch.size)
            withContext(Dispatchers.IO) {
                Database.internal.runInTransaction {
                    applyResults(batch, response.results)
                    applyRows(response)
                    Database.setSyncState(SyncState(KEY_CURSOR, response.cursor))
                }
            }
            cursor = response.cursor
            if (!response.hasMore && ops.isEmpty()) break
        }

        withContext(Dispatchers.IO) {
            Database.setSyncState(SyncState(KEY_LAST_SYNC, System.currentTimeMillis().toString()))
            Database.setSyncState(SyncState(KEY_MERGE, "0"))
        }
    }

    /** The first sync with an account: local playlists take over their server twins instead of doubling them. */
    private suspend fun planMerge() {
        val locals = withContext(Dispatchers.IO) { Database.playlistsNow() }
        if (locals.isEmpty()) return
        val plan = account.authorized { api, token ->
            api.mergePlan(
                token,
                MergePlanRequest(
                    locals.map { MergePlanInput(localKey = it.id.toString(), name = it.name.take(NAME_MAX).ifBlank { "—" }, browseId = it.browseId) }
                )
            )
        }
        withContext(Dispatchers.IO) {
            Database.internal.runInTransaction {
                for (entry in plan.plan) {
                    val id = entry.localKey.toLongOrNull() ?: continue
                    if (entry.action == "merge" || entry.action == "create") Database.setPlaylistSyncId(id, entry.playlistId)
                }
            }
        }
    }

    /** What changed here since the last sync, as ops. Runs in a transaction: new playlists get their `syncId` here. */
    private fun buildOps(): List<Op> {
        val ops = mutableListOf<Op>()
        val now = System.currentTimeMillis()
        base = Database.syncState(KEY_CURSOR)?.takeIf { it.isNotEmpty() }

        // Favorites
        val liked = Database.likedSongsNow().associateBy { it.id }
        val syncedLikes = Database.syncedLikes().toSet()
        liked.values.filter { it.id !in syncedLikes }.forEach { song ->
            ops += op("like.set", "like:${song.id}", at = song.likedAt ?: now) {
                put("videoId", song.id)
                put("liked", true)
                song.likedAt?.let { put("likedAt", it.isoTime()) }
                putTracks(listOf(song))
            }
        }
        syncedLikes.filter { it !in liked }.forEach { videoId ->
            ops += op("like.set", "like:$videoId", at = now) {
                put("videoId", videoId)
                put("liked", false)
            }
        }

        // Playlists
        val synced = Database.syncedPlaylists().associateBy { it.syncId }
        val playlists = Database.playlistsNow()
        for (playlist in playlists) {
            val songs = Database.playlistMapsNow(playlist.id)
                .map { it.songId }
                .filterNot { it.startsWith(LOCAL_KEY_PREFIX) }
            val syncId = playlist.syncId
            val previous = syncId?.let { synced[it] }
            when {
                syncId == null -> {
                    val newId = UUID.randomUUID().toString()
                    Database.setPlaylistSyncId(playlist.id, newId)
                    ops += playlistOp("playlist.create", newId, now, playlist, songs)
                }

                // Taken over from the merge plan, or created and not confirmed yet: import merges
                previous == null -> ops += playlistOp("playlist.import", syncId, now, playlist, songs)

                else -> {
                    if (previous.name != playlist.name || previous.thumbnailUrl != playlist.thumbnail) {
                        ops += op("playlist.update", "pl:$syncId", at = now) {
                            put("playlistId", syncId)
                            put("name", playlist.name.take(NAME_MAX).ifBlank { "—" })
                            playlist.thumbnail?.let { put("thumbnailUrl", it) }
                        }
                    }
                    if (previous.items != songs) ops += itemOps(syncId, playlistItemChanges(previous.items, songs), now)
                }
            }
        }
        val present = playlists.mapNotNull { it.syncId }.toSet()
        synced.keys.filter { it !in present }.forEach { syncId ->
            ops += op("playlist.delete", "pl:$syncId", at = now) { put("playlistId", syncId) }
        }

        // Saved albums and artists
        val bookmarks = Database.bookmarkedAlbumsNow().associate { ("album" to it.id) to it } +
            Database.bookmarkedArtistsNow().associate { ("artist" to it.id) to it }
        val syncedBookmarks = Database.syncedBookmarks().map { it.type to it.browseId }.toSet()
        bookmarks.filterKeys { it !in syncedBookmarks }.forEach { (key, item) ->
            val (type, browseId) = key
            ops += op("bookmark.set", "bm:$type:$browseId", at = now) {
                put("type", type)
                put("browseId", browseId)
                put("bookmarked", true)
                when (item) {
                    is Album -> {
                        item.bookmarkedAt?.let { put("bookmarkedAt", it.isoTime()) }
                        item.title?.let { put("title", it) }
                        item.authorsText?.let { put("subtitle", it) }
                        item.thumbnailUrl?.let { put("thumbnailUrl", it) }
                        item.year?.let { put("year", it) }
                    }

                    is Artist -> {
                        item.bookmarkedAt?.let { put("bookmarkedAt", it.isoTime()) }
                        item.name?.let { put("title", it) }
                        item.thumbnailUrl?.let { put("thumbnailUrl", it) }
                    }
                }
            }
        }
        syncedBookmarks.filter { it !in bookmarks }.forEach { (type, browseId) ->
            ops += op("bookmark.set", "bm:$type:$browseId", at = now) {
                put("type", type)
                put("browseId", browseId)
                put("bookmarked", false)
            }
        }
        return ops
    }

    /** The item ops of one playlist (API §4.8); the tracks it adds carry their metadata. */
    private fun itemOps(syncId: String, changes: List<ItemChange>, now: Long) = changes.map { change ->
        when (change) {
            is ItemChange.Remove -> op("playlist.item.remove", "pl:$syncId", at = now) {
                put("playlistId", syncId)
                put("videoId", change.videoId)
            }

            is ItemChange.Add -> op("playlist.items.add", "pl:$syncId", at = now) {
                put("playlistId", syncId)
                put("videoIds", buildJsonArray { change.videoIds.forEach { add(JsonPrimitive(it)) } })
                change.after?.let { put("after", it) }
                change.before?.let { put("before", it) }
                putTracks(change.videoIds.mapNotNull { Database.songNow(it) })
            }

            is ItemChange.Move -> op("playlist.item.move", "pl:$syncId", at = now) {
                put("playlistId", syncId)
                put("videoId", change.videoId)
                change.after?.let { put("after", it) }
                change.before?.let { put("before", it) }
            }
        }
    }

    private fun playlistOp(kind: String, syncId: String, now: Long, playlist: Playlist, songs: List<String>) =
        op(kind, "pl:$syncId", at = now) {
            put("playlistId", syncId)
            put("name", playlist.name.take(NAME_MAX).ifBlank { "—" })
            playlist.browseId?.let { put("browseId", it) }
            playlist.thumbnail?.let { put("thumbnailUrl", it) }
            put("videoIds", buildJsonArray { songs.forEach { add(JsonPrimitive(it)) } })
            putTracks(songs.mapNotNull { Database.songNow(it) })
        }

    private fun op(kind: String, key: String, at: Long, fields: JsonObjectBuilder.() -> Unit) = Op(
        kind = kind,
        key = key,
        json = buildJsonObject {
            put("opId", UUID.randomUUID().toString())
            put("kind", kind)
            put("at", at.isoTime())
            base?.let { put("base", it) }
            fields()
        }
    )

    /** Metadata of the tracks an op mentions (API §4.8 `tracks`), so the other devices can show them. */
    private fun JsonObjectBuilder.putTracks(songs: List<Song>) {
        if (songs.isEmpty()) return
        put(
            "tracks",
            buildJsonArray {
                songs.forEach { song ->
                    add(
                        buildJsonObject {
                            put("videoId", song.id)
                            put("title", song.title)
                            song.artistsText?.let { put("artistsText", it) }
                            song.durationText?.let { put("durationText", it) }
                            song.thumbnailUrl?.let { put("thumbnailUrl", it) }
                            if (song.explicit) put("explicit", true)
                        }
                    )
                }
            }
        )
    }

    /** A playlist the server moved to a recovery copy (`redirected`) follows it here. */
    private fun applyResults(batch: List<Op>, results: List<OpResult>) {
        batch.zip(results).forEach { (op, result) ->
            if (result.status == "redirected" && op.key.startsWith("pl:")) {
                val old = op.key.removePrefix("pl:")
                val newId = result.playlistId ?: return@forEach
                Database.playlistBySyncId(old)?.let {
                    Database.setPlaylistSyncId(it.id, newId)
                    // The copy has only what the op carried: the other tracks here are sent to it as new
                    Database.clearSortKeys(it.id)
                }
                Database.deleteSyncedPlaylist(old)
            }
        }
    }

    /**
     * What the server sends back (API §4.8, in its order: tracks, playlists, items, likes, bookmarks): the library
     * and the snapshot become what the server has.
     */
    private fun applyRows(response: SyncResponse) {
        response.tracks.forEach { track -> ensureSong(track.videoId, track) }

        val touched = mutableSetOf<Long>()
        response.playlists.forEach { row ->
            val local = Database.playlistBySyncId(row.id)
            when {
                row.deleted -> {
                    local?.let(Database::delete)
                    Database.deleteSyncedPlaylist(row.id)
                }

                local == null -> {
                    val id = Database.insert(
                        Playlist(name = row.name, browseId = row.browseId, thumbnail = row.thumbnailUrl, syncId = row.id)
                    )
                    Database.upsertSyncedPlaylist(SyncedPlaylist(row.id, row.name, row.thumbnailUrl, ""))
                    touched += id
                }

                else -> {
                    if (local.name != row.name || local.thumbnail != row.thumbnailUrl) {
                        Database.update(local.copy(name = row.name, thumbnail = row.thumbnailUrl))
                    }
                    val previous = Database.syncedPlaylists().firstOrNull { it.syncId == row.id }
                    Database.upsertSyncedPlaylist(SyncedPlaylist(row.id, row.name, row.thumbnailUrl, previous?.videoIds.orEmpty()))
                }
            }
        }

        response.items.groupBy { it.playlistId }.forEach { (syncId, items) ->
            val playlist = Database.playlistBySyncId(syncId) ?: return@forEach
            items.forEach { item ->
                if (item.present) {
                    ensureSong(item.videoId, null)
                    Database.upsertSongPlaylistMap(SongPlaylistMap(item.videoId, playlist.id, Int.MAX_VALUE, item.sortKey))
                } else Database.deleteSongPlaylistMap(playlist.id, item.videoId)
            }
            touched += playlist.id
        }
        touched.forEach(::reorder)

        response.likes.forEach { row ->
            ensureSong(row.videoId, null)
            Database.like(row.videoId, if (row.liked) row.likedAt?.epochMs() ?: System.currentTimeMillis() else null)
            if (row.liked) Database.upsertSyncedLikes(listOf(SyncedLike(row.videoId)))
            else Database.deleteSyncedLikes(listOf(row.videoId))
        }

        response.bookmarks.forEach { row ->
            val at = if (row.bookmarked) row.bookmarkedAt?.epochMs() ?: System.currentTimeMillis() else null
            when (row.type) {
                "album" -> {
                    if (row.bookmarked) Database.insertIfMissing(
                        Album(id = row.browseId, title = row.title, thumbnailUrl = row.thumbnailUrl, year = row.year, authorsText = row.subtitle)
                    )
                    Database.setAlbumBookmark(row.browseId, at)
                }

                "artist" -> {
                    if (row.bookmarked) Database.insertIfMissing(Artist(id = row.browseId, name = row.title, thumbnailUrl = row.thumbnailUrl))
                    Database.setArtistBookmark(row.browseId, at)
                }

                else -> return@forEach
            }
            if (row.bookmarked) Database.upsertSyncedBookmark(SyncedBookmark(row.type, row.browseId))
            else Database.deleteSyncedBookmark(row.type, row.browseId)
        }
    }

    /** Tracks with a server order key in its order, then those without one (local files) as they were. */
    private fun reorder(playlistId: Long) {
        val maps = Database.playlistMapsNow(playlistId)
        val ordered = maps.filter { it.sortKey != null }.sortedWith(compareBy({ it.sortKey }, { it.songId })) +
            maps.filter { it.sortKey == null }
        ordered.forEachIndexed { index, map -> if (map.position != index) Database.setPosition(playlistId, map.songId, index) }

        val playlist = Database.playlistsNow().firstOrNull { it.id == playlistId } ?: return
        val syncId = playlist.syncId ?: return
        Database.upsertSyncedPlaylist(
            SyncedPlaylist(
                syncId = syncId,
                name = playlist.name,
                thumbnailUrl = playlist.thumbnail,
                // What the server has: a track added here and not sent yet has no key
                videoIds = ordered.filter { it.sortKey != null }.joinToString("\n") { it.songId }
            )
        )
    }

    /** The row of a track the server mentions: its metadata when new, a placeholder named by its id otherwise. */
    private fun ensureSong(videoId: String, track: TrackDto?) {
        val existing = Database.songNow(videoId)
        if (existing != null) {
            // A placeholder of an earlier sync learns the real title
            if (track != null && !track.metadataStub && track.title.isNotBlank() && existing.title == existing.id) {
                Database.update(existing.copy(title = track.title, artistsText = track.artistsText, thumbnailUrl = track.thumbnailUrl))
            }
            return
        }
        Database.insert(
            Song(
                id = videoId,
                title = track?.title?.takeIf { it.isNotBlank() && !track.metadataStub } ?: videoId,
                artistsText = track?.artistsText,
                durationText = track?.durationText,
                thumbnailUrl = track?.thumbnailUrl,
                explicit = track?.explicit == true
            )
        )
    }

    /** The live stream (API §6) while the app is in front: syncs on `sync.changed`, signs out on `session.invalidated`. */
    private suspend fun followLiveEvents() {
        var backoff = 0L
        while (scope.isActive && account.session != null) {
            val started = System.currentTimeMillis()
            try {
                account.authorized { api, token ->
                    api.streamClient.prepareGet(api.eventsUrl) {
                        bearerAuth(token)
                        header("Accept", "text/event-stream")
                    }.execute { response ->
                        if (!response.status.isSuccess()) throw api.errorOf(response)
                        val channel = response.bodyAsChannel()
                        while (true) {
                            val line = channel.readLine() ?: break
                            if (line.startsWith("data: ")) onLiveEvent(line.removePrefix("data: "))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.i(TAG, "Live stream closed: ${e.message}")
            }
            if (account.session == null) return
            // A stream that lasted is not a failure: the server closes it when the token expires
            backoff = if (System.currentTimeMillis() - started > MAX_BACKOFF_MS) 0 else (backoff * 2).coerceIn(1_000L, MAX_BACKOFF_MS)
            delay(backoff + Random.nextLong(backoff / 4 + 1))
        }
    }

    private fun onLiveEvent(data: String) {
        val event = runCatching { json.decodeFromString<LiveEvent>(data) }.getOrNull() ?: return
        when (event.type) {
            "system.connected", "sync.changed" -> scope.launch { sync(force = true) }
            "devices.updated" -> mutableDevicesChanged.tryEmit(Unit)
            "session.invalidated" -> account.endSession()
        }
    }

    private suspend fun lastSyncAt(): Long? = withContext(Dispatchers.IO) { Database.syncState(KEY_LAST_SYNC)?.toLongOrNull() }

    private companion object {
        const val NAME_MAX = 200
    }
}
