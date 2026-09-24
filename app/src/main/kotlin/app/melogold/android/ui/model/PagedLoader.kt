package app.melogold.android.ui.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A page of an endless list and the token of the next one (null at the end).
 */
data class Page<T>(val items: List<T>, val continuation: String?)

/**
 * The state of an endless list: what is loaded so far, whether more is loading, the error of the
 * last page and whether the end was reached.
 */
data class Paged<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = true,
    val error: Loadable.Error.Kind? = null,
    val end: Boolean = false
)

/**
 * Loads an endless list page by page (search results): the first page at once, the next one on
 * [loadMore]; duplicates by [key] are dropped. A failed page keeps what is loaded and waits for
 * [retry].
 */
class PagedLoader<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> String,
    private val first: suspend () -> Result<Page<T>>?,
    private val next: suspend (String) -> Result<Page<T>>?
) {
    private val mutableState = MutableStateFlow(Paged<T>())
    val state: StateFlow<Paged<T>> = mutableState.asStateFlow()

    private var continuation: String? = null
    private var started = false
    private var job: Job? = null

    init {
        load()
    }

    fun loadMore() {
        val current = mutableState.value
        if (job?.isActive == true || current.end || current.error != null) return
        load()
    }

    fun retry() {
        if (job?.isActive == true) return
        mutableState.update { it.copy(error = null) }
        load()
    }

    private fun load() {
        val token = continuation
        if (started && token == null) return
        started = true

        job = scope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            val result = (if (token == null) first() else next(token)) ?: return@launch

            result.fold(
                onSuccess = { page ->
                    continuation = page.continuation
                    mutableState.update { state ->
                        state.copy(
                            items = (state.items + page.items).distinctBy(key),
                            loading = false,
                            end = page.continuation == null
                        )
                    }
                },
                onFailure = { error ->
                    mutableState.update { it.copy(loading = false, error = classify(error)) }
                }
            )
        }
    }
}
