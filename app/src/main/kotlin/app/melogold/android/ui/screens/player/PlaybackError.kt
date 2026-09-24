package app.melogold.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import app.melogold.android.R
import app.melogold.android.service.LoginRequiredException
import app.melogold.android.service.PlayableFormatNotFoundException
import app.melogold.android.service.RestrictedVideoException
import app.melogold.android.service.UnplayableException
import app.melogold.android.service.VideoIdMismatchException
import app.melogold.android.service.isLocal
import app.melogold.android.utils.center
import app.melogold.android.utils.color
import app.melogold.android.utils.medium
import app.melogold.core.ui.LocalAppearance
import app.melogold.core.ui.onOverlay
import app.melogold.core.ui.overlay
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

@Composable
fun PlaybackError(
    isDisplayed: Boolean,
    messageProvider: @Composable () -> String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) = Box(modifier = modifier) {
    val (colorPalette, typography) = LocalAppearance.current
    val message by rememberUpdatedState(newValue = messageProvider())

    AnimatedVisibility(
        visible = isDisplayed,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Spacer(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                }
                .fillMaxSize()
                .background(Color.Black.copy(0.8f))
        )
    }

    AnimatedContent(
        targetState = message.takeIf { isDisplayed },
        transitionSpec = {
            ContentTransform(
                targetContentEnter = slideInVertically { -it },
                initialContentExit = slideOutVertically { -it },
                sizeTransform = null
            )
        },
        label = "",
        modifier = Modifier.fillMaxWidth()
    ) { currentMessage ->
        if (currentMessage != null) BasicText(
            text = currentMessage,
            style = typography.xs.center.medium.color(colorPalette.onOverlay),
            modifier = Modifier
                .background(colorPalette.overlay.copy(alpha = 0.4f))
                .padding(all = 8.dp)
                .fillMaxWidth(),
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A user-facing message for a playback [error] of [mediaItem].
 */
@Composable
fun playbackErrorMessage(mediaItem: MediaItem, error: PlaybackException?): String =
    if (mediaItem.isLocal) stringResource(R.string.error_local_music_deleted)
    else when (error?.cause?.cause) {
        is UnresolvedAddressException, is UnknownHostException ->
            stringResource(R.string.error_network)

        is PlayableFormatNotFoundException -> stringResource(R.string.error_unplayable)

        is UnplayableException -> stringResource(R.string.error_source_deleted)

        is LoginRequiredException, is RestrictedVideoException ->
            stringResource(R.string.error_server_restrictions)

        is VideoIdMismatchException -> stringResource(R.string.error_id_mismatch)

        else -> stringResource(R.string.error_unknown_playback)
    }
