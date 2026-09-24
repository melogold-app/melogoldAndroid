package app.melogold.android.ui.screens.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import app.melogold.android.R
import app.melogold.android.service.LoginRequiredException
import app.melogold.android.service.PlayableFormatNotFoundException
import app.melogold.android.service.RestrictedVideoException
import app.melogold.android.service.UnplayableException
import app.melogold.android.service.VideoIdMismatchException
import app.melogold.android.service.isLocal
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * The error of the playing track in place of the artwork (REWRITE §3.10.9): what went wrong and
 * "Retry · Skip · Other versions".
 */
@Composable
fun PlaybackErrorCard(
    isDisplayed: Boolean,
    message: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onOtherVersions: (() -> Unit)?,
    modifier: Modifier = Modifier
) = AnimatedVisibility(
    visible = isDisplayed,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxSize()
            .testTag("player_error")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ms_error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(onClick = onRetry) { Text(text = stringResource(R.string.retry)) }
                TextButton(onClick = onSkip) { Text(text = stringResource(R.string.player_skip)) }
                onOtherVersions?.let {
                    TextButton(onClick = it) { Text(text = stringResource(R.string.other_versions)) }
                }
            }
        }
    }
}

/**
 * A user-facing message for a playback [error] of [mediaItem], by the error classes of REWRITE
 * §3.10.9; the cause chain is searched, as ExoPlayer wraps what the data source threw.
 */
@OptIn(UnstableApi::class)
@Composable
fun playbackErrorMessage(mediaItem: MediaItem, error: PlaybackException?): String {
    if (mediaItem.isLocal) return stringResource(R.string.error_local_music_deleted)

    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    fun has(predicate: (Throwable) -> Boolean) = causes.any(predicate)

    return stringResource(
        when {
            has { it is SocketTimeoutException } -> R.string.player_error_timeout
            has { it is UnresolvedAddressException || it is UnknownHostException || it is ConnectException } ->
                R.string.player_error_network
            has { it is RestrictedVideoException } -> R.string.player_error_geo
            has { it is LoginRequiredException } -> R.string.player_error_age
            has { it is UnplayableException } -> R.string.player_error_unavailable
            // An expired stream URL or a bot check answers 403/410: not the user's network
            has {
                it is PlayableFormatNotFoundException || it is VideoIdMismatchException ||
                    it is HttpDataSource.InvalidResponseCodeException
            } -> R.string.player_error_extractor
            error?.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error?.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                R.string.player_error_network
            has { it is IOException } -> R.string.player_error_network
            else -> R.string.player_error_extractor
        }
    )
}
