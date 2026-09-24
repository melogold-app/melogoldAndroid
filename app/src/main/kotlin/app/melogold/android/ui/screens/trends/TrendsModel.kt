package app.melogold.android.ui.screens.trends

import app.melogold.android.data.repo.CatalogRepository
import app.melogold.android.ui.model.CachedLoadable
import app.melogold.android.ui.model.ScreenModel
import kotlin.time.Duration.Companion.hours

/**
 * Trends (REWRITE §3.3): the cached `explore` page at once, refreshed when it is older than a day
 * or on pull-to-refresh.
 */
class TrendsModel(catalog: CatalogRepository) : ScreenModel() {
    private val explore = CachedLoadable(
        scope = scope,
        maxAge = 24.hours,
        name = "explore",
        cached = catalog::cachedExplore,
        fetch = catalog::fetchExplore
    )

    val state = explore.state

    fun refresh() = explore.refresh()
}
