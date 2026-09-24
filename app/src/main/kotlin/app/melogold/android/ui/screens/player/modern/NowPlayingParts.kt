@file:Suppress("TooManyFunctions")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.player.modern

import kotlin.math.abs
import app.melogold.android.ui.screens.player.minutesLeft
import app.melogold.android.ui.screens.player.formatSpeed
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Info
import app.melogold.android.ui.components.m3e.rememberHaptics
import app.melogold.android.ui.screens.artistRoute
import app.melogold.android.utils.thumbnail
import app.melogold.core.ui.utils.px
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * The top of the expanded player (REWRITE §3.10.2): the drag handle, "collapse", [indicators]
 * (the sleep timer, a speed other than 1×) and the player menu.
 */
@Composable
fun PlayerTopBar(
    onCollapse: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    indicators: @Composable RowScope.() -> Unit = { }
) = Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
        .fillMaxWidth()
        .testTag("player_handle")
) {
    val collapse = stringResource(R.string.collapse_player)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onCollapse
            )
            .clearAndSetSemantics { contentDescription = collapse }
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = CircleShape
                )
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                painter = painterResource(R.drawable.ms_keyboard_arrow_down),
                contentDescription = collapse
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = indicators
        )
        MoreButton(onClick = onMore)
    }
}

/**
 * The sleep timer ("⏾ 23 min") and a speed other than 1× next to ⋮ (REWRITE §3.10.7, §3.10.8); a
 * tap opens the player menu, where both are set.
 */
@Composable
fun RowScope.PlaybackIndicators(
    sleepTimerMillisLeft: Long?,
    speed: Float,
    onClick: () -> Unit
) {
    sleepTimerMillisLeft?.let { millis ->
        AssistChip(
            onClick = onClick,
            label = { Text(text = stringResource(R.string.menu_sleep_timer_minutes, millis.minutesLeft())) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ms_bedtime),
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                )
            },
            modifier = Modifier.testTag("player_sleep_timer")
        )
    }

    if (abs(speed - 1f) >= 0.01f) AssistChip(
        onClick = onClick,
        label = { Text(text = stringResource(R.string.menu_speed_value, formatSpeed(speed))) },
        modifier = Modifier.testTag("player_speed")
    )
}

/**
 * The big Now Playing artwork (28 dp corners). Shrinks a little while paused; the tap and the
 * horizontal swipe are handled by [onTap] and [modifier] (the stream info moved to the player menu).
 */
@Composable
fun PlayerArtwork(
    mediaItem: MediaItem,
    size: Dp,
    playing: Boolean,
    reduceMotion: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {}
) {
    val scale = animateFloatAsState(
        targetValue = if (playing) 1f else 0.9f,
        animationSpec = when {
            reduceMotion -> tween(150)
            playing -> spring(dampingRatio = 0.65f, stiffness = 300f)
            else -> spring(dampingRatio = 1f, stiffness = 300f)
        },
        label = ""
    )
    val elevation = animateFloatAsState(
        targetValue = if (playing) 16f else 4f,
        animationSpec = tween(300),
        label = ""
    )
    val shape = MaterialTheme.shapes.extraLarge
    val context = LocalContext.current
    val sizePx = size.px

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                shadowElevation = elevation.value.dp.toPx()
                this.shape = shape
                clip = true
            }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(onTap) {
                detectTapGestures(onTap = { onTap() })
            }
            .testTag("player_artwork")
    ) {
        Icon(
            painter = painterResource(R.drawable.ms_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.3f)
        )

        AsyncImage(
            model = remember(mediaItem.mediaMetadata.artworkUri, sizePx) {
                ImageRequest.Builder(context)
                    .data(mediaItem.mediaMetadata.artworkUri?.thumbnail(sizePx))
                    .crossfade(300)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        overlay()
    }
}

/** The thumbnail of the compact (Lyrics mode) header. */
@Composable
fun HeaderArtwork(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    val sizePx = size.px

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                shape = RoundedCornerShape(12.dp)
                clip = true
            }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        AsyncImage(
            model = mediaItem.mediaMetadata.artworkUri?.thumbnail(sizePx * 2),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * The artists of [mediaItem]; each one opens its page. Falls back to the metadata artist text.
 */
@Composable
fun ArtistLine(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge
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
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    AnimatedContent(
        targetState = artists,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
        label = "",
        modifier = modifier
    ) { currentArtists ->
        if (currentArtists == null) Text(
            text = fallback,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        ) else Row(verticalAlignment = Alignment.CenterVertically) {
            currentArtists.forEachIndexed { i, artist ->
                if (i > 0) Text(
                    text = if (i == currentArtists.lastIndex) " & " else ", ",
                    style = style,
                    color = color,
                    maxLines = 1
                )
                Text(
                    text = artist.name.orEmpty(),
                    style = style,
                    color = color,
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

/**
 * ♡ as a tonal icon toggle (REWRITE §3.10.2): the shape morphs when checked, the heart bounces.
 */
@Composable
fun FavoriteButton(
    liked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberHaptics()
    val coroutineScope = rememberCoroutineScope()
    val bounce = remember { Animatable(1f) }

    FilledTonalIconToggleButton(
        checked = liked,
        onCheckedChange = {
            haptics.toggle(it)
            onToggle(it)
            coroutineScope.launch {
                bounce.animateTo(1.25f, spring(stiffness = 600f))
                bounce.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f))
            }
        },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier.testTag("player_favorite")
    ) {
        Icon(
            painter = painterResource(if (liked) R.drawable.ms_favorite_fill else R.drawable.ms_favorite),
            contentDescription = stringResource(if (liked) R.string.unlike else R.string.like),
            modifier = Modifier.graphicsLayer {
                scaleX = bounce.value
                scaleY = bounce.value
            }
        )
    }
}

@Composable
fun MoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = IconButton(onClick = onClick, modifier = modifier.testTag("player_more")) {
    Icon(
        painter = painterResource(R.drawable.ms_more_vert),
        contentDescription = stringResource(R.string.more_options)
    )
}

/**
 * Title and artists of the playing track with ♡ (REWRITE §3.10.2).
 */
@Composable
fun TitleBlock(
    mediaItem: MediaItem,
    liked: Boolean,
    onToggleLike: (Boolean) -> Unit,
    sharedScopes: SharedScopes?,
    modifier: Modifier = Modifier
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp)
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
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .basicMarquee(iterations = 3, initialDelayMillis = 2000)
                    .testTag("player_title")
            )
        }
        ArtistLine(mediaItem = mediaItem)
    }

    FavoriteButton(
        liked = liked,
        onToggle = onToggleLike,
        modifier = Modifier.modeSharedElement(sharedScopes, "actions")
    )
}

/**
 * The header of the Lyrics mode: thumbnail, title, artists, ♡ and the lyrics menu. Tapping the
 * thumbnail or the title calls [onClick].
 */
@Composable
fun CompactHeader(
    mediaItem: MediaItem,
    liked: Boolean,
    onToggleLike: (Boolean) -> Unit,
    onClick: () -> Unit,
    sharedScopes: SharedScopes?,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
        .fillMaxWidth()
        .padding(start = 24.dp, end = 8.dp)
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ArtistLine(mediaItem = mediaItem, style = MaterialTheme.typography.bodyMedium)
    }

    FavoriteButton(
        liked = liked,
        onToggle = onToggleLike,
        modifier = Modifier.modeSharedElement(sharedScopes, "actions")
    )
}
