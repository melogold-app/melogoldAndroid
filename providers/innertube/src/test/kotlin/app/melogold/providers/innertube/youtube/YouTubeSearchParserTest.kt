package app.melogold.providers.innertube.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads the recorded WEB search answers (fixtures/web/search-*, REWRITE §4.13).
 */
class YouTubeSearchParserTest {
    private fun page(name: String) = parseYouTubeSearch(
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/fixtures/web/$name.json")) { "no fixture $name" }.readText()
        ).jsonObject
    )

    @Test
    fun `videos are read with channel, duration, views and a next page`() {
        val page = page("search-videos.ru")
        val videos = page.items.filterIsInstance<YouTubeItem.Video>()

        assertEquals(20, videos.size)
        val first = videos.first()
        assertEquals("xKpzH5bxYsk", first.videoId)
        assertEquals("Виктор Цой - Группа Крови", first.title)
        assertEquals("foleywarlocks", first.channelName)
        assertEquals("UCUlwHAxJEFWmYpqZbBDhwxg", first.channelId)
        assertEquals("4:51", first.durationText)
        assertEquals("25\u00A0млн просмотров", first.viewsText) // YouTube keeps a no-break space
        assertEquals("16 лет назад", first.publishedText)
        assertTrue(first.thumbnailUrl!!.startsWith("https://i.ytimg.com/"))
        assertTrue(videos.none { it.isLive })
        assertNotNull(page.continuation)
    }

    @Test
    fun `continuations are read too`() {
        listOf("search-videos.p01.ru", "search-videos.p01.en", "search-playlists.p01.ru", "search-channels.p01.ru").forEach {
            val page = page(it)
            assertTrue(page.items.size >= 10, "$it: ${page.items.size} items")
            assertNotNull(page.continuation, it)
        }
    }

    @Test
    fun `live streams carry their badge and viewers`() {
        val videos = page("search-live.ru").items.filterIsInstance<YouTubeItem.Video>()

        assertTrue(videos.isNotEmpty())
        val live = videos.filter { it.isLive }
        assertTrue(live.size >= videos.size / 2, "live: ${live.size} of ${videos.size}")
        assertEquals("В ЭФИРЕ", live.first().liveLabel)
        assertTrue(live.first().viewsText!!.contains("зрител"))
    }

    @Test
    fun `channels show subscribers rather than the handle`() {
        val channels = page("search-channels.ru").items.filterIsInstance<YouTubeItem.Channel>()

        assertEquals(20, channels.size)
        assertEquals("UCP4kF3UNNv-bNyUM3SD2i5A", channels.first().channelId)
        assertEquals("Кино Группа крови", channels.first().name)
        assertTrue(channels.first().thumbnailUrl!!.startsWith("https://yt3.ggpht.com/"))
        assertEquals("11 подписчиков", channels.first { it.name == "Группа КИНО" }.subtitle)
    }

    @Test
    fun `playlists are read from lockups`() {
        val playlists = page("search-playlists.ru").items.filterIsInstance<YouTubeItem.Playlist>()

        assertEquals(20, playlists.size)
        val first = playlists.first()
        assertEquals("PLYLVo5iB9gbHVB-0R9HOnFJmssl8ICeCP", first.playlistId)
        assertEquals("Кино Группа Крови", first.title)
        assertEquals("Svetlana Nikitchenko", first.channelName)
        assertEquals("11 видео", first.videoCountText)
        assertNotNull(first.thumbnailUrl)
    }

    @Test
    fun `english answers parse the same way`() {
        listOf("search-videos.en", "search-channels.en", "search-live.en", "search-playlists.en").forEach {
            assertTrue(page(it).items.size >= 10, it)
        }
    }
}
