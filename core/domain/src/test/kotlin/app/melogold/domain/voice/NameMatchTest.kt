package app.melogold.domain.voice

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Names as a person says them: exact beats whole words beats a part; case, «ё» and punctuation do not count. */
class NameMatchTest {
    @Test
    fun `case, ё, punctuation and spaces do not count`() {
        assertEquals("еще раз 2 0", NameMatch.normalize("  ЕЩЁ   раз!  2.0 "))
        assertEquals(MatchLevel.Exact, NameMatch.level("Ещё раз", null, "еще РАЗ"))
        assertEquals(MatchLevel.Exact, NameMatch.level("AC/DC", null, "ac dc"))
    }

    @Test
    fun `the artist may come before or after the name`() {
        assertEquals(MatchLevel.Exact, NameMatch.level("Группа крови", "Кино", "группа крови кино"))
        assertEquals(MatchLevel.Exact, NameMatch.level("Группа крови", "Кино", "Кино — Группа крови"))
    }

    @Test
    fun `whole words beat a part of a word`() {
        assertEquals(MatchLevel.Prefix, NameMatch.level("Дорога на дачу", null, "дорога"))
        assertEquals(MatchLevel.Prefix, NameMatch.level("Кино", null, "кино 1988"))
        assertEquals(MatchLevel.Contains, NameMatch.level("Кинолог", null, "кино"))
        assertEquals(MatchLevel.Contains, NameMatch.level("Лучшее", null, "моё лучшее за год"))
        assertEquals(MatchLevel.Words, NameMatch.level("Звезда по имени Солнце", "Кино", "кино солнце"))
        assertNull(NameMatch.level("Хочу перемен", "Кино", "звезда"))
        assertNull(NameMatch.level("Хочу перемен", null, "  "))
    }

    @Test
    fun `a name matches inside what was said only as whole words`() {
        assertEquals(MatchLevel.Contains, NameMatch.level("Би-2", null, "включи би 2 пожалуйста"))
        assertEquals(MatchLevel.Contains, NameMatch.level("Я", null, "это я и ты"))
        assertNull(NameMatch.level("Я", null, "яблоко"))
        assertNull(NameMatch.level("Ска", null, "русская классика"))
        assertNull(NameMatch.level("Рок", null, "роковые хиты"))
        assertNull(NameMatch.level("Car", null, "Carpenters best"))
    }

    @Test
    fun `the best match wins, the first of equal ones`() {
        val first = VoiceCollection(CollectionKind.Album, "1", "Группа крови (Remastered)", "Кино")
        val exact = VoiceCollection(CollectionKind.Album, "2", "Группа крови", "Кино")
        val again = VoiceCollection(CollectionKind.Album, "3", "Группа крови", "Кино")

        assertEquals(exact to MatchLevel.Exact, NameMatch.best(listOf(first, exact, again), "группа крови"))
        assertEquals(first to MatchLevel.Prefix, NameMatch.best(listOf(first), "группа крови"))
        assertNull(NameMatch.best(listOf(first, exact), "звезда"))
    }

    @Test
    fun `the library is asked for the longest word`() {
        assertEquals("звезда", NameMatch.keyWord("Звезда по имени Солнце"))
        assertNull(NameMatch.keyWord(" !? "))
    }
}
