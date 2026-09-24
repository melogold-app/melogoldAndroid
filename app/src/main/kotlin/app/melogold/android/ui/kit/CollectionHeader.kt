@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.kit

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.melogold.android.LocalPlayerAwareWindowInsets
import androidx.window.core.layout.WindowSizeClass
import app.melogold.android.R
import kotlin.math.roundToInt

/** The height of the buttons of [CollectionActions]: medium, small in a compact header. */
private val LocalActionHeight = staticCompositionLocalOf { ButtonDefaults.MediumContainerHeight }
private val PaneWidthRange = 320.dp..420.dp
private val CompactPaneHeight = 400.dp
private val CompactArtworkSize = 96.dp

/**
 * The frame of a detail screen (album, playlist, artist; REWRITE §3.11.6): a small app bar with
 * Back and [actions]. The [title] shows up in it, on a tonal bar, once the header's own title has
 * scrolled away ([showTitle]).
 */
@Composable
fun DetailScaffold(
    title: String,
    showTitle: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = { },
    content: @Composable (PaddingValues) -> Unit
) {
    val barColor by animateColorAsState(
        targetValue = if (showTitle) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        label = "barColor"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = showTitle,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 }
                    ) {
                        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ms_arrow_back),
                            contentDescription = stringResource(R.string.kit_back)
                        )
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor)
            )
        },
        contentWindowInsets = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag("detail")
    ) { padding ->
        content(padding)
    }
}

/**
 * Whether a detail screen puts its header in a pane at the start (REDESIGN §2.7): on an expanded
 * window, and on a phone on its side.
 */
@Composable
fun detailTwoPane(): Boolean {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ||
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) &&
        !sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
}

/**
 * The body of a detail screen. On a compact window the [header] is the first item of the list and
 * scrolls away with it; with [twoPane] it stays in a pane at the start and the list takes the rest.
 *
 * @param header gets whether the pane is too low for a big cover (a phone on its side)
 */
@Composable
fun DetailBody(
    twoPane: Boolean,
    padding: PaddingValues,
    listState: LazyListState,
    header: @Composable (compact: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) = BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val layoutDirection = LocalLayoutDirection.current
    val paneWidth = (maxWidth * 0.4f).coerceIn(PaneWidthRange.start, PaneWidthRange.endInclusive)
    val paneHeight = maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding()

    if (twoPane) Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = padding.calculateStartPadding(layoutDirection),
                end = padding.calculateEndPadding(layoutDirection)
            )
    ) {
        Column(
            modifier = Modifier
                .width(paneWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
        ) {
            header(paneHeight < CompactPaneHeight)
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding()
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .testTag("detail_list"),
            content = content
        )
    } else LazyColumn(
        state = listState,
        contentPadding = padding,
        modifier = Modifier
            .fillMaxSize()
            .testTag("detail_list")
    ) {
        item(key = "header", contentType = "header") { header(false) }
        content()
    }
}

/**
 * The header of a collection (REWRITE §3.11.6): the [artwork] in the middle, the [title], a
 * [subtitle] (with links, e.g. to the artist), an optional [status] line and the [actions].
 * A [compact] header (a low pane) puts a small cover beside the title.
 *
 * @param onTitleBottom where the title ends, in pixels from the top of the header: the app bar
 * shows the title once the list has scrolled past it
 */
@Composable
fun CollectionHeader(
    title: String,
    artwork: @Composable (size: Dp) -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: AnnotatedString? = null,
    status: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    onTitleBottom: (Int) -> Unit = { }
) = Column(modifier = modifier.fillMaxWidth()) {
    if (compact) Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        artwork(CompactArtworkSize)
        Column(modifier = Modifier.weight(1f)) {
            HeaderTitle(title = title, style = MaterialTheme.typography.headlineSmallEmphasized)
            if (subtitle != null) HeaderSubtitle(subtitle = subtitle, modifier = Modifier.padding(top = 4.dp))
        }
    } else {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            artwork(min(maxWidth - 96.dp, 280.dp))
        }

        HeaderTitle(
            title = title,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { onTitleBottom((it.positionInParent().y + it.size.height).roundToInt()) }
        )

        if (subtitle != null) HeaderSubtitle(
            subtitle = subtitle,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
        )
    }

    status?.let {
        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) { it() }
    }

    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) { actions() }
}

@Composable
private fun HeaderTitle(
    title: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) = Text(
    text = title,
    style = style,
    color = MaterialTheme.colorScheme.onSurface,
    maxLines = 3,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier.semantics { heading() }
)

@Composable
private fun HeaderSubtitle(
    subtitle: AnnotatedString,
    modifier: Modifier = Modifier
) = Text(
    text = subtitle,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier
)

/** The square cover of an album or a playlist in a [CollectionHeader]. */
@Composable
fun HeaderArtwork(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp)
) = Artwork(url = url, size = size, shape = shape, modifier = modifier)

/**
 * The actions of a [CollectionHeader] as a `ButtonGroup` (REWRITE §3.6): the wide primary
 * action, then tonal icon buttons, which fall into a menu when they don't fit. A pressed button
 * grows and pushes its neighbours. [compact] buttons are small (40 dp) instead of medium.
 */
@Composable
fun CollectionActions(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: ButtonGroupScope.() -> Unit
) = CompositionLocalProvider(
    LocalActionHeight provides if (compact) ButtonDefaults.MinHeight else ButtonDefaults.MediumContainerHeight
) {
    ButtonGroup(
        overflowIndicator = { menuState ->
            FilledTonalIconButton(
                onClick = { if (menuState.isShowing) menuState.dismiss() else menuState.show() },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(iconButtonSize())
            ) {
                Icon(painter = painterResource(R.drawable.ms_more_vert), contentDescription = stringResource(R.string.kit_menu))
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag("collection_actions"),
        content = content
    )
}

@Composable
private fun iconButtonSize() =
    if (LocalActionHeight.current < ButtonDefaults.MediumContainerHeight) IconButtonDefaults.smallContainerSize()
    else IconButtonDefaults.mediumContainerSize()

/** The wide filled button of [CollectionActions]: "Play". */
fun ButtonGroupScope.primaryAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) = customItem(
    buttonGroupContent = {
        val interactionSource = remember { MutableInteractionSource() }
        val height = LocalActionHeight.current

        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapesFor(height),
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 16.dp),
            interactionSource = interactionSource,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = height)
                .animateWidth(interactionSource)
                .testTag("action_primary")
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.iconSizeFor(height))
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.iconSpacingFor(height)))
            Text(
                text = label,
                style = ButtonDefaults.textStyleFor(height),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    },
    menuContent = { menuState ->
        DropdownMenuItem(
            text = { Text(text = label) },
            leadingIcon = { Icon(painter = painterResource(icon), contentDescription = null) },
            enabled = enabled,
            onClick = {
                menuState.dismiss()
                onClick()
            }
        )
    }
)

/** A tonal icon button of [CollectionActions]: "Shuffle", "⋮". */
fun ButtonGroupScope.iconAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null
) = customItem(
    buttonGroupContent = {
        val interactionSource = remember { MutableInteractionSource() }

        FilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(iconButtonSize())
                .animateWidth(interactionSource)
                .let { if (testTag != null) it.testTag(testTag) else it }
        ) {
            Icon(painter = painterResource(icon), contentDescription = label)
        }
    },
    menuContent = { menuState ->
        DropdownMenuItem(
            text = { Text(text = label) },
            leadingIcon = { Icon(painter = painterResource(icon), contentDescription = null) },
            enabled = enabled,
            onClick = {
                menuState.dismiss()
                onClick()
            }
        )
    }
)

/** A tonal toggle of [CollectionActions]: "Save to library" / "In library". */
fun ButtonGroupScope.toggleAction(
    checked: Boolean,
    @DrawableRes icon: Int,
    @DrawableRes checkedIcon: Int,
    label: String,
    checkedLabel: String,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    testTag: String? = null
) = customItem(
    buttonGroupContent = {
        val interactionSource = remember { MutableInteractionSource() }

        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            shapes = IconButtonDefaults.toggleableShapes(),
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(iconButtonSize())
                .animateWidth(interactionSource)
                .let { if (testTag != null) it.testTag(testTag) else it }
        ) {
            Icon(
                painter = painterResource(if (checked) checkedIcon else icon),
                contentDescription = if (checked) checkedLabel else label
            )
        }
    },
    menuContent = { menuState ->
        DropdownMenuItem(
            text = { Text(text = if (checked) checkedLabel else label) },
            leadingIcon = {
                Icon(painter = painterResource(if (checked) checkedIcon else icon), contentDescription = null)
            },
            enabled = enabled,
            onClick = {
                menuState.dismiss()
                onCheckedChange(!checked)
            }
        )
    }
)

/**
 * A text that may be long ("About the album"): a titled section showing three lines, and all of
 * it after a tap.
 */
@Composable
fun AboutSection(
    title: String,
    text: String,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize()
            .testTag("about"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            Icon(
                painter = painterResource(R.drawable.ms_keyboard_arrow_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = arrowRotation }
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
