package app.melogold.providers.innertube.models.bodies

import app.melogold.providers.innertube.models.Context
import kotlinx.serialization.Serializable

/**
 * A search on plain YouTube: a first page ([query], optional [params]) or a [continuation].
 */
@Serializable
data class WebSearchBody(
    val context: Context = Context.DefaultYouTubeWeb,
    val query: String? = null,
    val params: String? = null,
    val continuation: String? = null
)
