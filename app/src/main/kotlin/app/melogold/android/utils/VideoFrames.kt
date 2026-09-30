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
 * Video frames (`i.ytimg.com/vi/…`, REWRITE §4.8.2, tasks/0005): every one loses its bars and ring ([FrameBarsCrop]), and
 * a video without `hq720.jpg` (older and low-resolution uploads) gets its `hqdefault.jpg`. Videos seen without it go
 * there directly; offline, the `hqdefault` already in the disk cache stands in for the `hq720` that never was there.
 */
object VideoFrames : Interceptor {
    private val withoutHq720 = ConcurrentHashMap.newKeySet<String>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data.toString()
        val videoId = url.videoFrameId ?: return chain.proceedWithRing(url)
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

    /** A cover of YouTube Music (`lh3`/`yt3.googleusercontent`) loses the ring of its scan ([CoverRingCrop]). */
    private suspend fun Interceptor.Chain.proceedWithRing(url: String): ImageResult {
        if (!url.isYouTubeMusicCover || CoverRingCrop in request.transformations) return proceed()
        return withRequest(request.newBuilder().transformations(request.transformations + CoverRingCrop).build()).proceed()
    }
}

private val String.isYouTubeMusicCover: Boolean
    get() = contains("googleusercontent.com") || contains("ggpht.com")

/** Cuts the bars and the ring ([FrameBars]) off a video frame before it is cached in memory and shown anywhere. */
object FrameBarsCrop : Transformation() {
    // A new rule needs a new key: pictures cut by the old one stay in the memory cache under the old key
    override val cacheKey = "frame_bars_v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = input.cropped(bars = true)
}

/** Cuts the ring of a scan ([FrameBars], no bars) off a square cover of YouTube Music. */
object CoverRingCrop : Transformation() {
    override val cacheKey = "cover_ring_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = input.cropped(bars = false)
}

private fun Bitmap.cropped(bars: Boolean): Bitmap {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val content = FrameBars.content(pixels, width, height, bars) ?: return this
    return Bitmap.createBitmap(this, content.x, content.y, content.width, content.height)
}

/** A rectangle of pixels. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The bars of a video frame (tasks/0005, `FrameBars` of the Apple client): a single's square cover in a 16:9 frame (a
 * static video) has them at the sides, a 4:3 `hqdefault` at the top and the bottom. They are not only black: a static
 * video's bars can be any even color (brown at Kino «Группа крови», 2026-09-30). Inside them, and at the very edge of a
 * YouTube Music cover, a scan often has a ring of one color on all four sides; it goes too. The rule is strict, unlike
 * the artwork colors (`ArtworkColorScheme`): a bar is a line of nearly one color, bars are noticeable, of one width and
 * one color at both edges; a dark or plain scene of the video itself is not a bar.
 */
object FrameBars {
    /** How far a pixel of a bar may be from the bar's color, per channel: JPEG noise. */
    private const val TOLERANCE = 24

    /** A line is a bar when nearly all of its pixels are of the bar's color. */
    private const val MIN_BAR_SHARE = 0.98

    /** Bars are cut only when together they are at least 3 % of the side. */
    private const val MIN_BARS = 0.03

    /** The bars at both edges are alike: a dark scene is usually dark at one edge. */
    private const val MAX_ASYMMETRY = 0.03

    /** The bars at both edges are of one color (per channel). */
    private const val MAX_SIDES_DIFFERENCE = 40

    /** At least 40 % of the side is left: a nearly plain frame stays as it is. */
    private const val MIN_CONTENT = 0.4

    /** A ring is on all four sides, each at most this share of its side. */
    private const val MAX_RING = 0.06

    /** The edge of a JPEG ring is blurred: half a percent more goes with it, or a dark hair stays. */
    private const val RING_BLEND = 0.005

    /**
     * What is left of [argb] ([width] × [height], rows without gaps) without its bars and ring; null when nothing is
     * cut. [bars] false: the ring only (a square cover has no bars).
     */
    fun content(argb: IntArray, width: Int, height: Int, bars: Boolean = true): PixelRect? {
        if (width <= 0 || height <= 0 || argb.size < width * height) return null
        val scan = Scan(argb, width)
        var (y0, y1) = (if (bars) scan.bars(height, 0, width - 1, horizontal = true) else null) ?: (0 to height - 1)
        var (x0, x1) = (if (bars) scan.bars(width, y0, y1, horizontal = false) else null) ?: (0 to width - 1)
        scan.ring(x0, x1, y0, y1)?.let { inner ->
            x0 = inner[0]
            x1 = inner[1]
            y0 = inner[2]
            y1 = inner[3]
        }
        if (x0 == 0 && y0 == 0 && x1 == width - 1 && y1 == height - 1) return null
        return PixelRect(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    private class Scan(private val argb: IntArray, private val width: Int) {
        private fun pixel(index: Int, i: Int, horizontal: Boolean) =
            argb[if (horizontal) index * width + i else i * width + index]

        private fun near(a: Int, b: Int, limit: Int) =
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= limit &&
                abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= limit &&
                abs((a and 0xFF) - (b and 0xFF)) <= limit

        /** Whether row [index] (or column) from [from] to [to] is nearly all of [color]. */
        fun matches(index: Int, from: Int, to: Int, horizontal: Boolean, color: Int): Boolean {
            val count = to - from + 1
            val allowed = count - ceil(count * MIN_BAR_SHARE).toInt()
            var off = 0
            for (i in from..to) {
                if (!near(pixel(index, i, horizontal), color, TOLERANCE) && ++off > allowed) return false
            }
            return true
        }

        /** The color of an edge line when it is nearly of one color; null — the picture reaches the edge. */
        fun edgeColor(index: Int, from: Int, to: Int, horizontal: Boolean): Int? {
            var r = 0L
            var g = 0L
            var b = 0L
            for (i in from..to) {
                val p = pixel(index, i, horizontal)
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            val count = to - from + 1
            val mean = (0xFF shl 24) or ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
            return mean.takeIf { matches(index, from, to, horizontal, it) }
        }

        /** The bars at both ends of a side of [size]; the lines across go from [from] to [to]. */
        fun bars(size: Int, from: Int, to: Int, horizontal: Boolean): Pair<Int, Int>? {
            val first = edgeColor(0, from, to, horizontal) ?: return null
            val last = edgeColor(size - 1, from, to, horizontal) ?: return null
            if (!near(first, last, MAX_SIDES_DIFFERENCE)) return null
            var start = 0
            var end = size - 1
            while (start < end && matches(start, from, to, horizontal, first)) start++
            while (end > start && matches(end, from, to, horizontal, last)) end--
            val tail = size - 1 - end
            val accepted = start + tail >= size * MIN_BARS &&
                abs(start - tail) <= size * MAX_ASYMMETRY &&
                size - start - tail >= size * MIN_CONTENT
            return if (accepted) start to end else null
        }

        /** A ring of one color on all four sides of the rectangle: its new bounds (x0, x1, y0, y1) or null. */
        fun ring(x0: Int, x1: Int, y0: Int, y1: Int): IntArray? {
            val w = x1 - x0 + 1
            val h = y1 - y0 + 1
            if (w <= 8 || h <= 8) return null
            val color = edgeColor(y0, x0, x1, horizontal = true) ?: return null
            var top = y0
            var bottom = y1
            var left = x0
            var right = x1
            while (top < y1 && matches(top, x0, x1, horizontal = true, color)) top++
            while (bottom > top && matches(bottom, x0, x1, horizontal = true, color)) bottom--
            while (left < x1 && matches(left, y0, y1, horizontal = false, color)) left++
            while (right > left && matches(right, y0, y1, horizontal = false, color)) right--
            val vertical = listOf(top - y0, y1 - bottom)
            val horizontal = listOf(left - x0, x1 - right)
            if ((vertical + horizontal).any { it < 1 }) return null
            if (vertical.any { it > h * MAX_RING } || horizontal.any { it > w * MAX_RING }) return null
            val blend = maxOf(1, ceil(minOf(w, h) * RING_BLEND).toInt())
            if (right - left <= 2 * blend || bottom - top <= 2 * blend) return null
            return intArrayOf(left + blend, right - blend, top + blend, bottom - blend)
        }
    }
}

/** The middle square of [this] (a 16:9 video frame loses its sides); [this] itself if it is square. */
fun Bitmap.centerSquare(): Bitmap {
    if (width == height) return this
    val side = minOf(width, height)
    return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
}
