@file:OptIn(UnstableApi::class)

package app.melogold.android.service

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

class PlayableFormatNotFoundException(cause: Throwable? = null) : PlaybackException(
    /* message = */ "Playable format not found",
    /* cause = */ cause,
    /* errorCode = */ ERROR_CODE_IO_FILE_NOT_FOUND
)

class UnplayableException(cause: Throwable? = null) : PlaybackException(
    /* message = */ "Unplayable",
    /* cause = */ cause,
    /* errorCode = */ ERROR_CODE_IO_UNSPECIFIED
)

class LoginRequiredException(cause: Throwable? = null) : PlaybackException(
    /* message = */ "Login required",
    /* cause = */ cause,
    /* errorCode = */ ERROR_CODE_AUTHENTICATION_EXPIRED
)

class VideoIdMismatchException(cause: Throwable? = null) : PlaybackException(
    /* message = */ "Requested video ID doesn't match returned video ID",
    /* cause = */ cause,
    /* errorCode = */ ERROR_CODE_IO_UNSPECIFIED
)

/**
 * The rights holder closed the track in the country YouTube placed this device in.
 *
 * @property country that country (ISO code, from YouTube's answer), when known: behind a VPN it is the country
 *   YouTube counts the VPN server in, which is not always the server's own
 * @property availableCountries how many countries the track is open in, when YouTube says
 */
class RestrictedVideoException(
    val country: String? = null,
    val availableCountries: Int? = null,
    cause: Throwable? = null
) : PlaybackException(
    /* message = */ "Unavailable in ${country ?: "this country"}",
    /* cause = */ cause,
    /* errorCode = */ ERROR_CODE_PARENTAL_CONTROL_RESTRICTED
)
