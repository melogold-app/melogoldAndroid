@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.melogold.android.R
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.modifiers.onSwipe
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.positionAndDurationState
import kotlin.math.absoluteValue

/**
 * The mini player (REWRITE §3.10.1): 64 dp on `surfaceContainerHigh` with 16 dp top corners,
 * artwork, title and artist, play/pause and next, the progress as a 2 dp line along the top edge.
 * Tap expands, a sideways swipe changes the track, a long tap opens the track menu; swiping down
 * (the sheet) stops playback.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    binder: PlayerService.Binder?,
    metadata: MediaMetadata?,
    explicit: Boolean,
    shouldBePlaying: Boolean,
    onExpand: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null
) {
    val player = binder?.player
    val (position, duration) = player.positionAndDurationState()
    val progress = if (duration > 0) (position.toFloat() / duration.absoluteValue).coerceIn(0f, 1f) else 0f
    val progressColor = MaterialTheme.colorScheme.primary

    val previousLabel = stringResource(R.string.skip_back)
    val nextLabel = stringResource(R.string.skip_forward)
    val menuLabel = stringResource(R.string.more_options)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = modifier.drawBehind {
                drawRect(
                    color = progressColor,
                    topLeft = Offset.Zero,
                    size = Size(width = size.width * progress, height = 2.dp.toPx())
                )
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .onSwipe(
                        animateOffset = true,
                        onSwipeLeft = { player?.forceSeekToNext() },
                        onSwipeRight = { player?.forceSeekToPrevious(seekToStart = false) }
                    )
                    .combinedClickable(onClick = onExpand, onLongClick = onMenu)
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(previousLabel) { player?.forceSeekToPrevious(); true },
                            CustomAccessibilityAction(nextLabel) { player?.forceSeekToNext(); true },
                            CustomAccessibilityAction(menuLabel) { onMenu(); true }
                        )
                    }
                    .padding(start = 8.dp, end = 4.dp)
                    .testTag("mini_player")
            ) {
                Artwork(
                    url = metadata?.artworkUri?.toString(),
                    size = 48.dp,
                    shape = RoundedCornerShape(12.dp)
                )

                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    AnimatedContent(
                        targetState = metadata?.title?.toString().orEmpty(),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = ""
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // An error of the track replaces the artist (REWRITE §3.10.9)
                    AnimatedContent(
                        targetState = error ?: metadata?.artist?.toString().orEmpty(),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = ""
                    ) { artist ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (explicit && error == null) Icon(
                                painter = painterResource(R.drawable.explicit),
                                contentDescription = stringResource(R.string.kit_explicit),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                FilledTonalIconToggleButton(
                    checked = shouldBePlaying,
                    onCheckedChange = { play ->
                        if (!play) player?.pause()
                        else {
                            if (player?.playbackState == Player.STATE_IDLE) player.prepare()
                            player?.play()
                        }
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.testTag("mini_player_play")
                ) {
                    Icon(
                        painter = painterResource(if (shouldBePlaying) R.drawable.ms_pause_fill else R.drawable.ms_play_arrow_fill),
                        contentDescription = stringResource(if (shouldBePlaying) R.string.pause else R.string.play)
                    )
                }

                IconButton(onClick = { player?.forceSeekToNext() }) {
                    Icon(
                        painter = painterResource(R.drawable.ms_skip_next_fill),
                        contentDescription = nextLabel
                    )
                }
            }
        }
    }
}
