package app.melogold.android.ui.theme

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same scenarios as the tests of `melogold-core` `artwork_colors` on Linux (tasks/0028). */
class ArtworkBackdropTest {
    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    private fun image(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int) =
        IntArray(width * height) { 0xFF000000.toInt() or pixel(it % width, it / width) }

    private fun lightness(rgb: Int) = toLab(rgb)[0]

    /** A black cover with a thin rainbow (like "The Dark Side of the Moon"): black dominates, not red as Score chose. */
    @Test
    fun `a black cover with a thin rainbow is mostly black`() {
        val rainbow = intArrayOf(rgb(230, 30, 30), rgb(240, 200, 20), rgb(30, 160, 60), rgb(40, 90, 220))
        val pixels = image(200, 200) { x, y ->
            if (y in 90 until 110 && x > 100) rainbow[(y - 90) / 5] else rgb(2, 2, 2)
        }

        val shares = artworkShares(pixels, 200, 200)
        val first = shares.first()

        assertTrue(lightness(first.rgb) < 10.0, "black first: $shares")
        assertTrue(first.share > 0.85, "$shares")
        assertTrue(shares.size >= 3, "the colors of the rainbow are there too, in small shares: $shares")
        assertTrue(abs(shares.sumOf { it.share } - 1.0) < 1e-9)
    }

    @Test
    fun `a plain wide cover is one color`() {
        val shares = artworkShares(image(320, 180) { _, _ -> rgb(200, 40, 40) }, 320, 180)

        assertEquals(1, shares.size)
        assertEquals(1.0, shares.single().share, 1e-9)
    }

    @Test
    fun `two halves with a grain are a half each`() {
        // A little grain, as in a real picture: the quantizer works on many shades, not on two exact colors
        fun grain(x: Int, y: Int) = (x * 7 + y * 13) % 9
        val pixels = image(100, 100) { x, y ->
            if (x < 50) rgb(20 + grain(x, y), 60 + grain(y, x), 200 - grain(x, y)) else rgb(235 + grain(y, x), 235, 235)
        }

        val shares = artworkShares(pixels, 100, 100)

        assertEquals(2, shares.size, "$shares")
        assertTrue(shares.all { abs(it.share - 0.5) < 0.05 }, "$shares")
        val blue = toLab(shares.minBy { lightness(it.rgb) }.rgb)
        assertTrue(blue[2] < -40.0, "the darker half is blue: ${blue.toList()}")
    }

    @Test
    fun `an empty or short image has no colors`() {
        assertTrue(artworkShares(IntArray(0), 0, 0).isEmpty())
        assertTrue(artworkShares(IntArray(10), 100, 100).isEmpty())
    }

    @Test
    fun `backdrop keeps white text readable`() {
        assertTrue(lightness(darkBackdrop(0xFFFFFF)) <= 38.5, "a white cover - a grey backdrop")
        assertTrue(lightness(darkBackdrop(0x020202)) >= 5.5, "a black one - almost black, not a pit")
        val red = toLab(darkBackdrop(0xE01E1E))
        assertTrue(red[1] > 20.0, "red stays red: ${red.toList()}")
        val white = toLab(darkBackdrop(0xFFFFFF))
        assertTrue(abs(white[1]) < 1.0 && abs(white[2]) < 1.0, "white has no hue: ${white.toList()}")
    }

    @Test
    fun `lab round trip`() {
        for (color in intArrayOf(0x000000, 0xFFFFFF, 0xE01E1E, 0x2D5AE0, 0x7F7F7F, 0x12AB34)) {
            val back = fromLab(toLab(color))
            for (shift in intArrayOf(16, 8, 0)) {
                val a = color shr shift and 0xFF
                val b = back shr shift and 0xFF
                assertTrue(abs(a - b) <= 1, "%06x -> %06x".format(color, back))
            }
        }
    }

    @Test
    fun `the small cover is 24 by 24 and keeps a plain color`() {
        val cover = prepareBackdropCover(image(300, 200) { _, _ -> rgb(100, 150, 200) }, 300, 200)!!

        assertEquals(BACKDROP_SIDE * BACKDROP_SIDE, cover.pixels.size)
        assertTrue(cover.pixels.all { it == (0xFF000000.toInt() or rgb(100, 150, 200)) })
        assertEquals((0.2126 * 100 + 0.7152 * 150 + 0.0722 * 200) / 255.0, cover.luma.toDouble(), 1e-4)
    }

    @Test
    fun `blur softens an edge but luma is taken before it`() {
        // Black left half, white right half
        val cover = prepareBackdropCover(image(240, 240) { x, _ -> if (x < 120) 0 else rgb(255, 255, 255) }, 240, 240)!!
        val row = 12 * BACKDROP_SIDE
        val reds = IntArray(BACKDROP_SIDE) { cover.pixels[row + it] shr 16 and 0xFF }

        assertEquals(0.5f, cover.luma, 1e-4f)
        assertEquals(0, reds.first())
        assertEquals(255, reds.last())
        // Without the blur the middle two cells would jump 0 -> 255; with it they climb
        assertTrue(reds[11] in 1..254 && reds[12] in 1..254, "${reds.toList()}")
        assertTrue(reds.toList().zipWithNext().all { (a, b) -> a <= b }, "${reds.toList()}")
    }

    @Test
    fun `a light cover is dimmed and a dark one is not`() {
        assertEquals(1f, backdropBrightness(0.1f))
        assertEquals(1f, backdropBrightness(0.3f), 1e-6f)
        assertEquals(0.5f, backdropBrightness(0.6f), 1e-6f)
        assertEquals(0.3f, backdropBrightness(1f), 1e-6f)
        assertEquals(1f, backdropBrightness(0f))
    }

    @Test
    fun `color matrix keeps grey grey and saturates colors`() {
        val m = backdropColorMatrix(brightness = 0.5f)
        fun apply(r: Float, g: Float, b: Float) = floatArrayOf(
            m[0] * r + m[1] * g + m[2] * b,
            m[5] * r + m[6] * g + m[7] * b,
            m[10] * r + m[11] * g + m[12] * b
        )

        val grey = apply(0.4f, 0.4f, 0.4f)
        grey.forEach { assertEquals(0.2f, it, 1e-5f) }
        // A saturated red: the red gets further from the grey of the same luma
        val red = apply(0.8f, 0.2f, 0.2f)
        assertTrue(red[0] - red[1] > 0.5f * (0.8f - 0.2f), "${red.toList()}")
        // The alpha is left alone
        assertEquals(1f, m[18])
    }
}
