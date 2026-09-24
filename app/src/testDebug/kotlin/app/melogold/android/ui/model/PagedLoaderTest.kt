package app.melogold.android.ui.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PagedLoaderTest {
    @Test
    fun `a failed first page is loaded again on retry`() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val loader = PagedLoader(
            scope = backgroundScope,
            key = { it },
            first = {
                calls++
                if (calls == 1) Result.failure(UnknownHostException())
                else Result.success(Page(listOf("a", "b"), continuation = null))
            },
            next = { error("no next page") }
        )

        assertEquals(Loadable.Error.Kind.Offline, loader.state.value.error)

        loader.retry()

        assertEquals(2, calls)
        assertEquals(listOf("a", "b"), loader.state.value.items)
        assertNull(loader.state.value.error)
        assertTrue(loader.state.value.end)
    }

    @Test
    fun `pages follow their tokens to the end, without duplicates`() = runTest(UnconfinedTestDispatcher()) {
        val loader = PagedLoader(
            scope = backgroundScope,
            key = { it },
            first = { Result.success(Page(listOf("a", "b"), continuation = "1")) },
            next = { token ->
                when (token) {
                    "1" -> Result.success(Page(listOf("b", "c"), continuation = "2"))
                    else -> Result.success(Page(listOf("d"), continuation = null))
                }
            }
        )

        loader.loadMore()
        loader.loadMore()
        loader.loadMore()

        assertEquals(listOf("a", "b", "c", "d"), loader.state.value.items)
        assertTrue(loader.state.value.end)
    }

    @Test
    fun `walking the list with awaitNext reaches the end and stops`() = runTest(UnconfinedTestDispatcher()) {
        val loader = PagedLoader(
            scope = backgroundScope,
            key = { it },
            first = { Result.success(Page(listOf("a"), continuation = "1")) },
            next = { token ->
                // A page of duplicates only must not stall the walk
                if (token == "1") Result.success(Page(listOf("a"), continuation = "2"))
                else Result.success(Page(listOf("b"), continuation = null))
            }
        )

        var steps = 0
        while (loader.awaitNext()) steps++

        assertEquals(listOf("a", "b"), loader.state.value.items)
        assertTrue(loader.state.value.end)
        assertTrue(steps <= 3, "steps: $steps")
    }
}
