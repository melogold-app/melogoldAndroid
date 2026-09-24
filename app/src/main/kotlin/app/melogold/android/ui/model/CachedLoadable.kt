package app.melogold.android.ui.model

import android.util.Log
import app.melogold.android.data.repo.Timed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

private const val TAG = "CachedLoadable"

/**
 * One cache-first section of a screen (REWRITE §4.11.2): the cached value is shown at once and
 * refreshed when it is older than [maxAge] or on [refresh]. A failed refresh keeps the value and
 * marks it stale (the "No network — data from 14:02" chip); without a value it is an error.
 */
class CachedLoadable<T>(
    private val scope: CoroutineScope,
    private val maxAge: Duration,
    private val name: String,
    private val cached: suspend () -> Timed<T>?,
    private val fetch: suspend () -> Result<Timed<T>>
) {
    private val mutableState = MutableStateFlow<Loadable<T>>(Loadable.Loading)
    val state: StateFlow<Loadable<T>> = mutableState.asStateFlow()

    private var fetchedAt: Long? = null
    private var refreshJob: Job? = null

    init {
        scope.launch {
            val value = cached()
            if (value != null) {
                fetchedAt = value.fetchedAt
                mutableState.value = Loadable.Content(value.value)
            }
            val fresh = value != null && System.currentTimeMillis() - value.fetchedAt < maxAge.inWholeMilliseconds
            if (!fresh) refresh()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        Log.d(TAG, "refreshing $name")

        mutableState.value = when (val current = mutableState.value) {
            is Loadable.Content -> current.copy(refreshing = true)
            else -> Loadable.Loading
        }

        refreshJob = scope.launch {
            fetch().fold(
                onSuccess = { timed ->
                    fetchedAt = timed.fetchedAt
                    mutableState.value = Loadable.Content(timed.value)
                },
                onFailure = { error ->
                    val kind = classify(error)
                    Log.w(TAG, "$name refresh failed: $kind", error)
                    mutableState.value = when (val shown = mutableState.value) {
                        is Loadable.Content -> shown.copy(
                            refreshing = false,
                            staleSince = fetchedAt,
                            staleReason = kind
                        )

                        else -> Loadable.Error(kind, error)
                    }
                }
            )
        }
    }
}
