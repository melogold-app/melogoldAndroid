package app.melogold.android.ui.screens.player.modern

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NowPlayingSizingTest {
    @Test
    fun `a lyrics line is 4,2 percent of the height between 18 and 64`() {
        assertEquals(18f, lyricsLineSize(300f))
        assertEquals(0.042f * 800f, lyricsLineSize(800f), 1e-4f)
        assertEquals(0.042f * 1000f, lyricsLineSize(1000f), 1e-4f)
        assertEquals(64f, lyricsLineSize(3000f))
    }

    @Test
    fun `the size does not depend on the width, only on the height`() {
        // The same height gives the same line in a narrow window and in a wide one: there is no width in the function
        assertEquals(lyricsLineSize(900f), lyricsLineSize(900f))
        assertTrue(lyricsLineSize(900f) > lyricsLineSize(700f))
    }

    @Test
    fun `the wide cover is half the height or 38 percent of the width, the smaller`() {
        assertEquals(400f, wideCoverSide(areaHeight = 800f, areaWidth = 1280f), 1e-3f)
        assertEquals(0.38f * 800f, wideCoverSide(areaHeight = 800f, areaWidth = 800f), 1e-3f)
    }

    @Test
    fun `about 28 characters make a wide line`() {
        assertEquals(28 * 0.54f * 30f, wideLyricsTextWidth(30f), 1e-3f)
    }
}
