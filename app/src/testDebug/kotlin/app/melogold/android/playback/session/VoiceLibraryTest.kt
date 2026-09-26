package app.melogold.android.playback.session

import androidx.test.core.app.ApplicationProvider
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.internal
import app.melogold.android.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * The library as the voice commands ask it (tasks/0006-gemini-app-functions.md), on the real query: SQLite tells
 * «е» from «ё», a person does not.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceLibraryTest {
    private val catalog = AppVoiceCatalog(ApplicationProvider.getApplicationContext<MainApplication>().container.network)

    /** Room refuses the main thread, where Robolectric runs the tests. */
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private suspend fun found(query: String) = catalog.libraryTracks(query, limit = 50).map { it.id }.sorted()

    @Before
    fun library() = io {
        Database.internal.clearAllTables()
        listOf(
            Song(id = ONCE_MORE, title = "Ещё раз", artistsText = "Кто-то", durationText = "3:10", thumbnailUrl = null),
            Song(id = STARS, title = "Звёзды", artistsText = "Кино", durationText = "3:20", thumbnailUrl = null),
            Song(id = FIR, title = "Елка", artistsText = "Хор", durationText = "2:00", thumbnailUrl = null),
            Song(id = PEREMEN, title = "Хочу перемен", artistsText = "Кино", durationText = "4:50", thumbnailUrl = null)
        ).forEach { Database.insert(it) }
    }

    @Test
    fun `a title with «ё» is found for «е», and the other way round`() = io {
        // What VoiceCommands asks for: the longest word said, lowercase, «ё» as «е» (NameMatch.keyWord)
        assertEquals(listOf(ONCE_MORE), found("еще"))
        assertEquals(listOf(STARS), found("звезды"))
        assertEquals(listOf(FIR), found("ёлка"))
        assertEquals(listOf(PEREMEN, STARS), found("кино"), "artists too")
    }

    private companion object {
        const val ONCE_MORE = "onceMoreAAA"
        const val STARS = "starsAAAAAA"
        const val FIR = "firAAAAAAAA"
        const val PEREMEN = "peremenAAAA"
    }
}
