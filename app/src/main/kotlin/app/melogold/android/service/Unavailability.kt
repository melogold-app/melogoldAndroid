package app.melogold.android.service

import androidx.media3.common.PlaybackException
import app.melogold.providers.innertube.requests.Playability

/**
 * Why a track does not play, from what YouTube answered (REWRITE §3.10.9): the playability check of the web client
 * ([Playability]: the country YouTube placed this device in and where the track is open) and the message of
 * yt-dlp. Null when neither says anything the person could act on; the caller keeps its generic error then.
 *
 * yt-dlp says only "Video unavailable" for a track closed in the country (the track of the report: Saba
 * "Photosynthesis", open in 122 countries but not in Russia, also behind a Helsinki VPN that Google counts as
 * Russian), so the country list decides first.
 */
fun unavailability(playability: Playability?, ytDlpMessage: String?): PlaybackException? {
    val message = ytDlpMessage.orEmpty()
    val reason = playability?.reason.orEmpty()
    return when {
        playability?.isBlockedHere == true ->
            RestrictedVideoException(playability.country, playability.availableCountries.size)

        GEO_MESSAGES.any { it in message || it in reason } ->
            RestrictedVideoException(
                country = playability?.country,
                availableCountries = playability?.availableCountries?.size?.takeIf { it > 0 }
            )

        AGE_MESSAGES.any { it in message || it in reason } -> LoginRequiredException()
        GONE_MESSAGES.any { it in message || it in reason } -> UnplayableException()
        else -> null
    }
}

private val GEO_MESSAGES = listOf(
    "available in your country",
    "not made this video available in your country",
    "blocked it in your country"
)

private val AGE_MESSAGES = listOf("confirm your age", "age-restricted", "inappropriate for some users")

private val GONE_MESSAGES = listOf(
    "Private video",
    "has been removed",
    "account associated with this video has been terminated",
    "no longer available"
)
