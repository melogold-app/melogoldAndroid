package app.melogold.android.ui.screens.trends

import android.util.Log
import app.melogold.android.data.repo.CatalogRepository
import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.classify
import app.melogold.providers.innertube.Innertube
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

private const val TAG = "TrendsModel"
private val MAX_AGE = 24.hours

/**
 * Trends (REWRITE §3.3): the cached `explore` page at once, refreshed when it is older than a day
 * or on pull-to-refresh.
 */
class TrendsModel(private val catalog: CatalogRepository) : ScreenModel() {
    private val mutableState = MutableStateFlow<Loadable<Innertube.DiscoverPage>>(Loadable.Loading)
    val state: StateFlow<Loadable<Innertube.DiscoverPage>> = mutableState.asStateFlow()

    private var fetchedAt: Long? = null
    private var refreshJob: Job? = null

    init {
        scope.launch {
            val cached = catalog.cachedExplore()
            if (cached != null) {
                fetchedAt = cached.fetchedAt
                mutableState.value = Loadable.Content(cached.value)
            }
            val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < MAX_AGE.inWholeMilliseconds
            if (!fresh) refresh()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        Log.d(TAG, "refreshing explore")

        val current = mutableState.value
        mutableState.value = when (current) {
            is Loadable.Content -> current.copy(refreshing = true)
            else -> Loadable.Loading
        }

        refreshJob = scope.launch {
            catalog.fetchExplore().fold(
                onSuccess = { timed ->
                    fetchedAt = timed.fetchedAt
                    mutableState.value = Loadable.Content(timed.value)
                },
                onFailure = { error ->
                    val kind = classify(error)
                    Log.w(TAG, "explore refresh failed: $kind", error)
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
