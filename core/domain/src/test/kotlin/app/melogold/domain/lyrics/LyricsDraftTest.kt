package app.melogold.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsDraftTest {
    @Test
    fun `text becomes lines, with backing vocals in parentheses at the end`() {
        val draft = LyricsDraft.fromText("First line\n\n  Second line (ooh, ooh)  \nThird (not) backing here\n")

        assertEquals(listOf("First line", "Second line", "Third (not) backing here"), draft.lines.map { it.text })
        assertEquals("(ooh, ooh)", draft.lines[1].backing)
        assertNull(draft.lines[2].backing)
        assertEquals("First line\nSecond line (ooh, ooh)\nThird (not) backing here", draft.toText())
    }

    @Test
    fun `marks time the lines in order and a line ends where the next one starts`() {
        val draft = LyricsDraft.fromText("One\nTwo\nThree").mark(1_000).mark(4_000).mark(8_000)

        assertEquals(3, draft.cursor)
        assertTrue(draft.complete)

        val lyrics = assertNotNull(draft.toSyncedLyrics())
        assertEquals(LyricsTiming.Line, lyrics.timing)
        assertEquals(listOf(1_000L to 4_000L, 4_000L to 8_000L, 8_000L to 13_000L), lyrics.lines.map { it.startMs to it.endMs })
    }

    @Test
    fun `an explicit end leaves a gap before the next line`() {
        val draft = LyricsDraft.fromText("One\nTwo").mark(1_000).markEnd(3_000).mark(9_000)
        val lyrics = assertNotNull(draft.toSyncedLyrics())

        assertEquals(1_000L to 3_000L, lyrics.lines[0].startMs to lyrics.lines[0].endMs)
        assertEquals(9_000L, lyrics.lines[1].startMs)
    }

    @Test
    fun `re-marking a line after its gap was closed keeps only real gaps`() {
        // The end at 3 s is dropped when the next line is marked earlier than it
        val draft = LyricsDraft.fromText("One\nTwo").mark(1_000).markEnd(3_000).mark(2_000)

        assertNull(draft.lines[0].endMs)
        assertEquals(1_000L to 2_000L, draft.toSyncedLyrics()!!.lines[0].let { it.startMs to it.endMs })
    }

    @Test
    fun `word mode times every word, then moves to the next line`() {
        val draft = LyricsDraft.fromText("a b c\nd", language = "en")
            .copy(timing = LyricsTiming.Word)
            .mark(100).mark(200).mark(300)

        assertEquals(1, draft.cursor)
        assertEquals(0, draft.wordCursor)

        val lyrics = draft.mark(1_000).toSyncedLyrics()!!
        assertEquals(LyricsTiming.Word, lyrics.timing)
        assertEquals("en", lyrics.language)
        assertEquals(
            listOf(SyncedWord(100, 200, "a "), SyncedWord(200, 300, "b "), SyncedWord(300, 1_000, "c")),
            lyrics.lines[0].words
        )
    }

    @Test
    fun `lines only partly timed by word fall back to line timing`() {
        val lyrics = LyricsDraft.fromText("a b\nc").copy(timing = LyricsTiming.Word)
            .mark(100) // only the first word
            .movedTo(1).mark(2_000)
            .toSyncedLyrics()!!

        assertEquals(LyricsTiming.Line, lyrics.timing)
        assertTrue(lyrics.lines.all { it.words.isEmpty() })
    }

    @Test
    fun `a line on the end side makes a duet of two singers`() {
        val lyrics = LyricsDraft.fromText("One\nTwo").mark(0).mark(2_000).withSide(1, VocalSide.End).toSyncedLyrics()!!

        assertEquals(listOf("v1", "v2"), lyrics.agents.map { it.id })
        assertEquals(listOf("v1", "v2"), lyrics.lines.map { it.agent })
        assertTrue(lyrics.isDuet)
    }

    @Test
    fun `editing the text keeps the timing of unchanged lines`() {
        val draft = LyricsDraft.fromText("One\nTwo\nThree").mark(1_000).mark(2_000).mark(3_000)
            .withText("One\nNew line\nTwo\nThree (yeah)")

        assertEquals(listOf(1_000L, null, 2_000L, null), draft.lines.map { it.startMs })
        assertEquals("(yeah)", draft.lines[3].backing)
        assertEquals(1, draft.cursor)
    }

    @Test
    fun `nudging moves a line and its words`() {
        val draft = LyricsDraft.fromText("a b").copy(timing = LyricsTiming.Word).mark(1_000).mark(1_500)
            .nudge(0, -100)

        assertEquals(900L, draft.lines[0].startMs)
        assertEquals(listOf(900L, 1_400L), draft.lines[0].wordStarts)
        assertEquals(0L, draft.nudge(0, -5_000).lines[0].startMs)
    }

    @Test
    fun `a start offset shifts every time`() {
        val draft = LyricsDraft.fromText("a b\nc").copy(timing = LyricsTiming.Word)
            .mark(0).mark(500).mark(2_000)
            .shiftedBy(1_500)

        assertEquals(listOf(1_500L, 3_500L), draft.lines.map { it.startMs })
        assertEquals(listOf(1_500L, 2_000L), draft.lines[0].wordStarts)
    }

    @Test
    fun `synced lyrics round trip through the draft and TTML`() {
        val lyrics = LyricsDraft.fromText("Hello there\nGeneral Kenobi (you are a bold one)")
            .copy(timing = LyricsTiming.Word, language = "en")
            .mark(1_000).mark(1_600)
            .mark(3_000).mark(3_700)
            .withSide(1, VocalSide.End)
            .toSyncedLyrics()!!

        val parsed = assertNotNull(TtmlFormat.parse(TtmlFormat.write(lyrics)))
        assertEquals(lyrics.lines.map { it.text }, parsed.lines.map { it.text })
        assertEquals(lyrics.lines.map { it.words }, parsed.lines.map { it.words })
        assertEquals("(you are a bold one)", parsed.lines[1].background?.text)
        assertEquals(VocalSide.End, parsed.lines[1].side)

        val again = LyricsDraft.from(parsed)
        assertEquals(listOf(1_000L, 3_000L), again.lines.map { it.startMs })
        assertEquals(listOf(3_000L, 3_700L), again.lines[1].wordStarts)
        assertEquals("(you are a bold one)", again.lines[1].backing)
        assertEquals(2, again.cursor)
    }
}
