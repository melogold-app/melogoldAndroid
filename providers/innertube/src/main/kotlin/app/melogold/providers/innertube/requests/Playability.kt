package app.melogold.providers.innertube.requests

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.Context
import app.melogold.providers.innertube.models.PlayerResponse
import app.melogold.providers.innertube.models.bodies.PlayerBody
import app.melogold.providers.innertube.utils.visitorCountry
import app.melogold.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody

/**
 * Why YouTube does not play a video, as YouTube itself says it: asked when getting a stream failed.
 *
 * @property status `OK`, `UNPLAYABLE`, `LOGIN_REQUIRED`, `ERROR`…
 * @property country the country YouTube placed this device in (from `visitorData`), e.g. `RU` behind a VPN it
 *   counts as Russian
 * @property availableCountries where the rights holder opened the video; empty when YouTube does not say
 */
data class Playability(
    val status: String?,
    val reason: String?,
    val country: String?,
    val availableCountries: List<String>
) {
    /** The rights holder closed the video in the country YouTube placed this device in. */
    val isBlockedHere: Boolean
        get() = country != null && availableCountries.isNotEmpty() && country !in availableCountries
}

/**
 * One `player` request of the web client, which answers with the country list even for a video it will not play.
 * No validity check: the answer is the diagnosis.
 */
suspend fun Innertube.playability(videoId: String): Result<Playability>? = runCatchingCancellable {
    val context = Context.DefaultYouTubeWeb
    val response = client.post(PLAYER) {
        setBody(PlayerBody(context = context, videoId = videoId))
        context.apply()
        header("X-Goog-Api-Format-Version", "2")
    }.body<PlayerResponse>()
    Playability(
        status = response.playabilityStatus?.status,
        reason = response.playabilityStatus?.reason,
        country = visitorCountry(response.responseContext?.visitorData),
        availableCountries = response.microformat?.availableCountries.orEmpty()
    )
}
