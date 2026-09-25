package app.melogold.providers.innertube.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads the recorded "Videos" tabs of channels (fixtures/web/channel-videos*, REWRITE §3.7.2).
 */
class YouTubeChannelParserTest {
    private fun json(name: String) = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/fixtures/web/$name.json")) { "no fixture $name" }.readText()
    ).jsonObject

    @Test
    fun `the header gives the name, avatar, subscribers and videos`() {
        val channel = parseYouTubeChannel("UC_kRDKYrUlrbtrSiyu5Tflg", json("channel-videos-daftpunk.ru"))

        assertEquals("Daft Punk", channel.name)
        assertEquals("7,18 млн подписчиков", channel.subscribersText)
        assertEquals("350 видео", channel.videosText)
        assertTrue(channel.avatarUrl!!.startsWith("https://yt3."))
        assertNotNull(channel.playlistsParams)
    }

    @Test
    fun `the videos are read with views, date, duration and the channel`() {
        val channel = parseYouTubeChannel("UCy_vnPBNh9FqtyH9Qc-aiSA", json("channel-videos.ru"))
        val videos = channel.videos.items.filterIsInstance<YouTubeItem.Video>()

        assertEquals(30, videos.size)
        val first = videos.first()
        assertEquals("2Iq9Y21k_14", first.videoId)
        assertEquals("03-18. Время есть, а денег нет", first.title)
        assertEquals("513 просмотров", first.viewsText)
        assertEquals("8 месяцев назад", first.publishedText)
        assertNotNull(first.durationText)
        assertEquals("Gavrik's Archive", first.channelName)
        assertEquals("UCy_vnPBNh9FqtyH9Qc-aiSA", first.channelId)
        assertNotNull(channel.videos.continuation)
    }

    @Test
    fun `the next pages of videos are read`() {
        listOf("channel-videos.p01.ru", "channel-videos.p01.en").forEach { name ->
            val page = parseYouTubeChannelContinuation(json(name))
            assertTrue(page.items.size >= 10, "$name: ${page.items.size} items")
            assertTrue(page.items.all { it is YouTubeItem.Video })
        }
    }
}
