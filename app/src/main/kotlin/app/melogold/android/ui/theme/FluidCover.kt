package app.melogold.android.ui.theme

/*
 * The artwork for the shader of the living backdrop (tasks/0029): a 256 × 256 copy and its chain of mip levels. The
 * reference is Linux, `examples/final_lab.rs` (`load_cover` and `glGenerateMipmap`). AGSL has no `textureLod`, so every
 * level is a texture of its own and the shader mixes two neighbours by the fractional lod (trilinear). The math only —
 * no Android API, so the tests run on the JVM.
 */

/** The side of the first level: the artwork averaged by cells to this size. */
const val FLUID_SIDE = 256

/**
 * The artwork and all its mip levels.
 *
 * @property levels the opaque ARGB ints of each level: `levels[0]` is 256 × 256, `levels[1]` 128 × 128, …,
 * the last one 1 × 1; every next level is the 2 × 2 mean of the previous one
 */
class FluidCover(val levels: List<IntArray>) {
    /** The side of the level [level] in pixels. */
    fun side(level: Int): Int = FLUID_SIDE shr level
}

/**
 * The artwork averaged by cells to [FLUID_SIDE] × [FLUID_SIDE] (as the 24 × 24 of the backdrop, only 256: the cell
 * is `[x·W/256, (x+1)·W/256)` and at least one pixel; the mean is cut, not rounded), then the mip levels by the
 * standard 2 × 2 mean (what `glGenerateMipmap` does).
 *
 * @param pixels [width] × [height] pixels as ARGB ints, row after row
 */
fun prepareFluidCover(pixels: IntArray, width: Int, height: Int): FluidCover? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null

    val side = FLUID_SIDE
    val first = IntArray(side * side) { index ->
        val x0 = index % side * width / side
        val x1 = maxOf((index % side + 1) * width / side, x0 + 1).coerceAtMost(width)
        val y0 = index / side * height / side
        val y1 = maxOf((index / side + 1) * height / side, y0 + 1).coerceAtMost(height)
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val pixel = pixels[y * width + x]
                r += pixel shr 16 and 0xFF
                g += pixel shr 8 and 0xFF
                b += pixel and 0xFF
                n++
            }
        }
        n = maxOf(n, 1)
        OPAQUE or (r / n shl 16) or (g / n shl 8) or b / n
    }

    val levels = mutableListOf(first)
    var from = first
    var fromSide = side
    while (fromSide > 1) {
        val toSide = fromSide / 2
        val next = IntArray(toSide * toSide) { index ->
            val x = index % toSide * 2
            val y = index / toSide * 2
            val a = from[y * fromSide + x]
            val b = from[y * fromSide + x + 1]
            val c = from[(y + 1) * fromSide + x]
            val d = from[(y + 1) * fromSide + x + 1]
            fun mean(shift: Int): Int {
                val sum = listOf(a, b, c, d).sumOf { it shr shift and 0xFF }
                return (sum + 2) / 4
            }
            OPAQUE or (mean(16) shl 16) or (mean(8) shl 8) or mean(0)
        }
        levels += next
        from = next
        fromSide = toSide
    }
    return FluidCover(levels)
}

private const val OPAQUE = 0xFF000000.toInt()
