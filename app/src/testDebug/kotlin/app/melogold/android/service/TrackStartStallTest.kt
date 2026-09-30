package app.melogold.android.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.internal
import app.melogold.android.models.Format
import app.melogold.android.models.Song
import app.melogold.core.data.utils.UriCache
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * tasks/0019: the track must start from any state of the two caches. The real chain of the player
 * ([PlayerService.createYouTubeDataSourceResolverFactory] under the read-only downloads cache), a local server with
 * ranges instead of googlevideo, and the loader of ExoPlayer as far as the data goes (open from where the last read
 * ended, read to the end of the source, open again).
 *
 * What these tests hold: the bytes that come are the bytes of the track, every open makes progress (an open that
 * yields nothing is the loader retrying with no end), and what the caches hold is not asked of the network.
 */
@RunWith(RobolectricTestRunner::class)
class TrackStartStallTest {
    private val context: Context = ApplicationProvider.getApplicationContext<MainApplication>()
    private val data = ByteArray(300_000) { (it % 251).toByte() }
    private val videoId = "stallTestId1"
    private val chunk = 64L * 1024

    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()
    private lateinit var playerCache: SimpleCache
    private lateinit var downloadsCache: SimpleCache
    private lateinit var dir: File

    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private val streamUri get() = Uri.parse("http://127.0.0.1:${server.address.port}/stream")

    @Before
    fun setUp() {
        dir = File.createTempFile("stall", "").apply { delete(); mkdirs() }
        openCaches()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            requests += range.orEmpty()
            var start = 0
            var end = data.size - 1
            if (range != null) {
                val parts = range.removePrefix("bytes=").split("-")
                start = parts[0].toInt()
                parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { end = minOf(end, it.toInt()) }
            }
            val body = data.copyOfRange(start, end + 1)
            if (range != null) {
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
                ex.sendResponseHeaders(206, body.size.toLong())
            } else ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        io {
            Database.internal.clearAllTables()
            Database.insert(Song(id = videoId, title = "t", durationText = "3:00", thumbnailUrl = null))
            Database.insert(Format(songId = videoId, contentLength = data.size.toLong()))
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
        closeCaches()
        dir.deleteRecursively()
    }

    private fun openCaches() {
        val provider = StandaloneDatabaseProvider(context)
        playerCache = SimpleCache(File(dir, "player"), NoOpCacheEvictor(), provider)
        downloadsCache = SimpleCache(File(dir, "downloads"), NoOpCacheEvictor(), provider)
    }

    private fun closeCaches() {
        playerCache.release()
        downloadsCache.release()
    }

    private fun emptyCaches() {
        closeCaches()
        File(dir, "player").deleteRecursively()
        File(dir, "downloads").deleteRecursively()
        openCaches()
    }

    private fun uriCache() = UriCache<String, StreamMeta>().also {
        it.push(videoId, StreamMeta(data.size.toLong(), null), streamUri)
    }

    /** As the player has it: the resolving source (the player's cache under it) in the read-only downloads cache. */
    private fun playerSource(): DataSource = CacheDataSource.Factory()
        .setCache(downloadsCache)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setUpstreamDataSourceFactory(
            PlayerService.createYouTubeDataSourceResolverFactory(
                context = context, cache = playerCache, chunkLength = chunk, uriCache = uriCache()
            )
        )
        .createDataSource()

    /**
     * Puts [length] bytes from [position] of the stream into [cache], as a play or a download would. A whole track
     * carries its length, as the downloader and `CachedTracks` leave it.
     */
    private fun preload(cache: Cache, position: Long, length: Long) {
        val source = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .createDataSource()
        val spec = DataSpec.Builder().setUri(streamUri).setKey(videoId).setPosition(position).setLength(length).build()
        CacheWriter(source, spec, null, null).cache()
        if (position == 0L && length == data.size.toLong()) {
            cache.applyContentMetadataMutations(
                videoId,
                ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) }
            )
        }
        requests.clear()
    }

    private class Open(val position: Long, val bytes: Int)

    private fun spec(position: Long) = DataSpec.Builder().setUri(videoId).setKey(videoId).setPosition(position)
        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
        .build()

    /** What the loader of ExoPlayer does with the source: [opens] of it, from [from] to the end of the track. */
    private fun load(from: Long): List<Open> {
        val opens = mutableListOf<Open>()
        var position = from
        while (position < data.size) {
            assertTrue(opens.size < 50, "the loader goes round in circles at $position: ${opens.map { it.position to it.bytes }}")
            val source = playerSource()
            var read = 0
            try {
                source.open(spec(position))
                val buffer = ByteArray(8192)
                while (true) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    for (i in 0 until n) {
                        assertEquals(data[(position + read + i).toInt()], buffer[i], "wrong byte at ${position + read + i}")
                    }
                    read += n
                }
            } catch (e: IOException) {
                throw AssertionError("the open at $position failed: $e", e)
            } finally {
                source.close()
            }
            assertTrue(read > 0, "the open at $position gave nothing: ${opens.map { it.position to it.bytes }}")
            opens += Open(position, read)
            position += read
        }
        assertEquals(data.size.toLong(), position, "the track ends where it ends")
        return opens
    }

    @Test
    fun `a track with nothing cached starts and comes whole`() = io {
        load(0)
        assertEquals(5, requests.size, "one request per chunk")
    }

    @Test
    fun `the start of the track in the player cache (the preload, a short listen)`() = io {
        for (prefix in listOf(1_000L, 64L * 1024, 100_000L, 131_072L)) {
            emptyCaches()
            preload(playerCache, 0, prefix)
            load(0)
            assertTrue(requests.none { it.startsWith("bytes=0-") }, "the $prefix cached bytes are not asked again: $requests")
        }
    }

    @Test
    fun `a hole in the middle of the player cache`() = io {
        preload(playerCache, 0, 70_000)
        preload(playerCache, 200_000, 100_000)
        load(0)
        assertEquals(listOf("bytes=70000-131071", "bytes=131072-196607", "bytes=196608-199999"), requests)
    }

    @Test
    fun `a track whole in the player cache asks nothing of the network, from any place`() = io {
        preload(playerCache, 0, data.size.toLong())
        for (from in listOf(0L, 1L, 150_000L, 299_999L)) load(from)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `a track whole in the downloads comes in one open and asks nothing of the network`() = io {
        preload(downloadsCache, 0, data.size.toLong())
        val opens = load(0)
        assertEquals(1, opens.size)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `the downloads under the player cache, both whole`() = io {
        preload(downloadsCache, 0, data.size.toLong())
        preload(playerCache, 0, data.size.toLong())
        load(0)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `a download not finished plays on from the network`() = io {
        preload(downloadsCache, 0, 100_000)
        load(0)
        assertTrue(requests.first().startsWith("bytes=100000-"), requests.toString())
    }

    /**
     * The contract the loader of ExoPlayer is built on here: a chunk ends with the end of input at its boundary and the
     * open says how long the chunk is. ExoPlayer takes the end inside an element of the file for a load error,
     * retries from the position it reached and goes on (about a second per chunk, REWRITE §4.10.3). A change to
     * whole-track reads must be meant.
     */
    @Test
    fun `a chunk ends at its boundary with the end of input, and the open names the chunk`() = io {
        preload(playerCache, 0, data.size.toLong())
        val source = playerSource()
        assertEquals(chunk, source.open(spec(0)))
        var read = 0L
        val buffer = ByteArray(8192)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            read += n
        }
        source.close()
        assertEquals(chunk, read)
    }
}
