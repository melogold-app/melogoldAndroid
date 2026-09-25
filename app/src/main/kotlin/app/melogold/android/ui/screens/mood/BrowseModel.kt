package app.melogold.android.ui.screens.mood

import app.melogold.android.ui.model.Loadable
import app.melogold.android.ui.model.ScreenModel
import app.melogold.android.ui.model.classify
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.requests.BrowseResult
import app.melogold.providers.innertube.requests.browse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One YouTube Music `browse` page: a mood, all moods, new releases (REWRITE §3.9). */
class BrowseModel(
    private val browseId: String,
    private val params: String? = null
) : ScreenModel() {
    private val mutablePage = MutableStateFlow<Loadable<BrowseResult>>(Loadable.Loading)
    val page: StateFlow<Loadable<BrowseResult>> = mutablePage.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutablePage.value = Loadable.Loading
        scope.launch {
            val result = withContext(Dispatchers.IO) { Innertube.browse(BrowseBody(browseId = browseId, params = params)) }
            mutablePage.value = result?.getOrNull()?.let { Loadable.Content(it) }
                ?: Loadable.Error(result?.exceptionOrNull()?.let(::classify) ?: Loadable.Error.Kind.Parser)
        }
    }
}

/** "Single · Artist · 2024": what an album of a grid or carousel is. */
fun Innertube.AlbumItem.caption() = listOfNotNull(
    typeText,
    authors?.joinToString(", ") { it.name.orEmpty() }?.takeIf { it.isNotBlank() },
    year
).joinToString(" · ").ifBlank { null }
