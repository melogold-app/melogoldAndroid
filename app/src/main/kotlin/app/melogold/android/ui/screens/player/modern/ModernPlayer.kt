package app.melogold.android.ui.screens.player.modern

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
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
import androidx.media3.common.Player
import app.melogold.android.Database
import app.melogold.android.R
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsSource
import app.melogold.android.preferences.PlayerPreferences
import app.melogold.android.query
import app.melogold.android.service.LOCAL_KEY_PREFIX
import app.melogold.android.service.PlayerService
import app.melogold.android.service.isLocal
import app.melogold.android.transaction
import app.melogold.android.ui.components.BottomSheetState
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.menu.MenuEntry
import app.melogold.android.ui.components.rememberBottomSheetState
import app.melogold.android.ui.modifiers.onSwipe
import app.melogold.android.ui.screens.player.LyricsMenu
import app.melogold.android.ui.screens.player.PlaybackErrorCard
import app.melogold.android.ui.screens.player.Queue
import app.melogold.android.ui.screens.player.StreamInfoSheet
import app.melogold.android.ui.screens.player.lyrics.LrcLibSearchDialog
import app.melogold.android.ui.screens.player.lyricseditor.LyricsEditorDialog
import app.melogold.android.ui.screens.player.lyricseditor.initialDraft
import app.melogold.android.ui.screens.player.lyricseditor.saveLyricsDraft
import app.melogold.android.ui.screens.player.playbackErrorMessage
import app.melogold.android.ui.screens.player.searchLyricsOnline
import app.melogold.android.ui.screens.player.sleepTimerLeft
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.SearchSource
import app.melogold.android.ui.theme.rememberArtworkColorScheme
import app.melogold.android.ui.theme.rememberContrastLevel
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.forceSeekToNext
import app.melogold.android.utils.forceSeekToPrevious
import app.melogold.android.utils.rememberReduceMotion
import app.melogold.android.utils.rememberTouchExplorationEnabled
import app.melogold.android.utils.toast
import app.melogold.android.utils.windowState
import app.melogold.compose.persist.findActivityNullable
import app.melogold.compose.routing.CallbackPredictiveBackHandler
import app.melogold.core.ui.LocalAppearance
import app.melogold.core.ui.setSystemBarAppearance
import app.melogold.core.ui.utils.isLandscape
import app.melogold.domain.lyrics.SyncedLine
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
    openPlayerMenu: (onStreamInfo: (() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier
) {
    val appearance = LocalAppearance.current
    val menuState = LocalMenuState.current
    val nav = LocalMainNav.current
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
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

    // The player follows the app theme (REWRITE §3.10): its colors come from the artwork, its
    // darkness from the app; system bar icons follow the same darkness
    val appScheme = MaterialTheme.colorScheme
    val appIsDark = appScheme.surface.luminance() < 0.5f
    val artworkBitmap = rememberArtworkBitmap(mediaItem.mediaMetadata.artworkUri)
    val artworkScheme = rememberArtworkColorScheme(
        key = mediaId,
        bitmap = artworkBitmap,
        isDark = appIsDark,
        contrastLevel = rememberContrastLevel(),
        delayMillis = 150L
    )
    val activity = remember(context) { context.findActivityNullable() }
    val currentAppIsDark by rememberUpdatedState(appearance.colorPalette.isDark)
    LaunchedEffect(layoutState.expanded, appIsDark) {
        if (layoutState.expanded) {
            withFrameNanos { }
            activity?.setSystemBarAppearance(isDark = appIsDark)
        } else activity?.setSystemBarAppearance(isDark = currentAppIsDark)
    }
    DisposableEffect(activity) {
        onDispose { activity?.setSystemBarAppearance(isDark = currentAppIsDark) }
    }
    val buffering = rememberBuffering(binder.player)

    val keepScreenOn = PlayerPreferences.lyricsKeepScreenAwake &&
        mode == PlayerMode.Lyrics &&
        shouldBePlaying
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Dialogs and menus (hosted outside of the palette override below, so they keep the app theme)
    var editing by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val showPlayerMenu: () -> Unit = {
        openPlayerMenu { menuState.display { StreamInfoSheet(mediaId = mediaId, binder = binder) } }
    }
    val sleepTimerMillisLeft = binder.sleepTimerLeft()
    val indicators: @Composable RowScope.() -> Unit = {
        PlaybackIndicators(
            sleepTimerMillisLeft = sleepTimerMillisLeft,
            speed = PlayerPreferences.speed,
            onClick = showPlayerMenu
        )
    }

    val copiedMessage = stringResource(R.string.copied)
    val importedMessage = stringResource(R.string.lyrics_imported)
    val importFailedMessage = stringResource(R.string.lyrics_import_failed)
    val importLyrics = rememberLyricsImporter(
        mediaItem = mediaItem,
        current = { lyrics.raw },
        onResult = { imported -> context.toast(if (imported) importedMessage else importFailedMessage) }
    )

    fun showLyricsMenu(header: @Composable ColumnScope.() -> Unit = { }) = menuState.display {
        val errorMessage = stringResource(R.string.no_browser_installed)
        val showingSynced = PlayerPreferences.preferSyncedLyrics
        val raw = lyrics.raw

        LyricsMenu(
            showingSynced = showingSynced,
            onToggleSynced = { PlayerPreferences.preferSyncedLyrics = !showingSynced },
            onEdit = { editing = true },
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
                                if (showingSynced) current.copy(synced = null, syncedSource = null)
                                else current.copy(fixed = null, fixedSource = null)
                            )
                        }
                    }
                    // Also covers a side that is already null (nothing changes in Room then)
                    lyrics.retry()
                }
            },
            onImport = importLyrics,
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
                    icon = R.drawable.ms_more_horiz,
                    text = stringResource(R.string.more_options),
                    onClick = showPlayerMenu
                )
            }
        )
    }

    val onLineLongPress: (SyncedLine) -> Unit = { line ->
        showLyricsMenu(
            header = {
                MenuEntry(
                    icon = R.drawable.ms_content_copy,
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
        Box(modifier = Modifier.modeSharedElement(scopes, "art")) {
            PlayerArtwork(
                mediaItem = mediaItem,
                size = size,
                playing = shouldBePlaying,
                reduceMotion = reduceMotion,
                onTap = { modeState.openLyrics(mediaId) },
                modifier = Modifier
                    .onSwipe(
                        animateOffset = true,
                        onSwipeLeft = { binder.player.forceSeekToNext() },
                        onSwipeRight = { binder.player.forceSeekToPrevious(seekToStart = false) }
                    )
            ) {
                PlaybackErrorCard(
                    isDisplayed = error != null,
                    message = playbackErrorMessage(mediaItem, error),
                    onRetry = { binder.player.prepare() },
                    onSkip = { binder.player.forceSeekToNext() },
                    onOtherVersions = if (mediaItem.isLocal) null else ({
                        layoutState.collapseSoft()
                        val metadata = mediaItem.mediaMetadata
                        nav.openSearch(
                            query = listOfNotNull(metadata.artist, metadata.title).joinToString(" ").trim(),
                            source = SearchSource.YouTube
                        )
                    }),
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
            sharedScopes = scopes,
            modifier = blockModifier
        )
    }

    val compactHeader: @Composable (Modifier, SharedScopes?) -> Unit = { headerModifier, scopes ->
        CompactHeader(
            mediaItem = mediaItem,
            liked = likedAt != null,
            onToggleLike = onToggleLike,
            onClick = onHeaderClick,
            sharedScopes = scopes,
            modifier = headerModifier
        ) {
            Box(modifier = Modifier.modeSharedElement(scopes, "art")) {
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
                    source = targetContent.source,
                    mediaId = target.mediaId,
                    controlsOverlapPx = overlapPx,
                    bottomPadding = if (overlaid) controlsHeight else 0.dp
                )

                LyricsContent.Loading, LyricsContent.Unknown -> LyricsLoading(anchor = anchor)

                LyricsContent.NotFound -> LyricsEmptyState(
                    onSearchLrcLib = { picking = true },
                    onImport = importLyrics,
                    onEnterManually = { editing = true }
                )

                LyricsContent.Failed -> LyricsErrorState(onRetry = lyrics::retry)
            }
        }
    }

    val controls: @Composable (Modifier, Boolean) -> Unit = { controlsModifier, compact ->
        PlayerControlsBlock(
            binder = binder,
            shouldBePlaying = shouldBePlaying,
            resolving = buffering && shouldBePlaying,
            reduceMotion = reduceMotion,
            onScrubbing = { modeState.scrubbing = it },
            compact = compact,
            modifier = controlsModifier,
            toolbar = {
                PlayerToolbar(
                    lyricsSelected = mode == PlayerMode.Lyrics,
                    queueSelected = queueOpen,
                    onLyricsClick = onLyricsClick,
                    onQueueClick = onQueueClick
                )
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        MaterialTheme(colorScheme = artworkScheme ?: appScheme) {
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
                ArtworkTintedBackground()

                val transition = rememberTransition(modeState.transitionState, label = "mode")

                // The overflow menu follows what is shown: the lyrics actions (with "More options"
                // leading to the track menu) while the lyrics are
                val onMore: () -> Unit = {
                    if (mode == PlayerMode.Lyrics) showLyricsMenu() else showPlayerMenu()
                }

                val fold = tabletopFold()

                if (fold != null) TabletopLayout(
                    fold = fold,
                    transition = transition,
                    reduceMotion = reduceMotion,
                    onCollapse = { layoutState.collapseSoft() },
                    onMore = onMore,
                    indicators = indicators,
                    artwork = artwork,
                    titleBlock = titleBlock,
                    compactHeader = compactHeader,
                    lyricsArea = lyricsArea,
                    controls = controls
                ) else if (landscape) LandscapeLayout(
                    transition = transition,
                    reduceMotion = reduceMotion,
                    onCollapse = { layoutState.collapseSoft() },
                    onMore = onMore,
                    indicators = indicators,
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
                    onMore = onMore,
                    indicators = indicators,
                    onControlsHeightChange = { controlsHeightPx.intValue = it },
                    artwork = artwork,
                    titleBlock = titleBlock,
                    compactHeader = compactHeader,
                    lyricsArea = lyricsArea,
                    controls = controls
                )
            }
        }

        if (editing) {
            val savedMessage = stringResource(R.string.lyrics_editor_saved)

            LyricsEditorDialog(
                mediaItem = mediaItem,
                binder = binder,
                initial = remember(mediaId) { initialDraft(lyrics.raw) },
                onSave = { draft ->
                    saveLyricsDraft(mediaItem = mediaItem, current = lyrics.raw, draft = draft)
                    editing = false
                    context.toast(savedMessage)
                },
                onDismiss = { editing = false }
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
                                    startTime = lyrics.raw?.startTime,
                                    fixedSource = lyrics.raw?.fixedSource,
                                    syncedSource = LyricsSource.LrcLib
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
    onMore: () -> Unit,
    indicators: @Composable RowScope.() -> Unit,
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
            WindowInsets.systemBarsIgnoringVisibility
                .union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        )
) {
    PlayerTopBar(onCollapse = onCollapse, onMore = onMore, indicators = indicators)

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
    onCollapse: () -> Unit,
    onMore: () -> Unit,
    indicators: @Composable RowScope.() -> Unit,
    artwork: @Composable (Dp, SharedScopes?) -> Unit,
    titleBlock: @Composable (Modifier, SharedScopes?) -> Unit,
    compactHeader: @Composable (Modifier, SharedScopes?) -> Unit,
    lyricsArea: @Composable (Modifier, Dp, Boolean) -> Unit,
    controls: @Composable (Modifier, Boolean) -> Unit
) = SharedTransitionLayout(
    modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
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
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
        ) {
            PlayerTopBar(onCollapse = onCollapse, onMore = onMore, indicators = indicators)
            Spacer(modifier = Modifier.weight(1f))

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

                // As tall as the title block: a phone on its side has no height to spare
                if (stageMode == PlayerMode.Lyrics) compactHeader(Modifier.height(56.dp), scopes)
                else titleBlock(Modifier.height(56.dp), scopes)
            }

            controls(Modifier, true)
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

private val TabletopControlsMaxWidth = 720.dp

/**
 * A foldable half-open like a laptop (REDESIGN §2.7): the bar and the artwork (or the lyrics) above
 * the [fold], the title and the controls below it, where the hand is; nothing on the fold itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabletopLayout(
    fold: Fold,
    transition: Transition<PlayerMode>,
    reduceMotion: Boolean,
    onCollapse: () -> Unit,
    onMore: () -> Unit,
    indicators: @Composable RowScope.() -> Unit,
    artwork: @Composable (Dp, SharedScopes?) -> Unit,
    titleBlock: @Composable (Modifier, SharedScopes?) -> Unit,
    compactHeader: @Composable (Modifier, SharedScopes?) -> Unit,
    lyricsArea: @Composable (Modifier, Dp, Boolean) -> Unit,
    controls: @Composable (Modifier, Boolean) -> Unit
) = SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
    val sharedTransitionScope = this
    val insets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val modeSpec: AnimatedContentTransitionScope<PlayerMode>.() -> ContentTransform = {
        if (reduceMotion) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
        else fadeIn(tween(durationMillis = 220, delayMillis = 90)) togetherWith fadeOut(tween(durationMillis = 90))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(fold.top)
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        ) {
            PlayerTopBar(onCollapse = onCollapse, onMore = onMore, indicators = indicators)

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val paneHeight = maxHeight
                val artSize = (min(maxWidth, maxHeight) - 16.dp).coerceAtLeast(64.dp)

                transition.AnimatedContent(
                    transitionSpec = modeSpec,
                    contentKey = { it },
                    modifier = Modifier.fillMaxSize()
                ) { stageMode ->
                    val scopes = SharedScopes(
                        transition = sharedTransitionScope,
                        visibility = this@AnimatedContent,
                        reduceMotion = reduceMotion
                    )

                    if (stageMode == PlayerMode.Lyrics) lyricsArea(Modifier.fillMaxSize(), paneHeight / 3, false)
                    else Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        artwork(artSize, scopes)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height((fold.bottom - fold.top).coerceAtLeast(0.dp)))

        // The controls keep a phone's reach on a wide window
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = TabletopControlsMaxWidth)
                .fillMaxWidth()
                .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        ) {
            transition.AnimatedContent(
                transitionSpec = modeSpec,
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

/** Whether [player] is buffering: play shows a loading indicator meanwhile (REWRITE §3.10.2). */
@Composable
private fun rememberBuffering(player: Player): Boolean {
    var buffering by remember(player) { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }

    player.DisposableListener {
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
        }
    }

    return buffering
}
