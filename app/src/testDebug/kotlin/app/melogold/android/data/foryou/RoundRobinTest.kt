package app.melogold.android.data.foryou

import org.junit.Test
import kotlin.test.assertEquals

class RoundRobinTest {
    @Test
    fun `takes one item from every list in turn`() {
        assertEquals(
            listOf("a1", "b1", "c1", "a2", "c2", "a3"),
            roundRobin(listOf(listOf("a1", "a2", "a3"), listOf("b1"), listOf("c1", "c2")))
        )
    }

    @Test
    fun `is empty without lists or items`() {
        assertEquals(emptyList(), roundRobin(emptyList<List<String>>()))
        assertEquals(emptyList(), roundRobin(listOf(emptyList<String>(), emptyList())))
    }
}
