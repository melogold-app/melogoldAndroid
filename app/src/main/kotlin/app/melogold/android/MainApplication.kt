package app.melogold.android

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.work.Configuration
import app.melogold.android.preferences.AppearancePreferences
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.service.PlayerService
import app.melogold.android.service.ServiceNotifications
import app.melogold.android.ui.components.rememberBottomSheetState
import app.melogold.android.ui.screens.player.Thumbnail
import app.melogold.android.ui.screens.searchResultRoute
import android.view.KeyEvent
import androidx.compose.runtime.DisposableEffect
import app.melogold.android.ui.shell.AppShell
import app.melogold.android.ui.shell.KeyboardShortcuts
import app.melogold.android.ui.shell.LinkHandler
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.ui.shell.LocalLinkHandler
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.MainNavState
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.android.ui.shell.rememberAppSnackbar
import app.melogold.android.ui.shell.rememberMainNavState
import app.melogold.android.ui.shell.rememberShellLayout
import kotlinx.coroutines.flow.MutableStateFlow
import app.melogold.android.ui.theme.rememberArtworkColorScheme
import app.melogold.android.ui.theme.rememberContrastLevel
import app.melogold.android.ui.theme.rememberMelogoldColorScheme
import app.melogold.android.ui.theme.withDarkness
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.KeyedCrossfade
import app.melogold.android.utils.LocalMonetCompat
import app.melogold.android.utils.collectProvidedBitmapAsState
import app.melogold.android.utils.intent
import app.melogold.android.utils.invokeOnReady
import app.melogold.android.utils.isInPip
import app.melogold.android.utils.maybeEnterPip
import app.melogold.android.utils.maybeExitPip
import app.melogold.android.utils.rememberEffectiveMotionLevel
import app.melogold.android.utils.setDefaultPalette
import app.melogold.android.utils.shouldBePlaying
import app.melogold.compose.persist.LocalPersistMap
import app.melogold.compose.persist.PersistMap
import app.melogold.compose.preferences.PreferencesHolder
import app.melogold.core.ui.ArtworkColorScope
import app.melogold.core.ui.ColorMode
import app.melogold.core.ui.ColorSource
import app.melogold.core.ui.Dimensions
import app.melogold.core.ui.SystemBarAppearance
import app.melogold.core.ui.isDark
import app.melogold.core.ui.shimmerTheme
import app.melogold.core.ui.theme.MelogoldTheme
import app.melogold.core.ui.utils.activityIntentBundle
import app.melogold.core.ui.utils.isAtLeastAndroid12
import app.melogold.core.ui.utils.isAtLeastAndroid17
import app.melogold.core.ui.utils.songBundle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.bitmapFactoryExifOrientationStrategy
import coil3.decode.ExifOrientationStrategy
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.kieronquinn.monetcompat.core.MonetActivityAccessException
import com.kieronquinn.monetcompat.core.MonetCompat
import com.kieronquinn.monetcompat.interfaces.MonetColorsChangedListener
import com.valentinilk.shimmer.LocalShimmerTheme
import dev.kdrag0n.monet.theme.ColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

private const val WHOLE_APP_ARTWORK_DELAY_MS = 500L

// Viewmodel in order to avoid recreating the entire Player state (WORKAROUND)
class MainViewModel : ViewModel() {
    var binder: PlayerService.Binder? by mutableStateOf(null)

    suspend fun awaitBinder(): PlayerService.Binder =
        binder ?: snapshotFlow { binder }.filterNotNull().first()
}

class MainActivity : ComponentActivity(), MonetColorsChangedListener {
    private val vm: MainViewModel by viewModels()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is PlayerService.Binder) vm.binder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vm.binder = null
            // Try to rebind, otherwise fail
            unbindService(this)
            bindService(intent<PlayerService>(), this, BIND_AUTO_CREATE)
        }
    }

    private var _monet: MonetCompat? by mutableStateOf(null)
    val monet get() = _monet ?: throw MonetActivityAccessException()

    /**
     * The shell's navigation and link handler, once the content is composed: intents that arrive
     * before (cold start) wait for them.
     */
    private val shell = MutableStateFlow<ShellHandles?>(null)
    private suspend fun awaitShell() = shell.filterNotNull().first()

    private val keyboardShortcuts = KeyboardShortcuts(
        nav = { shell.value?.nav },
        player = { vm.binder?.player }
    )

    override fun onStart() {
        super.onStart()
        bindService(intent<PlayerService>(), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        MonetCompat.setup(this)
        _monet = MonetCompat.getInstance()
        monet.setDefaultPalette()
        monet.addMonetColorsChangedListener(
            listener = this,
            notifySelf = false
        )
        monet.updateMonetColors()
        monet.invokeOnReady {
            setContent()
        }

        // A recreated activity restores its sections instead of acting on the old intent again
        if (savedInstanceState == null) intent?.let { handleIntent(it) }
        addOnNewIntentListener(::handleIntent)
    }

    override fun dispatchKeyEvent(event: KeyEvent) =
        super.dispatchKeyEvent(event) || keyboardShortcuts.handle(event)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The cached data of the sections not on screen is the cheapest to give back
        if (level >= TRIM_MEMORY_BACKGROUND) shell.value?.nav?.trimParked()
    }

    @Composable
    fun AppWrapper(
        modifier: Modifier = Modifier,
        content: @Composable BoxWithConstraintsScope.() -> Unit
    ) = with(AppearancePreferences) {
        val isSystemInDarkTheme = isSystemInDarkTheme()
        val isDark = colorMode == ColorMode.Dark ||
            (colorMode == ColorMode.System && isSystemInDarkTheme)

        val baseScheme = rememberMelogoldColorScheme(
            source = colorSource,
            isDark = isDark,
            darkness = darkness,
            contrast = contrast,
            monet = _monet
        )

        // "Artwork colors in the whole app" (REDESIGN-M3E §4.4): off on low-RAM devices
        val isLowRamDevice = remember { getSystemService<ActivityManager>()?.isLowRamDevice == true }
        val artworkScheme = if (artworkColorScope == ArtworkColorScope.WholeApp && !isLowRamDevice) {
            val bitmap = vm.binder.collectProvidedBitmapAsState()
            val mediaId = remember(bitmap) { vm.binder?.player?.currentMediaItem?.mediaId }

            val artwork = rememberArtworkColorScheme(
                key = mediaId,
                bitmap = bitmap,
                isDark = isDark,
                contrastLevel = rememberContrastLevel(contrast),
                delayMillis = WHOLE_APP_ARTWORK_DELAY_MS
            )
            remember(artwork, isDark, darkness) {
                artwork?.withDarkness(isDark = isDark, darkness = darkness)
            }
        } else null

        val scheme = artworkScheme ?: baseScheme

        MelogoldTheme(
            scheme = scheme,
            motionLevel = rememberEffectiveMotionLevel(motionLevel),
            thumbnailRoundness = thumbnailRoundness.dp,
            applyFontPadding = applyFontPadding,
            isBrandScheme = artworkScheme == null && colorSource != ColorSource.System
        ) {
            SystemBarAppearance(isDark = scheme.isDark)

            BoxWithConstraints(
                modifier = Modifier.background(scheme.surface) then modifier.fillMaxSize()
            ) {
                CompositionLocalProvider(
                    LocalPlayerServiceBinder provides vm.binder,
                    LocalCredentialManager provides Dependencies.credentialManager,
                    LocalShimmerTheme provides shimmerTheme(),
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                    LocalPersistMap provides Dependencies.application.persistMap,
                    LocalMonetCompat provides monet
                ) {
                    content()
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    @OptIn(ExperimentalLayoutApi::class)
    fun setContent() = setContent {
        val windowInsets = WindowInsets.systemBars

        AppWrapper(
            modifier = Modifier.padding(
                WindowInsets
                    .displayCutout
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues()
            )
        ) {
            val density = LocalDensity.current
            val shellLayout = rememberShellLayout()
            val bottomDp = with(density) { windowInsets.getBottom(density).toDp() }

            val imeVisible = WindowInsets.isImeVisible
            val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }

            // Constant per device form (REDESIGN-M3E §5.4): mini player + navigation bar, or the
            // mini player alone next to the rail. Recreating the state keeps its anchor.
            val playerBottomSheetState = rememberBottomSheetState(
                key = vm.binder,
                dismissedBound = 0.dp,
                collapsedBound = Dimensions.items.collapsedPlayerHeight + shellLayout.bottomBarHeight + bottomDp,
                expandedBound = maxHeight
            )

            val playerAwareWindowInsets = remember(
                bottomDp,
                shellLayout,
                playerBottomSheetState.value,
                imeVisible,
                imeBottomDp
            ) {
                // Without a track the navigation bar remains; the keyboard covers the whole block
                val bottomBlock = shellLayout.bottomBarHeight + bottomDp
                val shown = playerBottomSheetState.value.coerceIn(
                    bottomBlock..playerBottomSheetState.collapsedBound.coerceAtLeast(bottomBlock)
                )
                val bottom = if (imeVisible) maxOf(imeBottomDp, shown) else shown

                windowInsets
                    .only(
                        // The rail takes the start side
                        if (shellLayout.useRail) WindowInsetsSides.End + WindowInsetsSides.Top
                        else WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                    )
                    .add(WindowInsets(bottom = bottom))
            }

            val mainNav = rememberMainNavState(
                initialTab = { AppearancePreferences.lastTab },
                onTabSelect = { AppearancePreferences.lastTab = it }
            )
            val linkHandler = remember(mainNav) {
                LinkHandler(context = this@MainActivity, nav = mainNav, binder = vm::awaitBinder)
            }
            val snackbar = rememberAppSnackbar()

            DisposableEffect(mainNav, linkHandler) {
                shell.value = ShellHandles(nav = mainNav, linkHandler = linkHandler)
                onDispose { shell.value = null }
            }

            val pip = isInPip(
                onChange = {
                    // The sections leave composition during picture-in-picture: keep their data
                    mainNav.parkCurrent(parked = it)
                    if (!it || vm.binder?.player?.shouldBePlaying != true) return@isInPip
                    playerBottomSheetState.expandSoft()
                }
            )

            KeyedCrossfade(state = pip) { currentPip ->
                if (currentPip) Thumbnail(
                    isShowingLyrics = true,
                    onShowLyrics = { },
                    isShowingStatsForNerds = false,
                    onShowStatsForNerds = { },
                    onOpenDialog = { },
                    likedAt = null,
                    setLikedAt = { },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    shouldShowSynchronizedLyrics = true,
                    setShouldShowSynchronizedLyrics = { },
                    showLyricsControls = false
                ) else CompositionLocalProvider(
                    LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                    LocalMainNav provides mainNav,
                    LocalLinkHandler provides linkHandler,
                    LocalAppSnackbar provides snackbar
                ) {
                    AppShell(
                        nav = mainNav,
                        layout = shellLayout,
                        playerSheetState = playerBottomSheetState,
                        snackbar = snackbar
                    )
                }
            }

            vm.binder?.player.DisposableListener {
                object : Player.Listener {
                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int
                    ) = when {
                        mediaItem == null -> {
                            maybeExitPip()
                            playerBottomSheetState.dismissSoft()
                        }

                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                            mediaItem.mediaMetadata.extras?.songBundle?.isFromPersistentQueue != true -> {
                            if (AppearancePreferences.openPlayer) playerBottomSheetState.expandSoft()
                            else Unit
                        }

                        playerBottomSheetState.dismissed -> playerBottomSheetState.collapseSoft()

                        else -> Unit
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun handleIntent(intent: Intent) = lifecycleScope.launch(Dispatchers.IO) {
        val extras = intent.extras?.activityIntentBundle

        when (intent.action) {
            // Search results open in the Search section (REDESIGN-M3E §1.3)
            Intent.ACTION_SEARCH -> {
                val query = extras?.query ?: return@launch
                extras.query = null

                awaitShell().nav.navigate(TopLevelDestination.Search) {
                    searchResultRoute.ensureGlobal(query)
                }
            }

            Intent.ACTION_APPLICATION_PREFERENCES -> awaitShell().nav.navigate(TopLevelDestination.Settings) { }

            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                val uri = intent.data
                    ?: runCatching { extras?.text?.toUri() }.getOrNull()
                    ?: return@launch

                intent.data = null
                extras?.text = null

                awaitShell().linkHandler.open(uri)
            }

            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = when (extras?.mediaFocus) {
                    null, "vnd.android.cursor.item/*" -> extras?.query ?: extras?.text

                    MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE -> extras.genre

                    MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> extras.artist

                    MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> extras.album

                    "vnd.android.cursor.item/audio" -> listOfNotNull(
                        extras.album,
                        extras.artist,
                        extras.genre,
                        extras.title
                    ).joinToString(separator = " ")

                    @Suppress("deprecation")
                    MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> extras.playlist

                    else -> null
                }

                if (!query.isNullOrBlank()) vm.awaitBinder().playFromSearch(query)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        monet.removeMonetColorsChangedListener(this)
        _monet = null

        removeOnNewIntentListener(::handleIntent)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        super.onStop()
    }

    override fun onMonetColorsChanged(
        monet: MonetCompat,
        monetColors: ColorScheme,
        isInitialChange: Boolean
    ) {
        // API 31+ uses the platform dynamic colors, which recreate the activity by themselves
        if (!isInitialChange && !isAtLeastAndroid12 && AppearancePreferences.colorSource == ColorSource.System)
            recreate()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (AppearancePreferences.autoPip && vm.binder?.player?.shouldBePlaying == true) maybeEnterPip()
    }
}

private class ShellHandles(
    val nav: MainNavState,
    val linkHandler: LinkHandler
)

val LocalPlayerServiceBinder = staticCompositionLocalOf<PlayerService.Binder?> { null }
val LocalPlayerAwareWindowInsets =
    compositionLocalOf<WindowInsets> { error("No player insets provided") }
val LocalCredentialManager = staticCompositionLocalOf { Dependencies.credentialManager }

class MainApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {
    override fun onCreate() {
        StrictMode.setVmPolicy(
            VmPolicy.Builder()
                .let {
                    if (isAtLeastAndroid12) it.detectUnsafeIntentLaunch()
                    else it
                }
                .let {
                    if (isAtLeastAndroid17) it.detectImplicitUriPermissionGrant()
                    else it
                }
                .penaltyLog()
                .penaltyDeath()
                .build()
        )
        Dependencies.init(this)

        MonetCompat.debugLog = BuildConfig.DEBUG
        super.onCreate()

        MonetCompat.enablePaletteCompat()
        ServiceNotifications.createAll()
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(this)
        .crossfade(true)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.1)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("coil"))
                .maxSizeBytes(DataPreferences.coilDiskCacheMaxSize.bytes)
                .build()
        }
        .bitmapFactoryExifOrientationStrategy(ExifOrientationStrategy.IGNORE)
        .let { if (BuildConfig.DEBUG) it.logger(DebugLogger()) else it }
        .build()

    val persistMap = PersistMap()

    override val workManagerConfiguration = Configuration.Builder()
        .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
        .build()
}

object Dependencies {
    lateinit var application: MainApplication
        private set

    val py by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(application))
        Python.getInstance()
    }

    private val module by lazy { py.getModule("download") }

    val quickjsPath by lazy {
        File(application.applicationInfo.nativeLibraryDir, "libqjs.so")
            .also { if (!it.canExecute()) it.setExecutable(true) }
    }

    fun runDownload(id: String): String = module
        .callAttr("download", quickjsPath.absolutePath, id)
        .toString()

    fun upgradeYoutubeDl(packageName: String = "yt-dlp"): Boolean {
        val success = runCatching { module.callAttr("upgrade", packageName) }
            .also { it.exceptionOrNull()?.printStackTrace() }
            .isSuccess
        if (!success) Log.e("Python", "Upgrading $packageName resulted in non-zero exit code!")
        return success
    }

    val credentialManager by lazy { CredentialManager.create(application) }

    internal fun init(application: MainApplication) {
        this.application = application
    }
}

open class GlobalPreferencesHolder : PreferencesHolder(Dependencies.application, "preferences")
