package app.melogold.android.sync

import android.util.Log
import app.melogold.android.Database
import app.melogold.android.internal
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsSource
import app.melogold.android.models.SyncState
import app.melogold.android.models.SyncedLyrics
import app.melogold.android.models.isOwn
import app.melogold.android.sync.api.ApiException
import app.melogold.android.sync.api.LyricsChangesRequest
import app.melogold.android.sync.api.LyricsPut
import app.melogold.android.sync.api.LyricsResponse
import app.melogold.android.sync.api.LyricsText
import app.melogold.android.sync.api.MyLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

private const val TAG = "LyricsSync"
private const val KEY_LYRICS_REV = "lyricsRev"
private const val SUPPORT_TTL_MS = 60 * 60_000L
private const val FETCH_TIMEOUT_MS = 5_000L

/**
 * The user's own lyrics on the Melogold server (API §4.10) and the ones other users share.
 *
 * Own lyrics are rows of `Lyrics` with a side the user made or imported ([isOwn]). Like the library (REWRITE §4.12a),
 * they are compared with a snapshot of what the server had after the last sync ([SyncedLyrics]): new and changed ones
 * go up with `PUT`, ones that are no longer own with `DELETE`; then the changes of the other devices come down
 * (`POST /auth/me/lyrics/changes` after the last `rev`). A tombstone removes the lyrics here only if they did not change
 * since the last sync; otherwise the local version is sent next time and wins as the later one.
 *
 * A track not in the library of this device yet keeps its lyrics in the snapshot: [fromServer] finds them when it is
 * first played.
 */
class LyricsSync(private val account: Account) {
    private var support: Triple<String, Boolean, Long>? = null

    /** Whether the server of the account keeps lyrics (`features.lyrics`); asked at most once an hour. */
    suspend fun supported(): Boolean {
        val url = account.session?.serverUrl ?: return false
        support?.let { (cachedUrl, value, at) ->
            if (cachedUrl == url && System.currentTimeMillis() - at < SUPPORT_TTL_MS) return value
        }
        val value = account.api(url).serverInfo().features.lyrics != null
        support = Triple(url, value, System.currentTimeMillis())
        return value
    }

    /** One round: own changes up, then the other devices' changes down. [force] also asks when nothing changed here. */
    suspend fun sync(force: Boolean) {
        if (!supported()) return
        val uploads = withContext(Dispatchers.IO) { pendingUploads() }
        if (uploads.isEmpty() && !force) return
        uploads.forEach { upload(it) }
        pull()
    }

    /**
     * The lyrics of [videoId] the server has for this user: their own version, else the one other users share.
     * `null` without an account, on a server without lyrics or when the server does not answer in time.
     */
    suspend fun fromServer(videoId: String): LyricsResponse? {
        if (account.session == null) return null
        return runCatching {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                if (!supported()) null else account.authorized { api, token -> api.lyrics(token, videoId) }
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    /** The version pulled into the snapshot for a track this device has not stored lyrics of yet. */
    fun ownFromSnapshot(videoId: String): Lyrics? = Database.syncedLyricsNow(videoId)?.toLyrics()

    private sealed interface Upload {
        val videoId: String

        class Put(override val videoId: String, val content: Content) : Upload
        class Delete(override val videoId: String) : Upload
    }

    private fun pendingUploads(): List<Upload> {
        val own = Database.ownLyricsNow().mapNotNull { row -> row.ownContent()?.let { row.songId to it } }.toMap()
        val snapshot = Database.syncedLyricsNow().associateBy { it.videoId }
        val puts = own.filter { (videoId, content) -> snapshot[videoId]?.hash != content.hash }
            .map { (videoId, content) -> Upload.Put(videoId, content) }
        val deletes = snapshot.keys.filter { it !in own }.map { Upload.Delete(it) }
        return puts + deletes
    }

    private suspend fun upload(upload: Upload) {
        when (upload) {
            is Upload.Put -> {
                val mine = try {
                    account.authorized { api, token -> api.putLyrics(token, upload.videoId, upload.content.toPut()) }
                } catch (e: ApiException) {
                    // Too long or refused as it is: kept as sent, so it is not tried again until it changes
                    if (e.status != 400 && e.status != 413) throw e
                    Log.w(TAG, "The server refused the lyrics of ${upload.videoId}: ${e.code}")
                    null
                }
                withContext(Dispatchers.IO) {
                    Database.upsert(upload.content.toSnapshot(upload.videoId, rev = mine?.rev ?: 0))
                }
            }

            is Upload.Delete -> {
                account.authorized { api, token -> api.deleteLyrics(token, upload.videoId) }
                withContext(Dispatchers.IO) { Database.deleteSyncedLyrics(upload.videoId) }
            }
        }
    }

    private suspend fun pull() {
        var after = withContext(Dispatchers.IO) { Database.syncState(KEY_LYRICS_REV)?.toLongOrNull() } ?: 0L
        do {
            val page = account.authorized { api, token ->
                api.lyricsChanges(token, LyricsChangesRequest(after = after))
            }
            withContext(Dispatchers.IO) {
                Database.internal.runInTransaction {
                    page.items.forEach(::apply)
                    Database.setSyncState(SyncState(KEY_LYRICS_REV, page.rev.toString()))
                }
            }
            after = page.rev
        } while (page.more)
    }

    /** One change from the server; runs in a transaction. */
    private fun apply(version: MyLyrics) {
        val local = Database.lyricsNow(version.videoId)
        val snapshot = Database.syncedLyricsNow(version.videoId)
        val localHash = local?.ownContent()?.hash
        val changedHere = localHash != null && localHash != snapshot?.hash
        val text = version.text

        if (version.deleted || text == null) {
            if (snapshot == null) return
            Database.deleteSyncedLyrics(version.videoId)
            // Removed elsewhere: gone here too, unless changed here since (then it goes up again next time)
            if (!changedHere && local != null && localHash != null) Database.deleteLyrics(version.videoId)
            return
        }

        val content = Content.of(text)
        Database.upsert(content.toSnapshot(version.videoId, rev = version.rev))
        if (changedHere) return
        // Only tracks this device knows get a row now; the others find it in the snapshot when first played
        if (Database.songNow(version.videoId) != null) Database.upsert(content.toLyrics(version.videoId))
    }

    /** What the server keeps of own lyrics, with sources in the words of API §4.10. */
    private data class Content(
        val plain: String?,
        val plainSource: String?,
        val synced: String?,
        val syncedSource: String?,
        val startTimeMs: Long?
    ) {
        val syncedFormat get() = synced?.let { if (it.trimStart().startsWith("<")) "ttml" else "lrc" }

        /** Of the fields both sides keep the same way: a row written from a version hashes like the version. */
        val hash: String by lazy {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(plain, plainSource, synced, syncedSource, startTimeMs?.toString())
                .forEach {
                    digest.update((it ?: "\u0001").toByteArray())
                    digest.update(0)
                }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun toPut() = LyricsPut(
            plain = plain,
            plainSource = plainSource?.takeIf { plain != null },
            synced = synced,
            syncedFormat = syncedFormat,
            syncedSource = syncedSource?.takeIf { synced != null },
            startTimeMs = startTimeMs
        )

        fun toSnapshot(videoId: String, rev: Long) = SyncedLyrics(
            videoId = videoId,
            rev = rev,
            hash = hash,
            plain = plain,
            plainSource = plainSource,
            synced = synced,
            syncedFormat = syncedFormat,
            syncedSource = syncedSource,
            startTimeMs = startTimeMs
        )

        fun toLyrics(videoId: String) = Lyrics(
            songId = videoId,
            fixed = plain,
            synced = synced,
            startTime = startTimeMs,
            fixedSource = plainSource.toSource(),
            syncedSource = syncedSource.toSource()
        )

        companion object {
            fun of(text: LyricsText) = Content(
                plain = text.plain?.takeIf { it.isNotEmpty() },
                plainSource = text.plainSource.toSource()?.wire,
                synced = text.synced?.takeIf { it.isNotEmpty() },
                syncedSource = text.syncedSource.toSource()?.wire,
                startTimeMs = text.startTimeMs
            )
        }
    }

    private fun SyncedLyrics.toLyrics() =
        Content(plain, plainSource, synced, syncedSource, startTimeMs).toLyrics(videoId)

    /** The part of a row the server keeps, or `null` when the row holds no lyrics of the user's own. */
    private fun Lyrics.ownContent(): Content? {
        if (!fixedSource.isOwn && !syncedSource.isOwn) return null
        val plain = fixed?.takeIf { it.isNotEmpty() }
        val synced = synced?.takeIf { it.isNotEmpty() }
        if (plain == null && synced == null) return null
        return Content(
            plain = plain,
            plainSource = fixedSource?.wire?.takeIf { plain != null },
            synced = synced,
            syncedSource = syncedSource?.wire?.takeIf { synced != null },
            startTimeMs = startTime
        )
    }
}

/** The word of API §4.10 for a source; the Melogold community is never sent as the user's own. */
private val LyricsSource.wire: String?
    get() = when (this) {
        LyricsSource.User -> "user"
        LyricsSource.File -> "file"
        LyricsSource.YouTubeMusic -> "youtube_music"
        LyricsSource.LrcLib -> "lrclib"
        LyricsSource.KuGou -> "kugou"
        LyricsSource.Melogold -> null
    }

private fun String?.toSource(): LyricsSource? = when (this) {
    "user" -> LyricsSource.User
    "file" -> LyricsSource.File
    "youtube_music" -> LyricsSource.YouTubeMusic
    "lrclib" -> LyricsSource.LrcLib
    "kugou" -> LyricsSource.KuGou
    else -> null
}
