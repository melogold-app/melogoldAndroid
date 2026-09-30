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
 *
 * The bot check (2026-09-30: every client, every track, from a Dutch VPN server) goes before age: both start with
 * "Sign in to confirm". YouTube words the reason in the language asked, yt-dlp always in English.
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

        BOT_MESSAGES.any { it in message || it in reason } -> BotCheckException()
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

/**
 * YouTube's bot check in a `player` answer: its words, or `LOGIN_REQUIRED` that is neither about age nor a private
 * video (the stream clients ask in English, the web client in the device's language).
 */
fun isBotCheck(status: String?, reason: String?): Boolean {
    val text = reason.orEmpty()
    if (BOT_MESSAGES.any { it in text }) return true
    return status == "LOGIN_REQUIRED" && AGE_MESSAGES.none { it in text } && PRIVATE_MESSAGES.none { it in text }
}

private val BOT_MESSAGES = listOf("not a bot", "не бот")

private val PRIVATE_MESSAGES = listOf("Private video", "private", "частное")

private val AGE_MESSAGES = listOf("confirm your age", "age-restricted", "inappropriate for some users")

private val GONE_MESSAGES = listOf(
    "Private video",
    "has been removed",
    "account associated with this video has been terminated",
    "no longer available"
)
