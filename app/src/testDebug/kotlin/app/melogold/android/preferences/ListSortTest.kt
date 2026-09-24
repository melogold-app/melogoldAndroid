package app.melogold.android.preferences

import org.junit.Test
import kotlin.test.assertEquals

class ListSortTest {
    private enum class Field { DateAdded, Title }

    private val default = ListSort(Field.DateAdded, descending = true)

    @Test
    fun `a sort survives being stored`() {
        val sort = ListSort(Field.Title, descending = false)
        assertEquals(sort, sort.encode().toListSort(default))
        assertEquals(ListSort(Field.Title, descending = true), "Title:desc".toListSort(default))
    }

    @Test
    fun `nothing stored or an unknown field gives the default`() {
        assertEquals(default, "".toListSort(default))
        assertEquals(default, "Duration:asc".toListSort(default))
    }
}
