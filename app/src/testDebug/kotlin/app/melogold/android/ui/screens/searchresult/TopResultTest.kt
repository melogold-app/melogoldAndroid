package app.melogold.android.ui.screens.searchresult

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** tasks/0021: the artist becomes the best result when their name is what was typed. */
class TopResultTest {
    @Test
    fun caseYoAndPunctuationDoNotMatter() {
        assertTrue(namesMatch("Кино", "  КИНО! "))
        assertTrue(namesMatch("Ёлка", "елка"))
        assertTrue(namesMatch("Tkay Maidza", "tkay   maidza"))
    }

    @Test
    fun otherNamesDoNotMatch() {
        assertFalse(namesMatch("Кино", "группа крови"))
        assertFalse(namesMatch("Кино", ""))
        assertFalse(namesMatch(null, "кино"))
    }
}
