package app.melogold.android.data.repo

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** tasks/0017: the answers of song.link are kept for a day, a hundred of them, in a file. */
class SongLinkFileCacheTest {
    private val file = File.createTempFile("songlink", ".json").also { it.deleteOnExit() }
    private var clock = 1_000_000L

    private fun cache() = SongLinkFileCache(file, now = { clock })

    @Test
    fun `an answer is there for a day and gone after it`() {
        val cache = cache()

        cache.put("https://open.spotify.com/track/x", """{"a":1}""")

        assertEquals("""{"a":1}""", cache.get("https://open.spotify.com/track/x"))
        clock += SongLinkFileCache.DAY_MS - 1
        assertEquals("""{"a":1}""", cache.get("https://open.spotify.com/track/x"))
        clock += 2
        assertNull(cache.get("https://open.spotify.com/track/x"))
    }

    @Test
    fun `it survives a restart`() {
        cache().put("u1", "body1")

        assertEquals("body1", cache().get("u1"))
        assertNull(cache().get("other"))
    }

    @Test
    fun `an old answer is not brought back by a restart after its day`() {
        cache().put("u1", "body1")
        clock += SongLinkFileCache.DAY_MS + 1

        assertNull(cache().get("u1"))
    }

    @Test
    fun `at most a hundred are kept, the oldest go first`() {
        val cache = cache()

        repeat(SongLinkFileCache.MAX_ENTRIES + 5) { index ->
            clock += 10
            cache.put("u$index", "b$index")
        }

        assertNull(cache.get("u0"))
        assertNull(cache.get("u4"))
        assertEquals("b5", cache.get("u5"))
        assertEquals("b104", cache.get("u104"))
    }

    @Test
    fun `a file that is not a cache is no cache`() {
        file.writeText("not json at all")

        val cache = cache()

        assertNull(cache.get("u"))
        cache.put("u", "b")
        assertEquals("b", cache.get("u"))
        assertTrue(file.readText().contains("\"u\""))
    }
}
