package app.melogold.android.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.network.ktor3.KtorNetworkFetcherFactory
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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A missing `hq720` falls back to `hqdefault` without bars; squares take the middle (REWRITE §4.8.2). */
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

    private fun respond(request: ImageRequest): ImageResult =
        if (request.data.toString().endsWith("/hq720.jpg") && "NoHq72000aa" in request.data.toString())
            ErrorResult(null, request, HttpException(NetworkResponse(code = 404)))
        else SuccessResult(bitmap.asImage(), request)

    private fun load(url: String): List<ImageRequest> = runBlocking {
        val asked = mutableListOf<ImageRequest>()
        VideoFrameFallback.intercept(FakeChain(ImageRequest.Builder(context).data(url).build(), asked, ::respond))
        asked
    }

    @Test
    fun `a video without hq720 gets hqdefault without bars`() {
        val first = load("https://i.ytimg.com/vi/NoHq72000aa/hq720.jpg")
        assertEquals(
            listOf("https://i.ytimg.com/vi/NoHq72000aa/hq720.jpg", "https://i.ytimg.com/vi/NoHq72000aa/hqdefault.jpg"),
            first.map { it.data.toString() }
        )
        assertEquals(listOf(LetterboxCrop), first.last().transformations)

        // Known to have none: straight to hqdefault
        val again = load("https://i.ytimg.com/vi/NoHq72000aa/hq720.jpg")
        assertEquals(listOf("https://i.ytimg.com/vi/NoHq72000aa/hqdefault.jpg"), again.map { it.data.toString() })
    }

    @Test
    fun `other images load as asked`() {
        listOf(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
            "https://i.ytimg.com/vi/NoHq72000aa/mqdefault.jpg",
            "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"
        ).forEach { url ->
            val asked = load(url)
            assertEquals(listOf(url), asked.map { it.data.toString() })
            assertTrue(asked.single().transformations.isEmpty())
        }
    }

    @Test
    fun `a real image loader falls back and crops`() = runBlocking {
        val hqdefault = ByteArrayOutputStream().also { frame(bars = 45).compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
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
                add(VideoFrameFallback)
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

    /** A 480×360 frame: [bars] px of black at the top and the bottom around a white picture. */
    private fun frame(bars: Int): Bitmap = createBitmap(480, 360).applyCanvas {
        drawColor(Color.BLACK)
        clipRect(0, bars, 480, 360 - bars)
        drawColor(Color.WHITE)
    }

    @Test
    fun `letterbox bars are cropped, a 4 by 3 picture is kept`() = runBlocking {
        val cropped = LetterboxCrop.transform(frame(bars = 45), Size.ORIGINAL)
        assertEquals(480 to 270, cropped.width to cropped.height)
        assertEquals(Color.WHITE, cropped.getPixel(240, 0))

        val whole = frame(bars = 0)
        assertSame(whole, LetterboxCrop.transform(whole, Size.ORIGINAL))
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
