package app.melogold.android

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
import android.view.KeyEvent
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
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
import app.melogold.android.ui.screens.searchResultRoute
import app.melogold.android.ui.shell.AppShell
import app.melogold.android.ui.shell.KeyboardShortcuts
import app.melogold.android.ui.shell.LinkHandler
import app.melogold.android.ui.shell.LocalAppSnackbar
import app.melogold.android.ui.shell.LocalLinkHandler
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.LocalPermissions
import app.melogold.android.ui.shell.MainNavState
import app.melogold.android.ui.shell.MainNavigationBarHeight
import app.melogold.android.ui.shell.SearchSource
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.android.ui.shell.rememberAppSnackbar
import app.melogold.android.ui.shell.rememberMainNavState
import app.melogold.android.ui.shell.rememberPermissionRequester
import app.melogold.android.ui.shell.rememberShellLayout
import app.melogold.android.ui.theme.rememberMelogoldColorScheme
import app.melogold.android.utils.DisposableListener
import app.melogold.android.utils.intent
import app.melogold.android.utils.rememberEffectiveMotionLevel
import app.melogold.compose.persist.LocalPersistMap
import app.melogold.compose.persist.PersistMap
import app.melogold.compose.preferences.PreferencesHolder
import app.melogold.core.ui.ColorMode
import app.melogold.core.ui.ColorSource
import app.melogold.core.ui.Dimensions
import app.melogold.core.ui.MotionLevel
import app.melogold.core.ui.SystemBarAppearance
import app.melogold.core.ui.isDark
import app.melogold.core.ui.theme.MelogoldTheme
import app.melogold.core.ui.utils.activityIntentBundle
import app.melogold.core.ui.utils.isAtLeastAndroid12
import app.melogold.core.ui.utils.isAtLeastAndroid17
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.bitmapFactoryExifOrientationStrategy
import coil3.decode.ExifOrientationStrategy
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Dispatcher

// Viewmodel in order to avoid recreating the entire Player state (WORKAROUND)
class MainViewModel : ViewModel() {
    var binder: PlayerService.Binder? by mutableStateOf(null)

    suspend fun awaitBinder(): PlayerService.Binder =
        binder ?: snapshotFlow { binder }.filterNotNull().first()
}

@Suppress("TooManyFunctions") // lifecycle callbacks
class MainActivity : ComponentActivity() {
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

        setContent()

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

        val scheme = rememberMelogoldColorScheme(
            source = colorSource,
            isDark = isDark,
            darkness = darkness
        )

        MelogoldTheme(
            scheme = scheme,
            motionLevel = rememberEffectiveMotionLevel(MotionLevel.Expressive),
            isBrandScheme = colorSource != ColorSource.System
        ) {
            SystemBarAppearance(isDark = scheme.isDark)

            BoxWithConstraints(
                modifier = Modifier.background(scheme.surface) then modifier.fillMaxSize()
            ) {
                CompositionLocalProvider(
                    LocalPlayerServiceBinder provides vm.binder,
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                    LocalPersistMap provides Dependencies.application.persistMap,
                    LocalAppContainer provides Dependencies.application.container,
                    LocalPermissions provides rememberPermissionRequester()
                ) {
                    content()
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    @OptIn(ExperimentalLayoutApi::class)
    fun setContent() = setContent {
        // Edge to edge: backgrounds reach under a camera cutout, content keeps clear of it
        val windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

        AppWrapper {
            val density = LocalDensity.current
            val shellLayout = rememberShellLayout()
            val bottomDp = with(density) { windowInsets.getBottom(density).toDp() }
            // 64 dp, taller with large font scales (measured by the shell)
            var navigationBarHeight by remember { mutableStateOf(MainNavigationBarHeight) }
            val bottomBarHeight = if (shellLayout.useRail) 0.dp else navigationBarHeight

            val imeVisible = WindowInsets.isImeVisible
            val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }

            // Constant per device form (REDESIGN-M3E §5.4): mini player + navigation bar, or the
            // mini player alone next to the rail. Recreating the state keeps its anchor.
            val playerBottomSheetState = rememberBottomSheetState(
                key = vm.binder,
                dismissedBound = 0.dp,
                collapsedBound = Dimensions.items.collapsedPlayerHeight + bottomBarHeight + bottomDp,
                expandedBound = maxHeight
            )

            val playerAwareWindowInsets = remember(
                bottomDp,
                shellLayout,
                bottomBarHeight,
                playerBottomSheetState.value,
                imeVisible,
                imeBottomDp
            ) {
                // Without a track the navigation bar remains; the keyboard covers the whole block
                val bottomBlock = bottomBarHeight + bottomDp
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
            // region R2.9
            val linkHandler = remember(mainNav) {
                LinkHandler(context = this@MainActivity, nav = mainNav, binder = vm::awaitBinder)
            }
            // endregion R2.9
            val snackbar = rememberAppSnackbar()

            DisposableEffect(mainNav, linkHandler) {
                shell.value = ShellHandles(nav = mainNav, linkHandler = linkHandler)
                onDispose { shell.value = null }
            }

            CompositionLocalProvider(
                LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                LocalMainNav provides mainNav,
                LocalLinkHandler provides linkHandler,
                LocalAppSnackbar provides snackbar
            ) {
                // region R2.1
                AppShell(
                    nav = mainNav,
                    layout = shellLayout,
                    playerSheetState = playerBottomSheetState,
                    snackbar = snackbar,
                    bottomBarHeight = bottomBarHeight,
                    onBottomBarHeightChange = { navigationBarHeight = it }
                )
                // endregion R2.1
            }

            vm.binder?.player.DisposableListener {
                object : Player.Listener {
                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int
                    ) = when {
                        mediaItem == null -> playerBottomSheetState.dismissSoft()
                        playerBottomSheetState.dismissed -> playerBottomSheetState.collapseSoft()
                        else -> Unit
                    }
                }
            }
        }
    }

    // region R2.9
    @Suppress("CyclomaticComplexMethod")
    private fun handleIntent(intent: Intent) = lifecycleScope.launch(Dispatchers.IO) {
        val extras = intent.extras?.activityIntentBundle

        when (intent.action) {
            // Search results open in the Search section (REDESIGN-M3E §1.3)
            Intent.ACTION_SEARCH -> {
                val query = extras?.query ?: return@launch
                extras.query = null

                awaitShell().nav.navigate(TopLevelDestination.Search) {
                    searchResultRoute.ensureGlobal(query, SearchSource.All)
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
    // endregion R2.9

    override fun onDestroy() {
        super.onDestroy()
        removeOnNewIntentListener(::handleIntent)
    }

    override fun onStop() {
        unbindService(serviceConnection)
        super.onStop()
    }
}

private class ShellHandles(
    val nav: MainNavState,
    val linkHandler: LinkHandler
)

val LocalPlayerServiceBinder = staticCompositionLocalOf<PlayerService.Binder?> { null }
val LocalPlayerAwareWindowInsets =
    compositionLocalOf<WindowInsets> { error("No player insets provided") }


private const val LYRICS_RECHECK = "lyricsRecheck1"
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
                // A violation only kills debug builds (REWRITE §5.3.1)
                .let { if (BuildConfig.DEBUG) it.penaltyDeath() else it }
                .build()
        )
        Dependencies.init(this)
        super.onCreate()

        ServiceNotifications.createAll()

        // The download manager lives on the thread that creates it: the main one
        container.downloads

        // The library follows the account on the Melogold server while signed in
        container.sync.start()

        // Once: lyrics that were not found are searched again with the better chain (2026-09-25)
        val migrations = getSharedPreferences("melogold_migrations", MODE_PRIVATE)
        if (!migrations.getBoolean(LYRICS_RECHECK, false)) query {
            Database.forgetMissingSyncedLyrics()
            Database.forgetMissingPlainLyrics()
            migrations.edit { putBoolean(LYRICS_RECHECK, true) }
        }
        container.updates.start()

        // Deletions waiting for "Undo" reach Room before the system may kill the app in the background
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) = container.pendingMutations.commitAll()
            }
        )
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(this)
        .components {
            // OkHttp with up to 16 requests per host (its default is 5): a list asks the same
            // thumbnail host for dozens of covers at once
            add(
                KtorNetworkFetcherFactory(
                    httpClient = {
                        HttpClient(OkHttp) {
                            engine {
                                config {
                                    dispatcher(Dispatcher().apply {
                                        maxRequests = 64
                                        maxRequestsPerHost = 16
                                    })
                                }
                            }
                        }
                    }
                )
            )
        }
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

    val container by lazy { AppContainer(this) }

    // region R2.1
    // endregion R2.1

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

    internal fun init(application: MainApplication) {
        this.application = application
    }
}

open class GlobalPreferencesHolder : PreferencesHolder(Dependencies.application, "preferences")
