package app.melogold.android.ui.theme

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** tasks/0029 §1: the 256 × 256 copy and the mip chain by the standard 2 × 2 mean. */
class FluidCoverTest {
    private fun rgb(r: Int, g: Int, b: Int) = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = c shr 16 and 0xFF
    private fun green(c: Int) = c shr 8 and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun `the chain goes from 256 down to one pixel`() {
        val cover = assertNotNull(prepareFluidCover(IntArray(512 * 512) { rgb(10, 20, 30) }, 512, 512))

        assertEquals(9, cover.levels.size)
        cover.levels.forEachIndexed { level, pixels ->
            assertEquals(cover.side(level) * cover.side(level), pixels.size)
            assertEquals(256 shr level, cover.side(level))
        }
        assertEquals(1, cover.levels.last().size)
        assertEquals(rgb(10, 20, 30), cover.levels.last().single())
    }

    @Test
    fun `a cell of the first level is the mean of its pixels, cut`() {
        // 512 × 512: a cell is 2 × 2; the top left cell is 10, 11, 12, 13 → 11.5, cut to 11
        val pixels = IntArray(512 * 512) { rgb(0, 0, 0) }
        pixels[0] = rgb(10, 0, 0)
        pixels[1] = rgb(11, 0, 0)
        pixels[512] = rgb(12, 0, 0)
        pixels[513] = rgb(13, 0, 0)
        val cover = assertNotNull(prepareFluidCover(pixels, 512, 512))

        assertEquals(11, red(cover.levels[0][0]))
        assertEquals(0, red(cover.levels[0][1]))
    }

    @Test
    fun `a small artwork is stretched over the cells, each cell has at least one pixel`() {
        val pixels = IntArray(64 * 64) { i -> if (i % 64 < 32) rgb(255, 0, 0) else rgb(0, 0, 255) }
        val cover = assertNotNull(prepareFluidCover(pixels, 64, 64))
        val first = cover.levels[0]

        assertEquals(rgb(255, 0, 0), first[0])
        assertEquals(rgb(255, 0, 0), first[127])
        assertEquals(rgb(0, 0, 255), first[128])
        assertEquals(rgb(0, 0, 255), first[255])
        // Not square: 128 × 32 → the rows are stretched too
        val wide = assertNotNull(prepareFluidCover(IntArray(128 * 32) { rgb(1, 2, 3) }, 128, 32))
        assertEquals(256 * 256, wide.levels[0].size)
    }

    @Test
    fun `every next level is the 2 x 2 mean of the previous one`() {
        val pixels = IntArray(256 * 256) { i -> rgb(i % 256, i / 256, (i * 7) % 256) }
        val cover = assertNotNull(prepareFluidCover(pixels, 256, 256))

        for (level in 1 until cover.levels.size) {
            val side = cover.side(level)
            val from = cover.levels[level - 1]
            val fromSide = cover.side(level - 1)
            for (y in 0 until side) for (x in 0 until side) {
                val cells = listOf(
                    from[2 * y * fromSide + 2 * x], from[2 * y * fromSide + 2 * x + 1],
                    from[(2 * y + 1) * fromSide + 2 * x], from[(2 * y + 1) * fromSide + 2 * x + 1]
                )
                val got = cover.levels[level][y * side + x]
                assertEquals((cells.sumOf { red(it) } + 2) / 4, red(got), "level $level $x,$y")
                assertEquals((cells.sumOf { green(it) } + 2) / 4, green(got))
                assertEquals((cells.sumOf { blue(it) } + 2) / 4, blue(got))
            }
        }
    }

    @Test
    fun `the last level is about the mean of the whole artwork and all levels are opaque`() {
        val pixels = IntArray(300 * 200) { i -> if ((i % 300 + i / 300) % 2 == 0) rgb(200, 100, 0) else rgb(0, 100, 200) }
        val cover = assertNotNull(prepareFluidCover(pixels, 300, 200))
        val mean = cover.levels.last().single()

        assertTrue(red(mean) in 90..110 && green(mean) == 100 && blue(mean) in 90..110, "mean ${red(mean)} ${green(mean)} ${blue(mean)}")
        assertTrue(cover.levels.all { level -> level.all { it ushr 24 == 0xFF } })
    }

    @Test
    fun `an empty artwork is nothing`() {
        assertNull(prepareFluidCover(IntArray(0), 0, 0))
        assertNull(prepareFluidCover(IntArray(10), 8, 8))
    }
}
