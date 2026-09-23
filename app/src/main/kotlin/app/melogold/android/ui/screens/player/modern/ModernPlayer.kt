package app.melogold.android.ui.screens.player.modern

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Lyrics
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.query
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.android.service.PlayerService
import app.melogold.android.transaction
import app.melogold.android.ui.components.BottomSheetState
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.rememberBottomSheetState
import app.melogold.android.ui.components.themed.MenuEntry
import app.melogold.android.ui.components.themed.TextFieldDialog
import app.melogold.android.ui.modifiers.PinchDirection
import app.melogold.android.ui.modifiers.onSwipe
import app.melogold.android.ui.modifiers.pinchToToggle
import app.melogold.android.ui.screens.player.LrcLibSearchDialog
import app.melogold.android.ui.screens.player.LyricsMenu
import app.melogold.android.ui.screens.player.PlaybackError
import app.melogold.android.ui.screens.player.Queue
import app.melogold.android.ui.screens.player.StatsForNerds
import app.melogold.android.ui.screens.player.playbackErrorMessage
import app.melogold.android.ui.screens.player.searchLyricsOnline
import app.melogold.android.utils.FullScreenState
import app.melogold.android.utils.Pip
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.rememberPipHandler
import app.melogold.android.utils.rememberReduceMotion
import app.melogold.android.utils.rememberTouchExplorationEnabled
import app.melogold.android.utils.toast
import app.melogold.android.utils.windowState
import app.melogold.compose.persist.findActivityNullable
import app.melogold.compose.routing.CallbackPredictiveBackHandler
import app.melogold.core.ui.LocalAppearance
import app.melogold.core.ui.defaultDarkPalette
import app.melogold.core.ui.setSystemBarAppearance
import app.melogold.core.ui.utils.isLandscape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_HIDE_DELAY_MS = 4_000L

/** Where the top of the active synced line sits, below the top of the lyrics viewport. */
private val LyricsAnchor = 88.dp

/** Used for the first frame, before the controls have been measured. */
private val DefaultControlsHeight = 330.dp

@Immutable
private data class LyricsTarget(val mediaId: String, val content: LyricsContent)

private fun LyricsContent.kind() = when (this) {
    is LyricsContent.Synced -> 0
    is LyricsContent.Plain -> 1
    LyricsContent.Loading, LyricsContent.Unknown -> 2
    LyricsContent.NotFound -> 3
    LyricsContent.Failed -> 4
}

/**
 * The Apple-Music-style expanded player: artwork-tinted background, big artwork (NowPlaying) or
 * lyrics (Lyrics, full screen when time-synced), and the controls at the bottom. The queue is the
 * existing queue sheet, opened from the toolbar.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ModernPlayer(
    layoutState: BottomSheetState,
    binder: PlayerService.Binder,
    mediaItem: MediaItem,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    shouldBePlaying: Boolean,
    openPlayerMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appearance = LocalAppearance.current
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val pipHandler = rememberPipHandler()
    val reduceMotion = rememberReduceMotion()
    val touchExploration = rememberTouchExplorationEnabled()
    val landscape = isLandscape
    val compactControls = LocalConfiguration.current.screenHeightDp < 720
    val mediaId = mediaItem.mediaId

    // The existing queue sheet, without its collapsed bar (both bounds are 0 dp)
    val queueSheet = rememberBottomSheetState(
        dismissedBound = 0.dp,
        collapsedBound = 0.dp,
        expandedBound = layoutState.expandedBound
    )
    val queueOpen = !queueSheet.collapsed

    val modeState = remember {
        PlayerModeState(
            initialBase = if (PlayerPreferences.isShowingLyrics) PlayerMode.Lyrics else PlayerMode.NowPlaying
        )
    }
    val lyrics = rememberPlayerLyrics(
        mediaItem = mediaItem,
        fetchEnabled = modeState.baseMode == PlayerMode.Lyrics,
        preferSynced = PlayerPreferences.preferSyncedLyrics
    )
    val content = lyrics.content
    val effectiveBase = modeState.effectiveBase(content, mediaId)
    val mode = if (queueOpen) PlayerMode.Queue else effectiveBase

    LaunchedEffect(mediaId) {
        if (modeState.lyricsRequestedFor != mediaId) modeState.lyricsRequestedFor = null
    }

    LaunchedEffect(modeState.baseMode) {
        PlayerPreferences.isShowingLyrics = modeState.baseMode == PlayerMode.Lyrics
    }

    LaunchedEffect(effectiveBase) {
        modeState.transitionState.animateTo(effectiveBase)
    }

    // Controls auto-hide (synced lyrics only, portrait only)
    val canAutoHide = !landscape &&
        mode == PlayerMode.Lyrics &&
        content is LyricsContent.Synced &&
        shouldBePlaying &&
        !modeState.userScrolling &&
        !modeState.scrubbing &&
        !menuState.isDisplayed &&
        !touchExploration

    LaunchedEffect(canAutoHide, modeState.lastPressAt, mediaId, shouldBePlaying) {
        modeState.controlsVisible = true
        if (canAutoHide) {
            delay(AUTO_HIDE_DELAY_MS)
            modeState.controlsVisible = false
        }
    }

    // Back: Lyrics -> NowPlaying (predictive); NowPlaying -> the sheet's own handler collapses it
    val backProgress = remember { mutableFloatStateOf(0f) }
    CallbackPredictiveBackHandler(
        enabled = effectiveBase == PlayerMode.Lyrics &&
            !queueOpen &&
            !menuState.isDisplayed &&
            layoutState.expanded,
        onStart = { },
        onProgress = { progress ->
            backProgress.floatValue = progress
            coroutineScope.launch {
                runCatching {
                    modeState.transitionState.seekTo(
                        fraction = 0.25f * progress,
                        targetState = PlayerMode.NowPlaying
                    )
                }
            }
        },
        onFinish = {
            backProgress.floatValue = 0f
            modeState.closeLyrics()
        },
        onCancel = {
            backProgress.floatValue = 0f
            coroutineScope.launch {
                runCatching { modeState.transitionState.animateTo(PlayerMode.Lyrics) }
            }
        }
    )

    // Light system bar icons on this always-dark screen, restored when collapsing
    val activity = remember(context) { context.findActivityNullable() }
    val appPalette = appearance.colorPalette
    val currentAppIsDark by rememberUpdatedState(appPalette.isDark)
    LaunchedEffect(layoutState.expanded, appPalette) {
        if (layoutState.expanded) {
            withFrameNanos { }
            activity?.setSystemBarAppearance(isDark = true)
        } else activity?.setSystemBarAppearance(isDark = appPalette.isDark)
    }
    DisposableEffect(activity) {
        onDispose { activity?.setSystemBarAppearance(isDark = currentAppIsDark) }
    }

    FullScreenState(shown = modeState.controlsVisible || PlayerPreferences.lyricsShowSystemBars)

    val keepScreenOn = PlayerPreferences.lyricsKeepScreenAwake &&
        mode == PlayerMode.Lyrics &&
        shouldBePlaying
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Dialogs and menus (hosted outside of the palette override below, so they keep the app theme)
    var editingSynced by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var showingStats by rememberSaveable(mediaId) { mutableStateOf(false) }

    val copiedMessage = stringResource(R.string.copied)

    fun showLyricsMenu(header: @Composable ColumnScope.() -> Unit = { }) = menuState.display {
        val errorMessage = stringResource(R.string.no_browser_installed)
        val showingSynced = PlayerPreferences.preferSyncedLyrics
        val raw = lyrics.raw

        LyricsMenu(
            showingSynced = showingSynced,
            onToggleSynced = { PlayerPreferences.preferSyncedLyrics = !showingSynced },
            onEdit = {
                editingSynced = when (lyrics.content) {
                    is LyricsContent.Synced -> true
                    is LyricsContent.Plain -> false
                    else -> showingSynced
                }
            },
            onSearchOnline = {
                context.searchLyricsOnline(
                    mediaMetadata = mediaItem.mediaMetadata,
                    errorMessage = errorMessage
                )
            },
            onRefetch = raw?.let { current ->
                {
                    transaction {
                        runCatching {
                            Database.insert(mediaItem)
                            Database.upsert(
                                if (showingSynced) current.copy(synced = null)
                                else current.copy(fixed = null)
                            )
                        }
                    }
                    // Also covers a side that is already null (nothing changes in Room then)
                    lyrics.retry()
                }
            },
            onPickFromLrcLib = if (showingSynced) {
                { picking = true }
            } else null,
            onSetStartOffset = if (showingSynced && raw != null) {
                {
                    val startTime = binder.player.currentPosition
                    query { Database.upsert(raw.copy(startTime = startTime)) }
                }
            } else null,
            header = header,
            footer = {
                MenuEntry(
                    icon = R.drawable.ellipsis_horizontal,
                    text = stringResource(R.string.more_options),
                    onClick = openPlayerMenu
                )
            }
        )
    }

    val onLineLongPress: (LyricLine) -> Unit = { line ->
        showLyricsMenu(
            header = {
                MenuEntry(
                    icon = R.drawable.text,
                    text = stringResource(R.string.lyrics_copy_line),
                    secondaryText = line.text,
                    onClick = {
                        menuState.hide()
                        context.getSystemService<ClipboardManager>()
                            ?.setPrimaryClip(ClipData.newPlainText("lyrics", line.text))
                        context.toast(copiedMessage)
                    }
                )
            }
        )
    }

    val onLyricsClick: () -> Unit = {
        when {
            queueOpen -> {
                queueSheet.collapseSoft()
                modeState.openLyrics(mediaId)
            }

            effectiveBase == PlayerMode.Lyrics -> modeState.closeLyrics()

            else -> modeState.openLyrics(mediaId)
        }
    }
    val onQueueClick: () -> Unit = {
        if (queueOpen) queueSheet.collapseSoft() else queueSheet.expandSoft()
    }
    val onHeaderClick: () -> Unit = {
        // A tap that only woke the controls up does not leave the lyrics
        if (!modeState.hiddenAtLastPress) modeState.closeLyrics()
    }
    val onToggleLike: (Boolean) -> Unit = { liked ->
        setLikedAt(if (liked) System.currentTimeMillis() else null)
    }

    val error = windowState(binder).second

    val controlsHeightPx = remember { mutableIntStateOf(with(density) { DefaultControlsHeight.roundToPx() }) }
    val controlsHeight = with(density) { controlsHeightPx.intValue.toDp() }

    // Building blocks shared by the portrait and landscape layouts

    val artwork: @Composable (Dp, SharedScopes?) -> Unit = { size, scopes ->
        Pip(
            numerator = 1,
            denominator = 1,
            modifier = Modifier.modeSharedElement(scopes, "art")
        ) {
            PlayerArtwork(
                mediaItem = mediaItem,
                size = size,
                playing = shouldBePlaying,
                reduceMotion = reduceMotion,
                onTap = { modeState.openLyrics(mediaId) },
                onLongPress = { showingStats = true },
                modifier = Modifier
                    .onSwipe(
                        animateOffset = true,
                        onSwipeLeft = { binder.player.forceSeekToNext() },
                        onSwipeRight = { binder.player.forceSeekToPrevious(seekToStart = false) }
                    )
                    .pinchToToggle(
                        direction = PinchDirection.In,
                        threshold = .95f,
                        onPinch = { pipHandler.enterPictureInPictureMode() }
                    )
            ) {
                StatsForNerds(
                    mediaId = mediaId,
                    isDisplayed = showingStats && error == null,
                    onDismiss = { showingStats = false },
                    modifier = Modifier.fillMaxSize()
                )

                PlaybackError(
                    isDisplayed = error != null,
                    messageProvider = { playbackErrorMessage(mediaItem, error) },
                    onDismiss = { binder.player.prepare() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    val titleBlock: @Composable (Modifier, SharedScopes?) -> Unit = { blockModifier, scopes ->
        TitleBlock(
            mediaItem = mediaItem,
            liked = likedAt != null,
            onToggleLike = onToggleLike,
            onMore = openPlayerMenu,
            sharedScopes = scopes,
            modifier = blockModifier
        )
    }

    val compactHeader: @Composable (Modifier, SharedScopes?) -> Unit = { headerModifier, scopes ->
        CompactHeader(
            mediaItem = mediaItem,
            liked = likedAt != null,
            onToggleLike = onToggleLike,
            onMore = { showLyricsMenu() },
            onClick = onHeaderClick,
            sharedScopes = scopes,
            modifier = headerModifier
        ) {
            Pip(
                numerator = 1,
                denominator = 1,
                modifier = Modifier.modeSharedElement(scopes, "art")
            ) {
                HeaderArtwork(mediaItem = mediaItem)
            }
        }
    }

    // overlaid: whether the controls are drawn over the bottom of the lyrics (portrait)
    val lyricsArea: @Composable (Modifier, Dp, Boolean) -> Unit = { areaModifier, anchor, overlaid ->
        AnimatedContent(
            targetState = LyricsTarget(mediaId, content),
            contentKey = { it.mediaId to it.content.kind() },
            transitionSpec = {
                fadeIn(tween(durationMillis = 250, delayMillis = 150)) togetherWith
                    fadeOut(tween(durationMillis = 150))
            },
            label = "",
            modifier = areaModifier
        ) { target ->
            val overlapPx: () -> Int = { if (overlaid) controlsHeightPx.intValue else 0 }

            when (val targetContent = target.content) {
                is LyricsContent.Synced -> SyncedLyricsView(
                    content = targetContent,
                    mediaId = target.mediaId,
                    player = binder.player,
                    shouldBePlaying = shouldBePlaying,
                    anchor = anchor,
                    controlsVisible = modeState.controlsVisible,
                    controlsOverlapPx = overlapPx,
                    reduceMotion = reduceMotion,
                    modeState = modeState,
                    onLineLongPress = onLineLongPress
                )

                is LyricsContent.Plain -> StaticLyricsView(
                    text = targetContent.text,
                    mediaId = target.mediaId,
                    controlsOverlapPx = overlapPx,
                    bottomPadding = if (overlaid) controlsHeight else 0.dp
                )

                LyricsContent.Loading, LyricsContent.Unknown -> LyricsLoading(
                    anchor = anchor,
                    reduceMotion = reduceMotion
                )

                LyricsContent.NotFound -> LyricsEmptyState(
                    onSearchLrcLib = { picking = true },
                    onEnterManually = { editingSynced = false }
                )

                LyricsContent.Failed -> LyricsErrorState(onRetry = lyrics::retry)
            }
        }
    }

    val controls: @Composable (Modifier, Boolean) -> Unit = { controlsModifier, compact ->
        PlayerControlsBlock(
            binder = binder,
            shouldBePlaying = shouldBePlaying,
            onScrubbing = { modeState.scrubbing = it },
            compact = compact,
            modifier = controlsModifier,
            toolbar = {
                PlayerToolbar(
                    lyricsSelected = mode == PlayerMode.Lyrics,
                    lyricsAvailable = content !is LyricsContent.NotFound,
                    queueSelected = queueOpen,
                    onLyricsClick = onLyricsClick,
                    onQueueClick = onQueueClick
                )
            }
        )
    }

    val onArtAppearance = remember(appearance) {
        appearance.copy(
            colorPalette = defaultDarkPalette.copy(
                accent = appearance.colorPalette.accent,
                onAccent = appearance.colorPalette.onAccent,
                text = Color.White,
                textSecondary = Color.White.copy(alpha = 0.6f),
                textDisabled = Color.White.copy(alpha = 0.35f)
            ),
            typography = appearance.typography.copy(color = Color.White)
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalAppearance provides onArtAppearance) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .semantics { testTagsAsResourceId = true }
                    .testTag("player_root")
                    .pointerInput(modeState) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) modeState.onPress()
                            }
                        }
                    }
                    .onPreviewKeyEvent {
                        modeState.reveal()
                        false
                    }
            ) {
                PlayerBackground(
                    artworkUri = mediaItem.mediaMetadata.artworkUri,
                    accent = appearance.colorPalette.accent,
                    animate = shouldBePlaying && !queueOpen && !reduceMotion
                )

                val transition = rememberTransition(modeState.transitionState, label = "mode")

                if (landscape) LandscapeLayout(
                    transition = transition,
                    reduceMotion = reduceMotion,
                    artwork = artwork,
                    titleBlock = titleBlock,
                    compactHeader = compactHeader,
                    lyricsArea = lyricsArea,
                    controls = controls
                ) else PortraitLayout(
                    transition = transition,
                    reduceMotion = reduceMotion,
                    controlsVisible = modeState.controlsVisible,
                    controlsHeight = controlsHeight,
                    compactControls = compactControls,
                    backProgress = { backProgress.floatValue },
                    onCollapse = { layoutState.collapseSoft() },
                    onControlsHeightChange = { controlsHeightPx.intValue = it },
                    artwork = artwork,
                    titleBlock = titleBlock,
                    compactHeader = compactHeader,
                    lyricsArea = lyricsArea,
                    controls = controls
                )
            }
        }

        editingSynced?.let { synced ->
            val raw = lyrics.raw

            TextFieldDialog(
                hintText = stringResource(R.string.enter_lyrics),
                initialTextInput = (if (synced) raw?.synced else raw?.fixed).orEmpty(),
                singleLine = false,
                maxLines = 10,
                isTextInputValid = { true },
                onDismiss = { editingSynced = null },
                onAccept = { text ->
                    transaction {
                        runCatching {
                            Database.insert(mediaItem)
                            Database.upsert(
                                Lyrics(
                                    songId = mediaId,
                                    fixed = if (synced) raw?.fixed else text,
                                    synced = if (synced) text else raw?.synced,
                                    startTime = raw?.startTime
                                )
                            )
                        }
                    }
                }
            )
        }

        if (picking) {
            var searchQuery by rememberSaveable {
                mutableStateOf(
                    mediaItem.mediaMetadata.title?.toString().orEmpty().let {
                        if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it.substringBeforeLast('.').trim()
                        else it
                    }
                )
            }

            LrcLibSearchDialog(
                query = searchQuery,
                setQuery = { searchQuery = it },
                onDismiss = { picking = false },
                onPick = { track ->
                    transaction {
                        runCatching {
                            Database.insert(mediaItem)
                            Database.upsert(
                                Lyrics(
                                    songId = mediaId,
                                    fixed = lyrics.raw?.fixed,
                                    synced = track.syncedLyrics,
                                    startTime = lyrics.raw?.startTime
                                )
                            )
                        }
                    }
                }
            )
        }

        Queue(
            layoutState = queueSheet,
            binder = binder,
            beforeContent = { },
            afterContent = { },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortraitLayout(
    transition: Transition<PlayerMode>,
    reduceMotion: Boolean,
    controlsVisible: Boolean,
    controlsHeight: Dp,
    compactControls: Boolean,
    backProgress: () -> Float,
    onCollapse: () -> Unit,
    onControlsHeightChange: (Int) -> Unit,
    artwork: @Composable (Dp, SharedScopes?) -> Unit,
    titleBlock: @Composable (Modifier, SharedScopes?) -> Unit,
    compactHeader: @Composable (Modifier, SharedScopes?) -> Unit,
    lyricsArea: @Composable (Modifier, Dp, Boolean) -> Unit,
    controls: @Composable (Modifier, Boolean) -> Unit
) = Column(
    modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(
            WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        )
) {
    Handle(onClick = onCollapse)

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
    ) {
        SharedTransitionLayout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - 0.05f * backProgress()
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            transition.AnimatedContent(
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                    else fadeIn(tween(durationMillis = 220, delayMillis = 90)) togetherWith
                        fadeOut(tween(durationMillis = 90))
                },
                contentKey = { it },
                modifier = Modifier.fillMaxSize()
            ) { stageMode ->
                val scopes = SharedScopes(
                    transition = this@SharedTransitionLayout,
                    visibility = this@AnimatedContent,
                    reduceMotion = reduceMotion
                )

                if (stageMode == PlayerMode.Lyrics) Column(modifier = Modifier.fillMaxSize()) {
                    compactHeader(
                        Modifier
                            .padding(vertical = 8.dp)
                            .height(72.dp),
                        scopes
                    )
                    lyricsArea(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        LyricsAnchor,
                        true
                    )
                } else BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = controlsHeight)
                ) {
                    val titleHeight = 56.dp
                    val artSize = min(maxWidth - 48.dp, maxHeight - titleHeight - 60.dp)
                        .coerceAtLeast(64.dp)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Spacer(modifier = Modifier.weight(14f))
                        artwork(artSize, scopes)
                        Spacer(modifier = Modifier.weight(44f))
                        titleBlock(Modifier.height(titleHeight), scopes)
                        Spacer(modifier = Modifier.weight(21f))
                    }
                }
            }
        }

        ControlsOverlay(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            controls(
                Modifier
                    .onSizeChanged { if (it.height > 0) onControlsHeightChange(it.height) }
                    .windowInsetsPadding(
                        WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Bottom)
                    ),
                compactControls
            )
        }
    }
}

/** Fades the controls in (200 ms) and out (300 ms, sliding 12 dp down). */
@Composable
private fun ControlsOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(300, easing = FastOutSlowInEasing)) +
            slideOutVertically(tween(300, easing = FastOutSlowInEasing)) {
                with(density) { 12.dp.roundToPx() }
            },
        modifier = modifier
    ) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LandscapeLayout(
    transition: Transition<PlayerMode>,
    reduceMotion: Boolean,
    artwork: @Composable (Dp, SharedScopes?) -> Unit,
    titleBlock: @Composable (Modifier, SharedScopes?) -> Unit,
    compactHeader: @Composable (Modifier, SharedScopes?) -> Unit,
    lyricsArea: @Composable (Modifier, Dp, Boolean) -> Unit,
    controls: @Composable (Modifier, Boolean) -> Unit
) = SharedTransitionLayout(
    modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)
) {
    val sharedTransitionScope = this

    Row(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
        ) {
            val paneHeight = maxHeight
            val artSize = (min(maxWidth, maxHeight) - 32.dp).coerceAtLeast(64.dp)

            transition.AnimatedContent(
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                    else fadeIn(tween(durationMillis = 220, delayMillis = 90)) togetherWith
                        fadeOut(tween(durationMillis = 90))
                },
                contentKey = { it },
                modifier = Modifier.fillMaxSize()
            ) { stageMode ->
                val scopes = SharedScopes(
                    transition = sharedTransitionScope,
                    visibility = this@AnimatedContent,
                    reduceMotion = reduceMotion
                )

                if (stageMode == PlayerMode.Lyrics) lyricsArea(Modifier.fillMaxSize(), paneHeight / 4, false)
                else Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    artwork(artSize, scopes)
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
        ) {
            transition.AnimatedContent(
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                    else fadeIn(tween(durationMillis = 220, delayMillis = 90)) togetherWith
                        fadeOut(tween(durationMillis = 90))
                },
                contentKey = { it },
                modifier = Modifier.fillMaxWidth()
            ) { stageMode ->
                val scopes = SharedScopes(
                    transition = sharedTransitionScope,
                    visibility = this@AnimatedContent,
                    reduceMotion = reduceMotion
                )

                if (stageMode == PlayerMode.Lyrics) compactHeader(
                    Modifier
                        .padding(vertical = 8.dp)
                        .height(72.dp),
                    scopes
                ) else titleBlock(Modifier.height(56.dp), scopes)
            }

            controls(Modifier, true)
        }
    }
}
