package app.melogold.android.ui.theme

import android.graphics.Bitmap
import android.graphics.Color.blue
import android.graphics.Color.green
import android.graphics.Color.red
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.graphics.scale
import app.melogold.core.ui.utils.isAtLeastAndroid8
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Colors taken from the artwork of the playing track (REDESIGN-M3E §4.4), the way Android's system
 * media controls do it: `QuantizerCelebi` + `Score` on a small copy of the artwork, then a
 * `SchemeContent` scheme around the winning color.
 *
 * - black letterbox bars of YouTube thumbnails are cropped before quantizing;
 * - grey artwork (less than 5% colorful pixels) has no seed, callers fall back to the base
 *   scheme;
 * - seeds are cached per media id.
 */
object ArtworkColors {
    private const val SAMPLE_SIZE = 112
    private const val MAX_COLORS = 128
    private const val MIN_CHROMA = 8.0
    private const val MIN_COLORFUL_SHARE = 0.05f

    // A row/column is part of a letterbox bar when nearly all of its pixels are nearly black
    private const val BAR_MAX_CHANNEL = 24
    private const val BAR_MIN_SHARE = 0.9f
    private const val MIN_CONTENT = 3

    /** A computed seed; [argb] is `null` for grey artwork. */
    private class Seed(val argb: Int?)

    private val cache = LruCache<String, Seed>(32)

    /**
     * The seed color of [bitmap], or `null` if the artwork is grey. Runs on [Dispatchers.Default].
     *
     * @param key a stable id of the artwork (the media id); `null` disables caching
     */
    suspend fun seedOf(key: String?, bitmap: Bitmap): Int? {
        key?.let { cache[it] }?.let { return it.argb }

        val seed = withContext(Dispatchers.Default) {
            runCatching { computeSeed(bitmap) }.getOrNull()
        }
        key?.let { cache.put(it, Seed(seed)) }
        return seed
    }

    private fun computeSeed(bitmap: Bitmap): Int? {
        val software =
            if (isAtLeastAndroid8 && bitmap.config == Bitmap.Config.HARDWARE)
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else bitmap

        val width = SAMPLE_SIZE
        val height = (SAMPLE_SIZE * software.height.toFloat() / software.width)
            .roundToInt()
            .coerceIn(1, SAMPLE_SIZE * 2)
        val sample = software.scale(width, height)
        val pixels = IntArray(width * height).also {
            sample.getPixels(it, 0, width, 0, 0, width, height)
        }

        val quantized = QuantizerCelebi.quantize(
            pixels = pixels.withoutLetterbox(width, height),
            maxColors = MAX_COLORS
        )

        // Black, white and greys carry no hue but would dominate Score's hue proportions (a
        // black-and-white cover with a navy sky would get an off-white seed), so only colorful
        // clusters are scored, and only if there are enough of them
        val total = quantized.values.sum()
        val colorful = quantized.filterKeys { Hct.fromInt(it).chroma >= MIN_CHROMA }
        if (total == 0 || colorful.values.sum() < total * MIN_COLORFUL_SHARE) return null

        return Score.score(
            colorsToPopulation = colorful,
            desired = 1,
            fallbackColorArgb = null,
            filter = true
        ).firstOrNull()
    }

    private fun Int.isBarPixel() =
        maxOf(red(this), green(this), blue(this)) <= BAR_MAX_CHANNEL

    /**
     * The pixels of [this] (a [width]×[height] image) without black letterbox bars.
     */
    private fun IntArray.withoutLetterbox(width: Int, height: Int): IntArray {
        val rows = barFreeRange(count = height, length = width) { y, x -> this[y * width + x] }
        val columns = barFreeRange(count = width, length = rows.count()) { x, i ->
            this[(rows.first + i) * width + x]
        }

        // An (almost) all-black artwork is not letterboxed, keep it as is
        if (rows.count() < MIN_CONTENT || columns.count() < MIN_CONTENT) return this

        return IntArray(rows.count() * columns.count()).also { out ->
            var i = 0
            for (y in rows) for (x in columns) out[i++] = this[y * width + x]
        }
    }

    /**
     * The range of lines (rows or columns) between the bars at both ends; [pixel] returns the
     * pixel at a position of a line.
     */
    private fun barFreeRange(
        count: Int,
        length: Int,
        pixel: (line: Int, position: Int) -> Int
    ): IntRange {
        fun isBar(line: Int) =
            (0 until length).count { pixel(line, it).isBarPixel() } >= length * BAR_MIN_SHARE

        var first = 0
        var last = count - 1
        while (first < last && isBar(first)) first++
        while (last > first && isBar(last)) last--
        return first..last
    }
}

/**
 * A `SchemeContent` color scheme around [seed].
 */
fun artworkColorScheme(seed: Int, isDark: Boolean, contrastLevel: Double = 0.0): ColorScheme =
    SchemeContent(
        sourceColorHct = Hct.fromInt(seed),
        isDark = isDark,
        contrastLevel = contrastLevel,
        specVersion = MelogoldSpecVersion
    ).toColorScheme()

/**
 * The color scheme of [bitmap], or `null` while there is none (no artwork, grey artwork).
 *
 * Changes are applied instantly (no crossfade), [delayMillis] after the artwork settles, so fast
 * skipping does not recolor the UI on every track.
 *
 * @param key a stable id of the artwork (the media id), used to cache the seed color
 */
@Composable
fun rememberArtworkColorScheme(
    key: String?,
    bitmap: Bitmap?,
    isDark: Boolean,
    contrastLevel: Double,
    delayMillis: Long = 0L
): ColorScheme? {
    var seed by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(key, bitmap) {
        if (delayMillis > 0) delay(delayMillis)
        seed = bitmap?.let { ArtworkColors.seedOf(key, it) }
    }

    return remember(seed, isDark, contrastLevel) {
        seed?.let { artworkColorScheme(seed = it, isDark = isDark, contrastLevel = contrastLevel) }
    }
}
