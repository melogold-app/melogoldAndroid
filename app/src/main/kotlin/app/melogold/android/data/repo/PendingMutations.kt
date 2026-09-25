package app.melogold.android.data.repo

import android.util.Log
import app.melogold.android.Database
import app.melogold.android.Dependencies
import app.melogold.android.internal
import app.melogold.android.models.HistoryForget
import app.melogold.android.models.Playlist
import app.melogold.android.models.PlaylistPreview
import app.melogold.android.models.Song
import app.melogold.android.models.SongPlaylistMap
import app.melogold.android.models.SongWithDownload
import app.melogold.android.models.SongWithLastPlayed
import app.melogold.android.models.SongWithPlayTime
import app.melogold.android.models.TrackDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PendingMutations"

/** How long "Undo" is offered before a deletion reaches Room (REWRITE §3.11.9). */
const val UNDO_TIMEOUT_MS = 5_000L

/**
 * A deletion that waits for its "Undo" (REWRITE §3.11.9). Until it is committed Room is unchanged
 * and the lists only hide what it deletes, so "Undo" just drops it.
 */
sealed interface PendingMutation {
    /** Writes the deletion to Room. Runs once, off the main thread. */
    fun commit()

    data class DeletePlaylist(val playlistId: Long) : PendingMutation {
        override fun commit() = Database.deletePlaylist(playlistId)
    }

    /** Takes [songId] out of [playlistId] from wherever it is by then, and closes the gap. */
    data class RemoveFromPlaylist(val playlistId: Long, val songId: String) : PendingMutation {
        override fun commit() = Database.internal.runInTransaction {
            Database.positionIn(songId, playlistId)?.let { position ->
                Database.move(playlistId, position, Int.MAX_VALUE)
                Database.delete(SongPlaylistMap(songId, playlistId, Int.MAX_VALUE))
            }
        }
    }

    /**
     * "Remove from history": the [plays] of [songId] up to [before], and its listening time. Likes
     * and playlists stay.
     */
    data class ForgetTrack(val songId: String, val before: Long, val plays: Int) : PendingMutation {
        override fun commit() = Database.internal.runInTransaction {
            Database.deleteEventsOf(songId, before)
            Database.setTotalPlayTime(songId, 0L)
            // On every device of the account too (API §4.8 history.forget)
            Database.upsert(HistoryForget(songId, eventsBefore = before, resetTotal = true))
        }
    }

    /** "Clear history": every play up to [before]. */
    data class ClearHistory(val before: Long) : PendingMutation {
        override fun commit() = Database.internal.runInTransaction {
            Database.deleteEventsBefore(before)
            // On every device of the account too (API §4.8 history.clear)
            Database.upsert(HistoryForget(HistoryForget.ALL, eventsBefore = before, resetTotal = false))
        }
    }

    /** "Don't show this track"; the row of the track must exist. */
    data class Hide(val songId: String) : PendingMutation {
        override fun commit() = Database.hide(songId)
    }

    /** "Remove download": the bytes of [videoId] and its row. */
    data class RemoveDownload(val videoId: String) : PendingMutation {
        override fun commit() = Dependencies.application.container.downloads.remove(videoId)
    }
}

/**
 * The deletions waiting for their "Undo" (REWRITE §3.11.9). Every list that shows what one of them
 * deletes hides it at once (the overlays below). Room changes on [commit], in the app's [scope]:
 * when the snackbar goes away, when a newer one replaces it, or for all of them when the app goes
 * to the background ([commitAll]). A crash before that loses the deletion, never the data.
 */
class PendingMutationStore(
    private val scope: CoroutineScope,
    private val write: suspend (PendingMutation) -> Unit = { it.commit() }
) {
    private val hidden = MutableStateFlow<List<PendingMutation>>(emptyList())
    private val lock = Any()

    // Being written: too late for "Undo"
    private val committing = mutableSetOf<PendingMutation>()

    /** What the lists hide: the deletions waiting and those being written. */
    val pending: StateFlow<List<PendingMutation>> = hidden.asStateFlow()

    /** Hides what [mutation] deletes until it is committed or undone. */
    fun add(mutation: PendingMutation) = hidden.update { if (mutation in it) it else it + mutation }

    /** "Undo": true when [mutation] was still waiting; what it deletes shows again. */
    fun undo(mutation: PendingMutation): Boolean = synchronized(lock) {
        if (mutation in committing || mutation !in hidden.value) return false
        hidden.update { it - mutation }
        true
    }

    /**
     * Writes [mutation] to Room, then stops hiding what it deleted, which Room no longer has by
     * then. Does nothing for a mutation undone or already written.
     */
    fun commit(mutation: PendingMutation): Job? {
        synchronized(lock) {
            if (mutation in committing || mutation !in hidden.value) return null
            committing += mutation
        }

        return scope.launch {
            try {
                write(mutation)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // What failed to delete shows again: nothing is lost
                Log.e(TAG, "Could not commit $mutation", e)
            } finally {
                synchronized(lock) { committing -= mutation }
                hidden.update { it - mutation }
            }
        }
    }

    /** Writes every deletion still waiting: the app goes to the background, where it may be killed. */
    fun commitAll() = hidden.value.forEach { commit(it) }
}

/** The app's deletions waiting for "Undo", kept by the [app.melogold.android.AppContainer]. */
val pendingMutations: PendingMutationStore get() = Dependencies.application.container.pendingMutations

// region Overlays: every list and count that shows what a waiting deletion deletes

private inline fun <reified T : PendingMutation> List<PendingMutation>.ofKind() = filterIsInstance<T>()

/** Playlists without those being deleted; the others lose the tracks being taken out. */
fun List<PlaylistPreview>.applying(pending: List<PendingMutation>): List<PlaylistPreview> {
    if (pending.isEmpty()) return this
    val deleted = pending.ofKind<PendingMutation.DeletePlaylist>().mapTo(mutableSetOf()) { it.playlistId }
    val removed = pending.ofKind<PendingMutation.RemoveFromPlaylist>().groupingBy { it.playlistId }.eachCount()

    return mapNotNull { preview ->
        when {
            preview.id in deleted -> null
            preview.id in removed -> preview.copy(songCount = (preview.songCount - removed.getValue(preview.id)).coerceAtLeast(0))
            else -> preview
        }
    }
}

@JvmName("applyingToPlaylists")
fun List<Playlist>.applying(pending: List<PendingMutation>): List<Playlist> {
    val deleted = pending.ofKind<PendingMutation.DeletePlaylist>().mapTo(mutableSetOf()) { it.playlistId }
    return if (deleted.isEmpty()) this else filterNot { it.id in deleted }
}

/** The playlist, or null while it is being deleted. */
fun Playlist?.applying(pending: List<PendingMutation>): Playlist? =
    this?.takeIf { playlist -> pending.none { it is PendingMutation.DeletePlaylist && it.playlistId == playlist.id } }

/** The tracks of [playlistId] without those being taken out of it. */
fun List<Song>.applyingTo(playlistId: Long, pending: List<PendingMutation>): List<Song> {
    val removed = pending.ofKind<PendingMutation.RemoveFromPlaylist>()
        .filter { it.playlistId == playlistId }
        .mapTo(mutableSetOf()) { it.songId }
    return if (removed.isEmpty()) this else filterNot { it.id in removed }
}

/** Tracks shown by their plays: without those being forgotten, none while the history is cleared. */
@JvmName("applyingToPlayedSongs")
fun List<Song>.applyingHistory(pending: List<PendingMutation>): List<Song> = when {
    pending.any { it is PendingMutation.ClearHistory } -> emptyList()
    else -> pending.ofKind<PendingMutation.ForgetTrack>().map { it.songId }.toSet()
        .let { forgotten -> if (forgotten.isEmpty()) this else filterNot { it.id in forgotten } }
}

@JvmName("applyingToRecent")
fun List<SongWithLastPlayed>.applyingHistory(pending: List<PendingMutation>): List<SongWithLastPlayed> {
    if (pending.isEmpty()) return this
    val clearedBefore = pending.ofKind<PendingMutation.ClearHistory>().maxOfOrNull { it.before }
    val forgotten = pending.ofKind<PendingMutation.ForgetTrack>().mapTo(mutableSetOf()) { it.songId }

    return filter { row ->
        row.song.id !in forgotten && (clearedBefore == null || row.lastPlayed > clearedBefore)
    }
}

@JvmName("applyingToMostPlayed")
fun List<SongWithPlayTime>.applyingHistory(pending: List<PendingMutation>): List<SongWithPlayTime> = when {
    pending.any { it is PendingMutation.ClearHistory } -> emptyList()
    else -> pending.ofKind<PendingMutation.ForgetTrack>().map { it.songId }.toSet()
        .let { forgotten -> if (forgotten.isEmpty()) this else filterNot { it.song.id in forgotten } }
}

/** The number of plays without those being forgotten; none while the history is cleared. */
fun Int.applyingPlays(pending: List<PendingMutation>): Int = when {
    pending.any { it is PendingMutation.ClearHistory } -> 0
    else -> (this - pending.ofKind<PendingMutation.ForgetTrack>().sumOf { it.plays }).coerceAtLeast(0)
}

/** The number of playlists without those being deleted. */
fun Int.applyingPlaylists(pending: List<PendingMutation>): Int =
    (this - pending.count { it is PendingMutation.DeletePlaylist }).coerceAtLeast(0)

/** Hidden tracks, and those being hidden. */
fun Set<String>.applyingHidden(pending: List<PendingMutation>): Set<String> =
    pending.ofKind<PendingMutation.Hide>().let { hides -> if (hides.isEmpty()) this else this + hides.map { it.songId } }

/** Tracks without those being hidden. */
@JvmName("applyingHiddenToSongs")
fun List<Song>.applyingHidden(pending: List<PendingMutation>): List<Song> {
    val hidden = pending.ofKind<PendingMutation.Hide>().mapTo(mutableSetOf()) { it.songId }
    return if (hidden.isEmpty()) this else filterNot { it.id in hidden }
}

/** Downloads without those being removed. */
fun Map<String, TrackDownload>.applyingDownloads(pending: List<PendingMutation>): Map<String, TrackDownload> {
    val removed = pending.ofKind<PendingMutation.RemoveDownload>().mapTo(mutableSetOf()) { it.videoId }
    return if (removed.isEmpty()) this else filterKeys { it !in removed }
}

/** Tracks with a download, without the downloads being removed. */
@JvmName("applyingDownloadsToSongs")
fun List<SongWithDownload>.applyingDownloads(pending: List<PendingMutation>): List<SongWithDownload> {
    val removed = pending.ofKind<PendingMutation.RemoveDownload>().mapTo(mutableSetOf()) { it.videoId }
    return if (removed.isEmpty()) this else filterNot { it.song.id in removed }
}

/** [this] with the waiting deletions applied by [apply], again whenever they change. */
fun <T> Flow<T>.withPending(
    store: PendingMutationStore = pendingMutations,
    apply: T.(List<PendingMutation>) -> T
): Flow<T> = combine(this, store.pending) { value, pending -> value.apply(pending) }
// endregion
