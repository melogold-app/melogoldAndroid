package app.melogold.android.utils

import android.graphics.Bitmap
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil

private const val HTTP_NOT_FOUND = 404

/**
 * Video frames (`i.ytimg.com/vi/…`, REWRITE §4.8.2, tasks/0005): every one loses its black bars ([FrameBarsCrop]), and
 * a video without `hq720.jpg` (older and low-resolution uploads) gets its `hqdefault.jpg`. Videos seen without it go
 * there directly; offline, the `hqdefault` already in the disk cache stands in for the `hq720` that never was there.
 */
object VideoFrames : Interceptor {
    private val withoutHq720 = ConcurrentHashMap.newKeySet<String>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data.toString()
        val videoId = url.videoFrameId ?: return chain.proceed()
        val request = chain.request.run {
            if (FrameBarsCrop in transformations) this
            else newBuilder().transformations(transformations + FrameBarsCrop).build()
        }
        if (!url.endsWith("/hq720.jpg")) return chain.withRequest(request).proceed()

        if (videoId !in withoutHq720) {
            val result = chain.withRequest(request).proceed()
            val error = (result as? ErrorResult)?.throwable ?: return result
            if ((error as? HttpException)?.response?.code != HTTP_NOT_FOUND) {
                // No network: a video without hq720 may still have its hqdefault in the disk cache
                val cached = request.hqdefault(videoId).newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()
                return chain.withRequest(cached).proceed() as? SuccessResult ?: result
            }
            withoutHq720 += videoId
        }

        return chain.withRequest(request.hqdefault(videoId)).proceed()
    }

    private fun ImageRequest.hqdefault(videoId: String) =
        newBuilder().data("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").build()
}

/** Cuts the black bars ([FrameBars]) off a video frame before it is cached in memory and shown anywhere. */
object FrameBarsCrop : Transformation() {
    // A new rule needs a new key: pictures cut by the old one stay in the memory cache under the old key
    override val cacheKey = "frame_bars_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        val content = FrameBars.content(pixels, input.width, input.height) ?: return input
        return Bitmap.createBitmap(input, content.x, content.y, content.width, content.height)
    }
}

/** A rectangle of pixels. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The black bars of a video frame (tasks/0005, `FrameBars` of the Windows client): a single's square cover in a 16:9
 * frame (a static video) has them at the sides, a 4:3 `hqdefault` at the top and the bottom. The rule is strict,
 * unlike the artwork colors (`ArtworkColorScheme`): a dark scene of the video itself is not a bar, bars are nearly all
 * black, noticeable and the same at both edges.
 */
object FrameBars {
    /** JPEG black is 0…20 per channel. */
    private const val MAX_CHANNEL = 28

    /** A line is a bar when nearly all of its pixels are black. */
    private const val MIN_BAR_SHARE = 0.98

    /** Bars are cut only when together they are at least 3 % of the side. */
    private const val MIN_BARS = 0.03

    /** The bars at both edges are alike: a dark scene is usually dark at one edge. */
    private const val MAX_ASYMMETRY = 0.03

    /** At least 40 % of the side is left: a nearly black frame stays as it is. */
    private const val MIN_CONTENT = 0.4

    /** What is left of [argb] ([width] × [height], rows without gaps) without its bars; null when it has none. */
    fun content(argb: IntArray, width: Int, height: Int): PixelRect? {
        if (width <= 0 || height <= 0 || argb.size < width * height) return null

        var top = 0
        var bottom = height - 1
        while (top < bottom && argb.isBar(width, top, 0, width - 1, horizontal = true)) top++
        while (bottom > top && argb.isBar(width, bottom, 0, width - 1, horizontal = true)) bottom--
        val (y0, y1) = if (accept(top, height - 1 - bottom, height)) top to bottom else 0 to height - 1

        var left = 0
        var right = width - 1
        while (left < right && argb.isBar(width, left, y0, y1, horizontal = false)) left++
        while (right > left && argb.isBar(width, right, y0, y1, horizontal = false)) right--
        val (x0, x1) = if (accept(left, width - 1 - right, width)) left to right else 0 to width - 1

        if (x0 == 0 && y0 == 0 && x1 == width - 1 && y1 == height - 1) return null
        return PixelRect(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    private fun accept(first: Int, last: Int, size: Int) =
        first + last >= size * MIN_BARS &&
            abs(first - last) <= size * MAX_ASYMMETRY &&
            size - first - last >= size * MIN_CONTENT

    /** Whether row [index] (or column) from [from] to [to] is a bar. */
    private fun IntArray.isBar(width: Int, index: Int, from: Int, to: Int, horizontal: Boolean): Boolean {
        val count = to - from + 1
        val allowed = count - ceil(count * MIN_BAR_SHARE).toInt()
        var bright = 0
        for (i in from..to) {
            val pixel = this[if (horizontal) index * width + i else i * width + index]
            val channel = maxOf(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
            if (channel > MAX_CHANNEL && ++bright > allowed) return false
        }
        return true
    }
}

/** The middle square of [this] (a 16:9 video frame loses its sides); [this] itself if it is square. */
fun Bitmap.centerSquare(): Bitmap {
    if (width == height) return this
    val side = minOf(width, height)
    return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
}
