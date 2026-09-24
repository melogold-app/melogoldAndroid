package app.melogold.android.ui.screens.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.melogold.android.Database
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.query
import app.melogold.android.service.PlayerService
import app.melogold.android.ui.components.BottomSheet
import app.melogold.android.ui.components.BottomSheetState
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.themed.BaseMediaItemMenu
import app.melogold.android.ui.components.themed.IconButton
import app.melogold.android.ui.components.themed.SecondaryTextButton
import app.melogold.android.ui.components.themed.SliderDialog
import app.melogold.android.ui.components.themed.SliderDialogBody
import app.melogold.android.ui.screens.player.modern.ModernPlayer
import app.melogold.android.ui.shell.AppSnackbar
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.positionAndDurationState
import app.melogold.android.utils.rememberEqualizerLauncher
import app.melogold.android.utils.seamlessPlay
import app.melogold.android.utils.secondary
import app.melogold.android.utils.semiBold
import app.melogold.android.utils.shouldBePlaying
import app.melogold.android.utils.thumbnail
import app.melogold.compose.persist.PersistMapCleanup
import app.melogold.compose.routing.OnGlobalRoute
import app.melogold.core.ui.Dimensions
import app.melogold.core.ui.LocalAppearance
import app.melogold.core.ui.collapsedPlayerProgressBar
import app.melogold.core.ui.utils.px
import app.melogold.core.ui.utils.roundedShape
import app.melogold.core.ui.utils.songBundle
import app.melogold.providers.innertube.models.NavigationEndpoint
import coil3.compose.AsyncImage
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * @param collapsedBottomExtra the space under the mini player that other content covers: the
 * navigation bar of the app shell (REDESIGN-M3E §5.4), 0 dp with the navigation rail
 */
@Composable
fun Player(
    layoutState: BottomSheetState,
    modifier: Modifier = Modifier,
    collapsedBottomExtra: Dp = 0.dp,
    shape: RoundedCornerShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp
    ),
    windowInsets: WindowInsets = WindowInsets.systemBars
) = with(PlayerPreferences) {
    val menuState = LocalMenuState.current
    val [colorPalette, typography, thumbnailCornerSize] = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current

    PersistMapCleanup(prefix = "queue/suggestions")

    val snackbar = LocalAppSnackbar.current
    val stoppedMessage = stringResource(R.string.player_stopped)

    var mediaItem by remember(binder) {
        mutableStateOf(
            value = binder?.player?.currentMediaItem,
            policy = neverEqualPolicy()
        )
    }
    var shouldBePlaying by remember(binder) { mutableStateOf(binder?.player?.shouldBePlaying == true) }

    var likedAt by remember(mediaItem) {
        mutableStateOf(
            value = null,
            policy = object : SnapshotMutationPolicy<Long?> {
                override fun equivalent(a: Long?, b: Long?): Boolean {
                    mediaItem?.mediaId?.let {
                        query {
                            Database.like(it, b)
                        }
                    }
                    return a == b
                }
            }
        )
    }

    LaunchedEffect(mediaItem) {
        mediaItem?.mediaId?.let { mediaId ->
            Database
                .likedAt(mediaId)
                .distinctUntilChanged()
                .collect { likedAt = it }
        }
    }

    binder?.player.DisposableListener {
        object : Player.Listener {
            override fun onMediaItemTransition(newMediaItem: MediaItem?, reason: Int) {
                mediaItem = newMediaItem
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldBePlaying = player.shouldBePlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                shouldBePlaying = player.shouldBePlaying
            }
        }
    }

    val metadata = remember(mediaItem) { mediaItem?.mediaMetadata }
    val extras = remember(metadata) { metadata?.extras?.songBundle }

    val horizontalBottomPaddingValues = windowInsets
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        .asPaddingValues()

    OnGlobalRoute { if (layoutState.expanded) layoutState.collapseSoft() }

    if (mediaItem != null) BottomSheet(
        state = layoutState,
        modifier = modifier.fillMaxSize(),
        onDismiss = {
            binder?.let { stopWithUndo(it, snackbar, stoppedMessage) }
            layoutState.dismissSoft()
        },
        backHandlerEnabled = !menuState.isDisplayed,
        collapsedContent = { _ ->
            MiniPlayer(
                binder = binder,
                metadata = metadata,
                explicit = extras?.explicit == true,
                shouldBePlaying = shouldBePlaying,
                onExpand = layoutState::expandSoft,
                onMenu = {
                    val item = mediaItem
                    if (binder != null && item != null) menuState.display {
                        PlayerMenu(onDismiss = menuState::hide, mediaItem = item, binder = binder)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontalBottomPaddingValues)
                    .padding(bottom = collapsedBottomExtra)
            )
        }
    ) {
        var audioDialogOpen by rememberSaveable { mutableStateOf(false) }

        val openPlayerMenu: () -> Unit = {
            mediaItem?.let {
                if (binder != null) menuState.display {
                    PlayerMenu(
                        onDismiss = menuState::hide,
                        mediaItem = it,
                        binder = binder,
                        onShowSpeedDialog = { audioDialogOpen = true }
                    )
                }
            }
        }

        mediaItem?.let { currentMediaItem ->
            if (binder != null) ModernPlayer(
                layoutState = layoutState,
                binder = binder,
                mediaItem = currentMediaItem,
                likedAt = likedAt,
                setLikedAt = { likedAt = it },
                shouldBePlaying = shouldBePlaying,
                openPlayerMenu = openPlayerMenu
            )
        }

        if (audioDialogOpen) SliderDialog(
            onDismiss = { audioDialogOpen = false },
            title = stringResource(R.string.playback_speed)
        ) {
            SliderDialogBody(
                provideState = { remember(speed) { mutableFloatStateOf(speed) } },
                onSlideComplete = { speed = it },
                min = 0f,
                max = 2f,
                toDisplay = {
                    if (it <= 0.01f) stringResource(R.string.minimum_speed_value)
                    else stringResource(R.string.format_multiplier, "%.2f".format(it))
                },
                steps = 39
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                SecondaryTextButton(
                    text = stringResource(R.string.reset),
                    onClick = { speed = 1f }
                )
            }
        }
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun PlayerMenu(
    binder: PlayerService.Binder,
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onShowSpeedDialog: (() -> Unit)? = null
) {
    val launchEqualizer by rememberEqualizerLauncher(audioSessionId = { binder.player.audioSessionId })

    BaseMediaItemMenu(
        mediaItem = mediaItem,
        onStartRadio = {
            binder.stopRadio()
            binder.player.seamlessPlay(mediaItem)
            binder.setupRadio(NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId))
        },
        onGoToEqualizer = launchEqualizer,
        onShowSleepTimer = {},
        onDismiss = onDismiss,
        onShowSpeedDialog = onShowSpeedDialog
    )
}

/**
 * Swiping the mini player down stops playback and clears the queue; "Undo" brings the queue, the
 * track and the position back (REWRITE §3.10.1, VT#177).
 */
private fun stopWithUndo(binder: PlayerService.Binder, snackbar: AppSnackbar, message: String) {
    val player = binder.player
    val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
    val index = player.currentMediaItemIndex
    val position = player.currentPosition

    binder.stopRadio()
    player.clearMediaItems()

    if (items.isNotEmpty()) snackbar.showUndo(message) {
        player.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
        player.prepare()
    }
}
