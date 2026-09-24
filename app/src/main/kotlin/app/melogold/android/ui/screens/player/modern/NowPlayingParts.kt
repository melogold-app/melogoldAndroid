@file:Suppress("TooManyFunctions")

package app.melogold.android.ui.screens.player.modern

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Info
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.utils.thumbnail
import app.melogold.core.ui.LocalAppearance
import app.melogold.core.ui.utils.px
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Colours of the content drawn on top of the artwork-tinted background: always light. */
internal object OnArt {
    val primary = Color.White
    val secondary = Color.White.copy(alpha = 0.6f)
    val tertiary = Color.White.copy(alpha = 0.55f)
    val glass = Color.White.copy(alpha = 0.14f)
    val selected = Color.White.copy(alpha = 0.85f)
    val onSelected = Color.Black.copy(alpha = 0.8f)
}

/** The scopes needed for shared-element transitions between the NowPlaying and Lyrics stages. */
@Immutable
class SharedScopes(
    val transition: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val reduceMotion: Boolean
)

private val modeBoundsTransform = BoundsTransform { _: Rect, _: Rect ->
    spring(dampingRatio = 0.85f, stiffness = 300f)
}
private val reducedBoundsTransform = BoundsTransform { _: Rect, _: Rect -> tween(150) }

@Composable
internal fun Modifier.modeSharedElement(scopes: SharedScopes?, key: String): Modifier {
    if (scopes == null) return this
    return with(scopes.transition) {
        this@modeSharedElement.sharedElement(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = scopes.visibility,
            boundsTransform = if (scopes.reduceMotion) reducedBoundsTransform else modeBoundsTransform
        )
    }
}

@Composable
internal fun Modifier.modeSharedBounds(scopes: SharedScopes?, key: String): Modifier {
    if (scopes == null) return this
    return with(scopes.transition) {
        this@modeSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = scopes.visibility,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(120)),
            boundsTransform = if (scopes.reduceMotion) reducedBoundsTransform else modeBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.CenterStart
            )
        )
    }
}

@Composable
fun Handle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.collapse_player)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .testTag("player_handle")
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .clearAndSetSemantics { contentDescription = description }
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 5.dp)
                .background(color = Color.White.copy(alpha = 0.4f), shape = CircleShape)
        )
    }
}

/**
 * The big Now Playing artwork. Scales down while paused; tap, long press, horizontal swipe and
 * pinch are handled by [modifier] / the callbacks.
 */
@Composable
fun PlayerArtwork(
    mediaItem: MediaItem,
    size: Dp,
    playing: Boolean,
    reduceMotion: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {}
) {
    val scale = animateFloatAsState(
        targetValue = if (playing) 1f else 0.8f,
        animationSpec = when {
            reduceMotion -> tween(150)
            playing -> spring(dampingRatio = 0.65f, stiffness = 300f)
            else -> spring(dampingRatio = 1f, stiffness = 300f)
        },
        label = ""
    )
    val elevation = animateFloatAsState(
        targetValue = if (playing) 24f else 6f,
        animationSpec = tween(300),
        label = ""
    )
    val shape = remember { RoundedCornerShape(10.dp) }
    val context = LocalContext.current
    val sizePx = size.px

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                shadowElevation = elevation.value.dp.toPx()
                this.shape = shape
                clip = true
            }
            .background(Color.White.copy(alpha = 0.08f))
            .pointerInput(onTap, onLongPress) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
            .testTag("player_artwork")
    ) {
        AsyncImage(
            model = remember(mediaItem.mediaMetadata.artworkUri, sizePx) {
                ImageRequest.Builder(context)
                    .data(mediaItem.mediaMetadata.artworkUri?.thumbnail(sizePx))
                    .crossfade(300)
                    .build()
            },
            placeholder = painterResource(R.drawable.ic_launcher_foreground),
            error = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        overlay()
    }
}

/** The 72 dp thumbnail of the compact (Lyrics mode) header. */
@Composable
fun HeaderArtwork(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    val sizePx = size.px

    AsyncImage(
        model = mediaItem.mediaMetadata.artworkUri?.thumbnail(sizePx * 2),
        placeholder = painterResource(R.drawable.ic_launcher_foreground),
        error = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                shadowElevation = 4.dp.toPx()
                shape = RoundedCornerShape(6.dp)
                clip = true
            }
            .background(Color.White.copy(alpha = 0.08f))
    )
}

/**
 * The artists of [mediaItem]; each one opens its page. Falls back to the metadata artist text.
 */
@Composable
fun ArtistLine(
    mediaItem: MediaItem,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    var artists by remember(mediaItem.mediaId) { mutableStateOf<List<Info>?>(null) }

    LaunchedEffect(mediaItem.mediaId) {
        artists = withContext(Dispatchers.IO) {
            runCatching { Database.songArtistInfo(mediaItem.mediaId) }
                .getOrNull()
                ?.takeIf { list -> list.isNotEmpty() && list.all { !it.name.isNullOrBlank() } }
        }
    }

    val fallback = mediaItem.mediaMetadata.artist?.toString().orEmpty()

    AnimatedContent(
        targetState = artists,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
        label = "",
        modifier = modifier
    ) { currentArtists ->
        if (currentArtists == null) BasicText(
            text = fallback,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        ) else Row(verticalAlignment = Alignment.CenterVertically) {
            currentArtists.forEachIndexed { i, artist ->
                if (i > 0) BasicText(
                    text = if (i == currentArtists.lastIndex) " & " else ", ",
                    style = style,
                    maxLines = 1
                )
                BasicText(
                    text = artist.name.orEmpty(),
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { artistRoute.global(artist.id) }
                )
            }
        }
    }
}

@Composable
private fun GlassCircleButton(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier
) = Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.size(48.dp)
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .background(color = OnArt.glass, shape = CircleShape)
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(OnArt.primary),
            modifier = iconModifier.size(17.dp)
        )
    }
}

@Composable
fun FavoriteButton(
    liked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val bounce = remember { Animatable(1f) }

    val on = stringResource(R.string.state_on)
    val off = stringResource(R.string.state_off)
    val description = stringResource(if (liked) R.string.unlike else R.string.like)

    GlassCircleButton(
        icon = if (liked) R.drawable.heart else R.drawable.heart_outline,
        iconModifier = Modifier.graphicsLayer {
            scaleX = bounce.value
            scaleY = bounce.value
        },
        modifier = modifier
            .testTag("player_favorite")
            .clip(CircleShape)
            .toggleable(
                value = liked,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 20.dp),
                onValueChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onToggle(it)
                    coroutineScope.launch {
                        bounce.animateTo(1.3f, spring(stiffness = 600f))
                        bounce.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f)
                        )
                    }
                }
            )
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = if (liked) on else off
            }
    )
}

@Composable
fun MoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.more_options)

    GlassCircleButton(
        icon = R.drawable.ellipsis_horizontal,
        modifier = modifier
            .testTag("player_more")
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 20.dp),
                role = Role.Button,
                onClick = onClick
            )
            .clearAndSetSemantics { contentDescription = description }
    )
}

/** Favourite + "…", shared between the big title block and the compact header. */
@Composable
fun TitleActions(
    liked: Boolean,
    onToggleLike: (Boolean) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
) {
    FavoriteButton(liked = liked, onToggle = onToggleLike)
    MoreButton(onClick = onMore)
}

@Composable
private fun titleStyle(size: Int, weight: FontWeight, color: Color): TextStyle {
    val typography = LocalAppearance.current.typography
    return typography.l.copy(
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        lineHeight = (size + 5).sp
    )
}

/** Title and artists of the big Now Playing layout, with the favourite and "…" buttons. */
@Composable
fun TitleBlock(
    mediaItem: MediaItem,
    liked: Boolean,
    onToggleLike: (Boolean) -> Unit,
    onMore: () -> Unit,
    sharedScopes: SharedScopes?,
    modifier: Modifier = Modifier
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
        .fillMaxWidth()
        .padding(start = 32.dp, end = 16.dp)
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .modeSharedBounds(sharedScopes, "title")
    ) {
        AnimatedContent(
            targetState = mediaItem.mediaMetadata.title?.toString().orEmpty(),
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
            label = ""
        ) { title ->
            BasicText(
                text = title,
                style = titleStyle(20, FontWeight.SemiBold, OnArt.primary),
                maxLines = 1,
                modifier = Modifier
                    .basicMarquee(iterations = 3, initialDelayMillis = 2000)
                    .testTag("player_title")
            )
        }
        ArtistLine(
            mediaItem = mediaItem,
            style = titleStyle(20, FontWeight.Normal, OnArt.secondary)
        )
    }

    TitleActions(
        liked = liked,
        onToggleLike = onToggleLike,
        onMore = onMore,
        modifier = Modifier.modeSharedElement(sharedScopes, "actions")
    )
}

/**
 * The header of the Lyrics mode: 72 dp thumbnail, title, artists, favourite and "…".
 * Tapping the thumbnail or the title calls [onClick].
 */
@Composable
fun CompactHeader(
    mediaItem: MediaItem,
    liked: Boolean,
    onToggleLike: (Boolean) -> Unit,
    onMore: () -> Unit,
    onClick: () -> Unit,
    sharedScopes: SharedScopes?,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
        .fillMaxWidth()
        .padding(start = 32.dp, end = 16.dp)
        .testTag("player_header")
) {
    Box(
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick
        )
    ) {
        artwork()
    }

    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(start = 12.dp)
            .weight(1f)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
            .modeSharedBounds(sharedScopes, "title")
    ) {
        AnimatedContent(
            targetState = mediaItem.mediaMetadata.title?.toString().orEmpty(),
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
            label = ""
        ) { title ->
            BasicText(
                text = title,
                style = titleStyle(17, FontWeight.SemiBold, OnArt.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ArtistLine(
            mediaItem = mediaItem,
            style = titleStyle(15, FontWeight.Normal, OnArt.secondary)
        )
    }

    TitleActions(
        liked = liked,
        onToggleLike = onToggleLike,
        onMore = onMore,
        modifier = Modifier.modeSharedElement(sharedScopes, "actions")
    )
}
