package app.melogold.android.utils

import android.graphics.Bitmap
import android.graphics.Color
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import java.util.concurrent.ConcurrentHashMap

private const val HTTP_NOT_FOUND = 404

/**
 * Loads `hqdefault.jpg` without its letterbox bars when a video has no `hq720.jpg` (older and
 * low-resolution uploads, REWRITE §4.8.2). Videos seen without it go there directly.
 */
object VideoFrameFallback : Interceptor {
    private val withoutHq720 = ConcurrentHashMap.newKeySet<String>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data.toString()
        val videoId = url.takeIf { it.endsWith("/hq720.jpg") }?.videoFrameId ?: return chain.proceed()

        if (videoId !in withoutHq720) {
            val result = chain.proceed()
            val notFound = ((result as? ErrorResult)?.throwable as? HttpException)?.response?.code == HTTP_NOT_FOUND
            if (!notFound) return result
            withoutHq720 += videoId
        }

        return chain.withRequest(
            request.newBuilder()
                .data("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                .transformations(request.transformations + LetterboxCrop)
                .build()
        ).proceed()
    }
}

/**
 * Cuts the black bars off a 4:3 `hqdefault`/`sddefault` frame of a 16:9 video: an eighth of the
 * height at the top and at the bottom (45 of 360 px). A 4:3 video fills the frame and is kept whole.
 */
object LetterboxCrop : Transformation() {
    private const val BAR_MAX_CHANNEL = 24
    private const val BAR_MIN_SHARE = 0.9f
    private const val SAMPLES = 32

    override val cacheKey = "letterbox_crop"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val bar = input.height / 8
        if (bar == 0) return input
        // The outer half of each bar: JPEG blurs its inner edge into the picture
        val top = listOf(0, bar / 4, bar / 2)
        val bottom = top.map { input.height - 1 - it }
        if (!input.isBlack(top) || !input.isBlack(bottom)) return input
        return Bitmap.createBitmap(input, 0, bar, input.width, input.height - 2 * bar)
    }

    /** Whether nearly all of a sample of pixels in [rows] are nearly black (JPEG leaves some noise). */
    private fun Bitmap.isBlack(rows: List<Int>): Boolean {
        val xs = (0 until SAMPLES).map { it * (width - 1) / (SAMPLES - 1) }
        val black = rows.sumOf { y ->
            xs.count { x ->
                val pixel = getPixel(x, y)
                maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= BAR_MAX_CHANNEL
            }
        }
        return black >= rows.size * xs.size * BAR_MIN_SHARE
    }
}

/** The middle square of [this] (a 16:9 video frame loses its sides); [this] itself if it is square. */
fun Bitmap.centerSquare(): Bitmap {
    if (width == height) return this
    val side = minOf(width, height)
    return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
}
