package app.melogold.android.ui.screens.player.modern

import app.melogold.domain.lyrics.LrcFormat
import app.melogold.domain.lyrics.VocalSide
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LyricRowsTest {
    @Test
    fun `interludes before the first line and in long gaps`() {
        val lyrics = LrcFormat.parse(
            """
            [00:05.00]First
            [00:07.00]
            [00:15.00]Second
            [00:17.00]Third
            """.trimIndent()
        )!!
        val rows = buildLyricRows(lyrics)

        assertEquals(5, rows.size)
        assertIs<LyricRow.Interlude>(rows[0]).also { assertEquals(0L to 5_000L, it.startMs to it.endMs) }
        assertIs<LyricRow.Sung>(rows[1])
        assertIs<LyricRow.Interlude>(rows[2]).also { assertEquals(7_000L to 15_000L, it.startMs to it.endMs) }
        assertEquals(listOf("First", "Second", "Third"), rows.filterIsInstance<LyricRow.Sung>().map { it.line.text })
    }

    @Test
    fun `filler lines become interludes or vanish`() {
        val lyrics = LrcFormat.parse(
            """
            [00:01.00]Sing
            [00:03.00]♪
            [00:10.00]Again
            [00:11.00]…
            [00:12.00]End
            """.trimIndent()
        )!!
        val rows = buildLyricRows(lyrics)

        assertEquals(listOf("Sing", "", "Again", "End"), rows.map { (it as? LyricRow.Sung)?.line?.text.orEmpty() })
        assertEquals(3_000L, rows[1].startMs)
    }

    @Test
    fun `an interlude sits on the side of the line after it`() {
        val rows = buildLyricRows(LrcFormat.parse("[00:01.00]M: First\n[00:02.00]\n[00:10.00]F: Second\n")!!)

        assertEquals(3, rows.size)
        assertEquals(VocalSide.End, assertIs<LyricRow.Interlude>(rows[1]).side)
        assertEquals(VocalSide.End, assertIs<LyricRow.Sung>(rows[2]).line.side)
    }

    @Test
    fun `the active row is the last one that started`() {
        val rows = buildLyricRows(LrcFormat.parse("[00:05.00]A\n[00:06.00]B\n")!!)

        assertEquals(-1, rows.drop(1).activeIndexAt(1_000))
        assertEquals(0, rows.activeIndexAt(1_000))
        assertEquals(1, rows.activeIndexAt(5_500))
        assertEquals(2, rows.activeIndexAt(60_000))
    }
}
