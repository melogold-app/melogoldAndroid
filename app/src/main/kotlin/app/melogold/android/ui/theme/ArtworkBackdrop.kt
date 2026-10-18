package app.melogold.android.ui.theme

import com.materialkolor.quantize.QuantizerCelebi
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The backdrop of "Now playing" (tasks/0028): a blurred artwork under a color matrix. This file holds the numbers and
 * the math only — no Android API, so the tests run on the JVM. The reference is Linux: `melogold-core`
 * `artwork_colors::shares` / `dark_backdrop` and `examples/backdrop_lab.rs` (`load_cover`); the numbers are the same
 * on every platform.
 */

/** A color of the artwork (`0xRRGGBB`) and its share of the artwork, 0..1. */
data class ColorShare(val rgb: Int, val share: Double)

/** Points of the grid [artworkShares] takes: this many columns, the rows in proportion (at most twice as many). */
private const val SHARES_COLUMNS = 112
private const val SHARES_MAX_ROWS = SHARES_COLUMNS * 2
private const val SHARES_MAX_COLORS = 128

/** The backdrop takes the dominant color and up to five more. */
private const val MAX_SHARES = 6

/**
 * Below this a color is not a color of the artwork: compression noise, a dot in the corner. Low on purpose: the
 * rainbow of "The Dark Side of the Moon" is 1–2 % a color, and without it the artwork is just black.
 */
private const val MIN_SHARE = 0.005

/** Closer than this in Lab (ΔE76) colors are one color: the shadows and the lights of a color are the same color. */
private const val MERGE_DISTANCE = 16.0

/**
 * The colors of an artwork by their shares, the dominant first; the shares add up to 1.
 *
 * Unlike the seed of [ArtworkColors] (Score likes bright colors, and a mostly black cover with a thin rainbow got a
 * red seed) black and grey are colors like any other here. The points are taken from a grid, **not averaged**
 * (averaging turns a small colorful pattern grey); the letterbox bars are **not** cropped (a black edge of a square
 * cover is a part of the picture).
 *
 * @param pixels [width] × [height] pixels as ARGB ints, row after row
 */
fun artworkShares(pixels: IntArray, width: Int, height: Int): List<ColorShare> {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()

    val columns = SHARES_COLUMNS
    val rows = (SHARES_COLUMNS * height / width.toDouble()).roundToInt().coerceIn(1, SHARES_MAX_ROWS)
    val sample = IntArray(columns * rows) { index ->
        val x = (index % columns * width / columns).coerceAtMost(width - 1)
        val y = (index / columns * height / rows).coerceAtMost(height - 1)
        OPAQUE or (pixels[y * width + x] and RGB_MASK)
    }

    // Most frequent first, equal ones by value: the same artwork gives the same colors on every platform
    val colors = QuantizerCelebi.quantize(sample, SHARES_MAX_COLORS)
        .entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key and RGB_MASK })

    // A color joins the first group it is close to; the group stays the color of its first (most frequent) member,
    // not their mean: the mean slides to grey and a rainbow becomes a grey porridge
    class Group(val rgb: Int, val lab: DoubleArray, var weight: Double)

    val groups = mutableListOf<Group>()
    for ((argb, count) in colors) {
        val rgb = argb and RGB_MASK
        val lab = toLab(rgb)
        val group = groups.firstOrNull { distance(it.lab, lab) < MERGE_DISTANCE }
        if (group != null) group.weight += count else groups += Group(rgb, lab, count.toDouble())
    }

    val total = groups.sumOf { it.weight }
    if (total <= 0.0) return emptyList()

    val kept = groups
        .sortedByDescending { it.weight }
        .map { ColorShare(it.rgb, it.weight / total) }
        .filter { it.share >= MIN_SHARE }
        .take(MAX_SHARES)
    val sum = kept.sumOf { it.share }
    return kept.map { it.copy(share = it.share / sum) }
}

/**
 * The color under the blurred artwork, for the corner it might not cover: the hue and the saturation of [rgb], the
 * lightness kept where white text reads (`L*` from 6 to 38). A white cover gives a grey, a black one an almost
 * black, a red one stays red.
 */
fun darkBackdrop(rgb: Int): Int {
    val (l, a, b) = toLab(rgb)
    val target = l.coerceIn(MIN_BACKDROP_L, MAX_BACKDROP_L)
    // A dark color is a bit less saturated (the same saturation shouts there), but not by half or a rainbow turns to mud
    val keep = if (l > 0.0) sqrt(target / l).coerceIn(0.7, 1.0) else 1.0
    return fromLab(doubleArrayOf(target, a * keep, b * keep))
}

private const val MIN_BACKDROP_L = 6.0
private const val MAX_BACKDROP_L = 38.0

// ── the small blurred copy of the artwork ──

/** The side of the small copy: the artwork blurs to the colors of its places, not to its picture. */
const val BACKDROP_SIDE = 24

private const val BLUR_RADIUS = 2
private const val BLUR_PASSES = 3

/**
 * The artwork prepared once for the backdrop.
 *
 * @property pixels [BACKDROP_SIDE] × [BACKDROP_SIDE] opaque ARGB ints: the artwork averaged by cells, then blurred
 * @property luma the mean brightness of the cells before the blur, 0..1 (a light cover is dimmed under white text)
 */
class BackdropCover(val pixels: IntArray, val luma: Float)

/**
 * The artwork averaged by cells to 24 × 24, then blurred by a 5 × 5 box (radius 2, the edge pixel repeated beyond the
 * edge) in three passes.
 *
 * @param pixels [width] × [height] pixels as ARGB ints, row after row
 */
fun prepareBackdropCover(pixels: IntArray, width: Int, height: Int): BackdropCover? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null

    val side = BACKDROP_SIDE
    var cells = Array(side * side) { DoubleArray(3) }
    var luma = 0.0
    for (index in cells.indices) {
        val cell = cells[index]
        val cx = index % side
        val cy = index / side
        val x0 = cx * width / side
        val x1 = maxOf((cx + 1) * width / side, x0 + 1).coerceAtMost(width)
        val y0 = cy * height / side
        val y1 = maxOf((cy + 1) * height / side, y0 + 1).coerceAtMost(height)
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val pixel = pixels[y * width + x]
                cell[0] += (pixel shr 16 and 0xFF).toDouble()
                cell[1] += (pixel shr 8 and 0xFF).toDouble()
                cell[2] += (pixel and 0xFF).toDouble()
                n++
            }
        }
        for (c in 0..2) cell[c] /= maxOf(n, 1)
        luma += (0.2126 * cell[0] + 0.7152 * cell[1] + 0.0722 * cell[2]) / 255.0
    }
    luma /= side * side

    repeat(BLUR_PASSES) {
        val source = cells
        cells = Array(side * side) { index ->
            val x = index % side
            val y = index / side
            val sum = DoubleArray(3)
            for (dy in -BLUR_RADIUS..BLUR_RADIUS) {
                for (dx in -BLUR_RADIUS..BLUR_RADIUS) {
                    val p = source[(y + dy).coerceIn(0, side - 1) * side + (x + dx).coerceIn(0, side - 1)]
                    for (c in 0..2) sum[c] += p[c]
                }
            }
            val boxArea = (2 * BLUR_RADIUS + 1) * (2 * BLUR_RADIUS + 1)
            DoubleArray(3) { sum[it] / boxArea }
        }
    }

    val out = IntArray(side * side) { index ->
        val p = cells[index]
        fun channel(c: Int) = p[c].roundToInt().coerceIn(0, 255)
        OPAQUE or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }
    return BackdropCover(out, luma.toFloat())
}

/** The brightness factor of the color matrix: a light cover under white text is dimmed, a dark one is not touched. */
fun backdropBrightness(luma: Float): Float = (0.30f / luma.coerceAtLeast(0.01f)).coerceIn(0.30f, 1f)

/** The saturation of the color matrix. */
const val BACKDROP_SATURATION = 1.6f

/**
 * The 4 × 5 color matrix of the backdrop (row-major, as `ColorMatrix` of Compose and Android): the brightness
 * [brightness] and the saturation [saturation], a mix with grey by Rec. 709. The offsets are 0.
 */
fun backdropColorMatrix(brightness: Float, saturation: Float = BACKDROP_SATURATION): FloatArray {
    val lr = 0.2126f * (1f - saturation)
    val lg = 0.7152f * (1f - saturation)
    val lb = 0.0722f * (1f - saturation)
    val k = brightness
    return floatArrayOf(
        k * (lr + saturation), k * lg, k * lb, 0f, 0f,
        k * lr, k * (lg + saturation), k * lb, 0f, 0f,
        k * lr, k * lg, k * (lb + saturation), 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
}

// ── CIE Lab (D65, sRGB) ──

private const val OPAQUE = 0xFF000000.toInt()
private const val RGB_MASK = 0x00FFFFFF

internal fun toLab(rgb: Int): DoubleArray {
    fun channel(shift: Int): Double {
        val c = (rgb shr shift and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val r = channel(16)
    val g = channel(8)
    val b = channel(0)
    val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
    val fx = f(x)
    val fy = f(y)
    val fz = f(z)
    return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
}

internal fun fromLab(lab: DoubleArray): Int {
    val (l, a, b) = lab
    val fy = (l + 16.0) / 116.0
    val fx = fy + a / 500.0
    val fz = fy - b / 200.0
    fun inverse(t: Double) = if (t.pow(3) > 216.0 / 24389.0) t.pow(3) else (116.0 * t - 16.0) / (24389.0 / 27.0)
    val x = inverse(fx) * 0.95047
    val y = inverse(fy)
    val z = inverse(fz) * 1.08883
    val linear = doubleArrayOf(
        3.2406 * x - 1.5372 * y - 0.4986 * z,
        -0.9689 * x + 1.8758 * y + 0.0415 * z,
        0.0557 * x - 0.2040 * y + 1.0570 * z
    )
    fun gamma(value: Double): Int {
        val c = value.coerceIn(0.0, 1.0)
        val v = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (v * 255.0).roundToInt()
    }
    return (gamma(linear[0]) shl 16) or (gamma(linear[1]) shl 8) or gamma(linear[2])
}

private operator fun DoubleArray.component1() = this[0]
private operator fun DoubleArray.component2() = this[1]
private operator fun DoubleArray.component3() = this[2]

private fun distance(a: DoubleArray, b: DoubleArray): Double =
    sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2))
