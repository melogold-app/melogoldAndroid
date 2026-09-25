package app.melogold.providers.innertube.models.bodies

import app.melogold.providers.innertube.models.Context
import kotlinx.serialization.Serializable

/**
 * A browse on plain YouTube: a channel tab ([browseId] and its [params]) or a [continuation].
 */
@Serializable
data class WebBrowseBody(
    val context: Context = Context.DefaultYouTubeWeb,
    val browseId: String? = null,
    val params: String? = null,
    val continuation: String? = null
)
