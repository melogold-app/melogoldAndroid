package app.melogold.android.ui.screens.whatsnew

import app.melogold.android.data.foryou.ForYouBuilder
import app.melogold.android.data.repo.CatalogRepository
import app.melogold.android.ui.model.CachedLoadable
import app.melogold.android.ui.model.ScreenModel
import kotlin.time.Duration.Companion.hours

/**
 * New (REWRITE §3.4): new releases from the shared `explore` cache and the personal picks, each
 * loaded, cached and failing on its own.
 */
class WhatsNewModel(
    catalog: CatalogRepository,
    forYou: ForYouBuilder
) : ScreenModel() {
    private val releases = CachedLoadable(
        scope = scope,
        maxAge = 24.hours,
        name = "explore",
        cached = catalog::cachedExplore,
        fetch = catalog::fetchExplore
    )

    private val picks = CachedLoadable(
        scope = scope,
        maxAge = 6.hours,
        name = "for you",
        cached = forYou::cached,
        fetch = forYou::build
    )

    val releasesState = releases.state
    val picksState = picks.state

    fun refreshReleases() = releases.refresh()
    fun refreshPicks() = picks.refresh()

    fun refresh() {
        releases.refresh()
        picks.refresh()
    }
}
