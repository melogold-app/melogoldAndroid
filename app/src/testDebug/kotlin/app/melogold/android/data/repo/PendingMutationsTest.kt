package app.melogold.android.data.repo

import androidx.compose.material3.SnackbarHostState
import app.melogold.android.data.repo.PendingMutation.ClearHistory
import app.melogold.android.data.repo.PendingMutation.DeletePlaylist
import app.melogold.android.data.repo.PendingMutation.ForgetTrack
import app.melogold.android.data.repo.PendingMutation.RemoveFromPlaylist
import app.melogold.android.models.PlaylistPreview
import app.melogold.android.ui.shell.AppSnackbar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PendingMutationsTest {
    private val rock = PlaylistPreview(id = 1, name = "Rock", songCount = 3, thumbnail = null)
    private val jazz = PlaylistPreview(id = 2, name = "Jazz", songCount = 5, thumbnail = null)

    private fun TestScope.store(written: MutableList<PendingMutation>) =
        PendingMutationStore(scope = backgroundScope) { written += it }

    private suspend fun PendingMutationStore.playlists() =
        flowOf(listOf(rock, jazz)).withPending(this) { applying(it) }.first()

    @Test
    fun `a deleted playlist is hidden until undo brings it back`() = runTest {
        val written = mutableListOf<PendingMutation>()
        val store = store(written)

        store.add(DeletePlaylist(1))
        assertEquals(listOf(jazz), store.playlists())

        assertTrue(store.undo(DeletePlaylist(1)))
        assertEquals(listOf(rock, jazz), store.playlists())
        runCurrent()
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a commit is written once and cannot be undone`() = runTest {
        val written = mutableListOf<PendingMutation>()
        val store = store(written)

        store.add(DeletePlaylist(1))
        store.commit(DeletePlaylist(1))
        assertFalse(store.undo(DeletePlaylist(1)))
        store.commit(DeletePlaylist(1))
        runCurrent()

        assertEquals(listOf<PendingMutation>(DeletePlaylist(1)), written)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun `going to the background writes everything waiting`() = runTest {
        val written = mutableListOf<PendingMutation>()
        val store = store(written)

        store.add(DeletePlaylist(1))
        store.add(ForgetTrack("a", before = 10, plays = 2))
        store.commitAll()
        runCurrent()

        assertEquals(setOf(DeletePlaylist(1), ForgetTrack("a", before = 10, plays = 2)), written.toSet())
    }

    @Test
    fun `a newer snackbar commits the one it replaces, undo drops its own`() = runTest {
        val written = mutableListOf<PendingMutation>()
        val store = store(written)
        val snackbar = AppSnackbar(
            hostState = SnackbarHostState(),
            scope = backgroundScope,
            undoLabel = { "Undo" },
            mutations = { store }
        )

        snackbar.undoable("Deleted", DeletePlaylist(1))
        runCurrent()
        snackbar.undoable("Removed", RemoveFromPlaylist(playlistId = 2, songId = "a"))
        runCurrent()
        assertEquals(listOf<PendingMutation>(DeletePlaylist(1)), written)

        snackbar.hostState.currentSnackbarData?.performAction()
        runCurrent()
        assertEquals(listOf<PendingMutation>(DeletePlaylist(1)), written)
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun `the deletion is written when the snackbar times out`() = runTest {
        val written = mutableListOf<PendingMutation>()
        val store = store(written)
        val snackbar = AppSnackbar(
            hostState = SnackbarHostState(),
            scope = backgroundScope,
            undoLabel = { "Undo" },
            mutations = { store }
        )

        snackbar.undoable("Cleared", ClearHistory(before = 10))
        advanceTimeBy(UNDO_TIMEOUT_MS - 100)
        assertTrue(written.isEmpty())

        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf<PendingMutation>(ClearHistory(before = 10)), written)
    }

    @Test
    fun `tracks being taken out leave the playlist count and the history`() {
        val pending = listOf(RemoveFromPlaylist(playlistId = 2, songId = "a"), ForgetTrack("b", before = 10, plays = 4))

        assertEquals(listOf(rock, jazz.copy(songCount = 4)), listOf(rock, jazz).applying(pending))
        assertEquals(6, 10.applyingPlays(pending))
        assertEquals(0, 10.applyingPlays(pending + ClearHistory(before = 20)))
    }
}
