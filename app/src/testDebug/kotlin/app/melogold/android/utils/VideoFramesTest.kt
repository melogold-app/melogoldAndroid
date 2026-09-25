package app.melogold.android.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.asImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Size
import coil3.toBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Video frames (tasks/0005, REWRITE §4.8.2): black bars are cut by the rule of `FrameBars` (the cases of the Windows
 * `FrameBarsTests`), a missing `hq720` falls back to `hqdefault`, online and from the disk cache, squares take the middle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFramesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Answers each request with [respond] and remembers what was asked. */
    private class FakeChain(
        override val request: ImageRequest,
        val asked: MutableList<ImageRequest>,
        val respond: (ImageRequest) -> ImageResult
    ) : Interceptor.Chain {
        override val size = Size.ORIGINAL
        override fun withRequest(request: ImageRequest) = FakeChain(request, asked, respond)
        override fun withSize(size: Size) = this
        override suspend fun proceed() = respond(request).also { asked += request }
    }

    private val bitmap = createBitmap(1, 1)

    private fun respond(request: ImageRequest): ImageResult {
        val url = request.data.toString()
        return when {
            url.endsWith("/hq720.jpg") && "NoHq72000aa" in url ->
                ErrorResult(null, request, HttpException(NetworkResponse(code = 404)))

            // No network; only the hqdefault of NoHq720Off1 is in the disk cache
            "NoHq720Off" in url && (url.endsWith("/hq720.jpg") || "NoHq720Off1" !in url) ->
                ErrorResult(null, request, IOException("offline"))

            else -> SuccessResult(bitmap.asImage(), request)
        }
    }

    private fun load(url: String): Pair<ImageResult, List<ImageRequest>> = runBlocking {
        val asked = mutableListOf<ImageRequest>()
        val result = VideoFrames.intercept(FakeChain(ImageRequest.Builder(context).data(url).build(), asked, ::respond))
        result to asked
    }

    @Test
    fun `a video without hq720 gets hqdefault without bars`() {
        val (_, first) = load("https://i.ytimg.com/vi/NoHq72000aa/hq720.jpg")
        assertEquals(
            listOf("https://i.ytimg.com/vi/NoHq72000aa/hq720.jpg", "https://i.ytimg.com/vi/NoHq72000aa/hqdefault.jpg"),
            first.map { it.data.toString() }
        )
        first.forEach { assertEquals(listOf(FrameBarsCrop), it.transformations) }

        // Known to have none: straight to hqdefault
        val (_, again) = load("https://i.ytimg.com/vi/NoHq72000aa/hq720.jpg")
        assertEquals(listOf("https://i.ytimg.com/vi/NoHq72000aa/hqdefault.jpg"), again.map { it.data.toString() })
    }

    @Test
    fun `every video frame loses its bars, other images load as asked`() {
        listOf(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
            "https://i.ytimg.com/vi/LLhpBVFh2Zg/mqdefault.jpg"
        ).forEach { url ->
            val (_, asked) = load(url)
            assertEquals(listOf(url), asked.map { it.data.toString() })
            assertEquals(listOf(FrameBarsCrop), asked.single().transformations)
        }

        val cover = "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"
        val (_, asked) = load(cover)
        assertEquals(listOf(cover), asked.map { it.data.toString() })
        assertTrue(asked.single().transformations.isEmpty())
    }

    @Test
    fun `offline, a video without hq720 shows the hqdefault from the disk cache`() {
        val (result, asked) = load("https://i.ytimg.com/vi/NoHq720Off1/hq720.jpg")
        assertTrue(result is SuccessResult, "$result")
        assertEquals(
            listOf("https://i.ytimg.com/vi/NoHq720Off1/hq720.jpg", "https://i.ytimg.com/vi/NoHq720Off1/hqdefault.jpg"),
            asked.map { it.data.toString() }
        )
        assertEquals(CachePolicy.DISABLED, asked.last().networkCachePolicy)
        assertEquals(listOf(FrameBarsCrop), asked.last().transformations)

        // Nothing cached: the error of hq720 itself
        val (missing, _) = load("https://i.ytimg.com/vi/NoHq720Off2/hq720.jpg")
        assertEquals("https://i.ytimg.com/vi/NoHq720Off2/hq720.jpg", (missing as ErrorResult).request.data.toString())
    }

    private fun png(bitmap: Bitmap) =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    @Test
    fun `a real image loader falls back and crops`() = runBlocking {
        val hqdefault = png(letterboxed(bars = 45))
        val asked = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                asked += request.url.toString()
                if (request.url.encodedPath.endsWith("/hqdefault.jpg"))
                    respond(hqdefault, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
                else respond(ByteArray(0), HttpStatusCode.NotFound)
            }
        )
        val loader = ImageLoader.Builder(context)
            .components {
                add(VideoFrames)
                add(KtorNetworkFetcherFactory(httpClient = { client }))
            }
            .memoryCache(null)
            .diskCache(null)
            .build()

        val result = loader.execute(
            ImageRequest.Builder(context)
                .data("https://i.ytimg.com/vi/RealLoader1/hq720.jpg")
                .size(480)
                .allowHardware(false)
                .build()
        )

        assertTrue(result is SuccessResult, "$result ${(result as? ErrorResult)?.throwable}")
        assertEquals(
            listOf("https://i.ytimg.com/vi/RealLoader1/hq720.jpg", "https://i.ytimg.com/vi/RealLoader1/hqdefault.jpg"),
            asked
        )
        val bitmap = result.image.toBitmap()
        assertEquals(480 to 270, bitmap.width to bitmap.height)
    }

    @Test
    fun `a real image loader shows the cached hqdefault offline`() = runBlocking {
        val hqdefault = png(letterboxed(bars = 45))
        var online = true
        val asked = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                if (!online) throw IOException("offline")
                asked += request.url.toString()
                respond(hqdefault, HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
            }
        )
        val folder = context.cacheDir.resolve("video-frames-test")
        val loader = ImageLoader.Builder(context)
            .components {
                add(VideoFrames)
                add(KtorNetworkFetcherFactory(httpClient = { client }))
            }
            .memoryCache(null)
            .diskCache { DiskCache.Builder().directory(folder).maxSizeBytes(1_000_000).build() }
            .build()
        fun request(name: String) = ImageRequest.Builder(context)
            .data("https://i.ytimg.com/vi/RealLoader2/$name.jpg")
            .size(480)
            .allowHardware(false)
            .build()

        try {
            // Seen before, when the app last ran
            assertTrue(loader.execute(request("hqdefault")) is SuccessResult)
            online = false

            val result = loader.execute(request("hq720"))

            assertTrue(result is SuccessResult, "$result ${(result as? ErrorResult)?.throwable}")
            assertEquals(listOf("https://i.ytimg.com/vi/RealLoader2/hqdefault.jpg"), asked)
            assertEquals(480 to 270, result.image.toBitmap().run { width to height })
        } finally {
            loader.shutdown()
            folder.deleteRecursively()
        }
    }

    /** A 480×360 frame: [bars] px of black at the top and the bottom around a white picture. */
    private fun letterboxed(bars: Int): Bitmap = createBitmap(480, 360).applyCanvas {
        drawColor(Color.BLACK)
        clipRect(0, bars, 480, 360 - bars)
        drawColor(Color.WHITE)
    }

    /** Opaque gray pixels, rows without gaps: [paint] gives the brightness of (x, y). */
    private fun frame(width: Int, height: Int, paint: (x: Int, y: Int) -> Int) = IntArray(width * height) { i ->
        val v = paint(i % width, i / width)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** A "picture": varied, with dark places, but not a bar. */
    private fun picture(x: Int, y: Int) = 40 + (x * 7 + y * 13) % 200

    @Test
    fun `a square cover in a wide frame loses its side bars`() {
        // hq720 of a static video: a 720×720 cover in the middle of a 1280×720 frame
        val pixels = frame(1280, 720) { x, y -> if (x in 280 until 1000) picture(x, y) else if (x % 3 == 0) 12 else 4 }
        assertEquals(PixelRect(280, 0, 720, 720), FrameBars.content(pixels, 1280, 720))
    }

    @Test
    fun `a letterboxed preview loses its top and bottom`() {
        // hqdefault 480×360: a 16:9 frame and 45 px bars at the top and the bottom
        val pixels = frame(480, 360) { x, y -> if (y in 45 until 315) picture(x, y) else 0 }
        assertEquals(PixelRect(0, 45, 480, 270), FrameBars.content(pixels, 480, 360))
    }

    @Test
    fun `a full frame stays as it is`() = assertNull(FrameBars.content(frame(320, 180, ::picture), 320, 180))

    @Test
    fun `a dark scene on one side is not a bar`() {
        // A night scene: the left third is dark, the right is light
        val pixels = frame(320, 180) { x, y -> if (x < 110) 6 else picture(x, y) }
        assertNull(FrameBars.content(pixels, 320, 180))
    }

    @Test
    fun `an almost black frame stays as it is`() {
        // Nearly all black, a light strip in the middle: nothing to cut
        val pixels = frame(320, 180) { x, y -> if (x in 150 until 170) picture(x, y) else 0 }
        assertNull(FrameBars.content(pixels, 320, 180))
    }

    @Test
    fun `a thin edge is not a bar`() {
        // A 2 px black edge: less than 3 % of the side
        val pixels = frame(320, 180) { x, y -> if (x < 2 || x >= 318) 0 else picture(x, y) }
        assertNull(FrameBars.content(pixels, 320, 180))
    }

    @Test
    fun `JPEG noise in the bars is tolerated`() {
        // A rare light pixel in a bar (JPEG noise): still a bar
        val pixels = frame(320, 180) { x, y ->
            if (x in 70 until 250) picture(x, y) else if (x == 10 && y == 50) 200 else 8
        }
        assertEquals(PixelRect(70, 0, 180, 180), FrameBars.content(pixels, 320, 180))
    }

    @Test
    fun `the crop cuts a single's cover out of a static video, a frame without bars is kept`() = runBlocking {
        // «Бармалей» (LLhpBVFh2Zg): black at the sides, the cover in the middle
        val wide = createBitmap(1280, 720).applyCanvas {
            drawColor(Color.BLACK)
            clipRect(280, 0, 1000, 720)
            drawColor(Color.rgb(200, 60, 40))
        }
        val cover = FrameBarsCrop.transform(wide, Size.ORIGINAL)
        assertEquals(720 to 720, cover.width to cover.height)
        assertEquals(Color.rgb(200, 60, 40), cover.getPixel(0, 360))
        assertEquals(Color.rgb(200, 60, 40), cover.getPixel(719, 360))

        val whole = letterboxed(bars = 0)
        assertSame(whole, FrameBarsCrop.transform(whole, Size.ORIGINAL))
    }

    @Test
    fun `a square takes the middle of a frame`() {
        // Left third red, middle white, right third blue: the square is all white at its sides' middle
        val wide = createBitmap(1280, 720).applyCanvas {
            drawColor(Color.WHITE)
            save()
            clipRect(0, 0, 280, 720)
            drawColor(Color.RED)
            restore()
            clipRect(1000, 0, 1280, 720)
            drawColor(Color.BLUE)
        }
        val square = wide.centerSquare()
        assertEquals(720 to 720, square.width to square.height)
        assertEquals(Color.WHITE, square.getPixel(0, 360))
        assertEquals(Color.WHITE, square.getPixel(719, 360))

        val cover = createBitmap(544, 544)
        assertSame(cover, cover.centerSquare())
    }
}
