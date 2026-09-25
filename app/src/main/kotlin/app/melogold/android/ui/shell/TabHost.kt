package app.melogold.android.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import app.melogold.android.ui.screens.GlobalRoutes
import app.melogold.android.ui.screens.library.LibraryRoot
import app.melogold.android.ui.screens.search.SearchRoot
import app.melogold.android.ui.screens.settings.SettingsRoot
import app.melogold.android.ui.screens.settings.account.AccountRoutes
import app.melogold.android.ui.screens.trends.TrendsRoot
import app.melogold.android.ui.screens.whatsnew.WhatsNewRoot
import app.melogold.compose.persist.LocalPersistMap
import app.melogold.compose.persist.LocalPersistNamespace
import app.melogold.compose.persist.LocalTabParking
import app.melogold.compose.persist.PersistMapCleanup
import app.melogold.compose.persist.findActivityNullable
import app.melogold.compose.routing.Route
import app.melogold.compose.routing.RouteHandler
import app.melogold.compose.routing.RouteHandlerScope

/**
 * The five sections, one stack each (REDESIGN-M3E §6.1 "Устройство TabHost").
 *
 * - Only the current section is composed; the others are *parked*: their `rememberSaveable` state
 *   (stack, scroll positions) lives in a [androidx.compose.runtime.saveable.SaveableStateHolder]
 *   and their cached data stays in the `PersistMap` under the section's namespace.
 * - A switch has no shared transition: the new section fades in with the theme's
 *   `fastEffectsSpec`, the old one leaves at once. So no two stacks ever listen to global routes
 *   at the same time.
 * - Once a section's `RouteHandler` is composed, [MainNavState.tabReady] names it, which
 *   [MainNavState.navigate] waits for.
 */
@Composable
fun TabHost(
    nav: MainNavState,
    modifier: Modifier = Modifier
) {
    val holder = rememberSaveableStateHolder()
    val persistMap = LocalPersistMap.current
    val context = LocalContext.current

    DisposableEffect(nav, holder, persistMap) {
        nav.stateHolder = holder
        nav.persistMap = persistMap

        onDispose {
            if (nav.stateHolder === holder) nav.stateHolder = null
            // The parked sections are not composed, so nothing else cleans their cache
            if (context.findActivityNullable()?.isFinishing == true) nav.trimParked()
        }
    }

    val tab = nav.current
    val alpha = rememberSectionFadeIn(tab)

    Box(modifier = modifier.graphicsLayer { this.alpha = alpha.value }) {
        holder.SaveableStateProvider(key = tab.name) {
            SectionStack(nav = nav, tab = tab)
        }
    }
}

@Composable
private fun SectionStack(
    nav: MainNavState,
    tab: TopLevelDestination
) = CompositionLocalProvider(
    LocalPersistNamespace provides tab.persistNamespace,
    LocalTabParking provides nav.parkingOf(tab)
) {
    // The section's root screens persist their data without a cleanup of their own
    PersistMapCleanup(prefix = "")

    var child: Route? by rememberSaveable { mutableStateOf(null) }

    DisposableEffect(nav, tab) {
        val stack = TabStack(
            isAtRoot = { child == null },
            popToRoot = { child = null }
        )
        nav.attachStack(tab, stack)

        onDispose {
            nav.detachStack(tab, stack)
            if (nav.tabReady == tab) nav.tabReady = null
        }
    }

    RouteHandler(
        child = child,
        setChild = { child = it }
    ) {
        GlobalRoutes()
        SectionRoutes(tab)

        Content {
            SectionRoot(tab)
        }
    }

    // After the RouteHandler: its global route listener is already launched
    LaunchedEffect(nav, tab) {
        nav.tabReady = tab
    }
}

/**
 * Routes that only one section knows.
 */
@Composable
private fun RouteHandlerScope.SectionRoutes(tab: TopLevelDestination) = when (tab) {
    TopLevelDestination.Settings -> AccountRoutes()
    else -> Unit
}

@Composable
private fun RouteHandlerScope.SectionRoot(tab: TopLevelDestination) = when (tab) {
    TopLevelDestination.Search -> SearchRoot()
    TopLevelDestination.Library -> LibraryRoot()
    TopLevelDestination.Trends -> TrendsRoot()
    TopLevelDestination.WhatsNew -> WhatsNewRoot()
    TopLevelDestination.Settings -> SettingsRoot()
}

/**
 * Full opacity for the section shown first, a fade in for every section switched to later.
 */
@Composable
private fun rememberSectionFadeIn(tab: TopLevelDestination): Animatable<Float, *> {
    val spec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val isFirst = remember { booleanArrayOf(true) }
    val alpha = remember(tab) {
        Animatable(if (isFirst[0]) 1f else 0f).also { isFirst[0] = false }
    }

    LaunchedEffect(alpha) {
        alpha.animateTo(targetValue = 1f, animationSpec = spec)
    }

    return alpha
}
