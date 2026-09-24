package app.melogold.android.sync

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistDiffTest {
    /** The server's reading of the item ops (API §4.8): anchors, a present track left alone by an add. */
    private fun apply(list: List<String>, changes: List<ItemChange>): List<String> {
        val items = list.toMutableList()
        fun place(after: String?, before: String?) = when {
            after != null && after in items -> items.indexOf(after) + 1
            before != null && before in items -> items.indexOf(before)
            else -> items.size
        }
        for (change in changes) when (change) {
            is ItemChange.Remove -> items.remove(change.videoId)
            is ItemChange.Add -> {
                val new = change.videoIds.filter { it !in items }
                items.addAll(place(change.after, change.before), new)
            }

            is ItemChange.Move -> if (items.remove(change.videoId)) items.add(place(change.after, change.before), change.videoId)
        }
        return items
    }

    @Test
    fun `nothing changed, nothing sent`() {
        assertEquals(emptyList<ItemChange>(), playlistItemChanges(listOf("a", "b", "c"), listOf("a", "b", "c")))
    }

    @Test
    fun `moving one track is one op`() {
        val changes = playlistItemChanges(listOf("a", "b", "c", "d"), listOf("a", "d", "b", "c"))
        assertEquals(listOf<ItemChange>(ItemChange.Move("d", after = "a", before = null)), changes)
    }

    @Test
    fun `a track moved to the start goes before the first that stays`() {
        val changes = playlistItemChanges(listOf("a", "b", "c"), listOf("c", "a", "b"))
        assertEquals(listOf<ItemChange>(ItemChange.Move("c", after = null, before = "a")), changes)
    }

    @Test
    fun `new tracks form one block after their neighbour`() {
        val changes = playlistItemChanges(listOf("a", "b"), listOf("a", "x", "y", "b", "z"))
        assertEquals(
            listOf<ItemChange>(
                ItemChange.Add(listOf("x", "y"), after = "a", before = null),
                ItemChange.Add(listOf("z"), after = "b", before = null)
            ),
            changes
        )
    }

    @Test
    fun `removed tracks are removed, others stay`() {
        val changes = playlistItemChanges(listOf("a", "b", "c"), listOf("a", "c"))
        assertEquals(listOf<ItemChange>(ItemChange.Remove("b")), changes)
    }

    @Test
    fun `a track another device added meanwhile is kept`() {
        // The server has "n" that this device never saw; this device added "x" at the end
        val server = listOf("a", "n", "b")
        val changes = playlistItemChanges(before = listOf("a", "b"), after = listOf("a", "b", "x"))
        assertEquals(listOf("a", "n", "b", "x"), apply(server, changes))
    }

    @Test
    fun `big blocks are split`() {
        val new = (1..1_200).map { "v$it" }
        val changes = playlistItemChanges(emptyList(), new)
        assertEquals(listOf(500, 500, 200), changes.map { (it as ItemChange.Add).videoIds.size })
        assertEquals(new, apply(emptyList(), changes))
    }

    @Test
    fun `any edit reaches the same list`() {
        val random = Random(42)
        repeat(2_000) {
            val before = (0 until random.nextInt(0, 30)).map { "t$it" }.shuffled(random)
            val after = (before.filter { random.nextInt(4) != 0 } + (0 until random.nextInt(0, 8)).map { "n$it" })
                .shuffled(random)
                .let { list -> if (random.nextBoolean()) list else list.sortedBy { before.indexOf(it).let { i -> if (i < 0) 99 else i } } }
            assertEquals("$before → $after", after, apply(before, playlistItemChanges(before, after)))
        }
    }

    @Test
    fun `the longest run is found`() {
        val values = listOf(3, 1, 2, 0, 4, 5, 1)
        val run = longestIncreasingRun(values).map { values[it] }
        assertEquals(4, run.size)
        assertEquals(run.sorted().distinct(), run)
        assertEquals(emptyList<Int>(), longestIncreasingRun(emptyList()))
    }
}
