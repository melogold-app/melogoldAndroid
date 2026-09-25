package app.melogold.android.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import app.melogold.compose.persist.PersistMap
import app.melogold.compose.persist.TabParking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The navigation between the five top-level sections (REDESIGN-M3E §1.3, §6.1).
 *
 * Every section keeps its own stack, see [TabHost]; exactly one section is composed at a time.
 * Screens reach this state through [LocalMainNav].
 *
 * @property startTab the section the app was opened on. "Back" from the root of any other section
 * goes there, "back" from its root leaves the app. It is the section the user was in when they last
 * left the app (REDESIGN-M3E §8.1), [TopLevelDestination.FirstLaunch] on the very first launch.
 */
@Suppress("TooManyFunctions") // the navigation API of the whole app
@Stable
class MainNavState internal constructor(
    initialTab: TopLevelDestination,
    val startTab: TopLevelDestination,
    initialSearchQuery: String?,
    initialSearchSource: SearchSource?,
    private val scope: CoroutineScope,
    private val onTabSelect: (TopLevelDestination) -> Unit
) {
    /**
     * The section on screen.
     */
    var current by mutableStateOf(initialTab)
        private set

    /**
     * The section whose stack is composed and already listens to global routes, `null` while a
     * switch is in progress. Set by [TabHost] after the section's `RouteHandler` is composed.
     */
    var tabReady: TopLevelDestination? by mutableStateOf(null)
        internal set

    private val stacks = mutableMapOf<TopLevelDestination, TabStack>()
    private val parkings = TopLevelDestination.entries.associateWith { TabParking() }
    internal var stateHolder: SaveableStateHolder? = null
    internal var persistMap: PersistMap? = null

    private val reselectFlow = MutableSharedFlow<TopLevelDestination>(extraBufferCapacity = 1)

    /**
     * Emits the current section when its item is tapped again while its stack is at the root: the
     * root scrolls to the top and expands its header ([TabRootScaffold] does this by itself).
     */
    val reselects: SharedFlow<TopLevelDestination> = reselectFlow.asSharedFlow()

    /**
     * A query handed to the Search section by [openSearch], taken with [consumeSearchQuery].
     */
    var pendingSearchQuery: String? by mutableStateOf(initialSearchQuery)
        private set
    private var pendingSearchSource: SearchSource? = initialSearchSource

    /**
     * Whether the Search section should focus its field and show the keyboard; taken with
     * [consumeSearchFocus].
     */
    var searchFocusRequested by mutableStateOf(false)
        private set

    /**
     * A tap on the navigation item of [tab].
     */
    fun onItemClick(tab: TopLevelDestination) = if (tab == current) reselect() else select(tab)

    /**
     * Shows [tab] with its stack as the user left it.
     */
    fun select(tab: TopLevelDestination) {
        if (tab == current) return

        parkingOf(current).isParked = true
        parkingOf(tab).isParked = false
        tabReady = null
        current = tab
        onTabSelect(tab)
    }

    /**
     * The current section's item tapped again: back to the section's root; at the root, to the top
     * (and, in Search, into the search field).
     */
    fun reselect() {
        val tab = current
        val stack = stacks[tab]

        if (stack != null && !stack.isAtRoot()) stack.popToRoot()
        else {
            reselectFlow.tryEmit(tab)
            if (tab == TopLevelDestination.Search) searchFocusRequested = true
        }
    }

    /**
     * Switches to [tab] (the current section when `null`) and runs [action] once its stack
     * listens to global routes, so that e.g. `albumRoute.ensureGlobal(id)` opens the album in that
     * section instead of getting lost while the section is still being composed
     * (REDESIGN-M3E §0.3 p. 2).
     *
     * Safe to call from any thread; [action] runs on the main thread.
     */
    fun navigate(tab: TopLevelDestination? = null, action: suspend () -> Unit): Job = scope.launch {
        val target = tab ?: current

        select(target)
        snapshotFlow { tabReady }.first { it == target }
        withFrameNanos { }
        action()
    }

    /**
     * Opens the Search section at its root with [query] in the field; with a [source] other than
     * "All" and a query, it shows the results on that segment instead.
     */
    fun openSearch(query: String = "", source: SearchSource = SearchSource.All) {
        val search = TopLevelDestination.Search

        pendingSearchQuery = query
        pendingSearchSource = source

        if (current == search) stacks[search]?.popToRoot()
        else {
            resetTab(search)
            select(search)
        }
        searchFocusRequested = source == SearchSource.All || query.isBlank()
    }

    /**
     * Switches to Search, as it was left, and asks it to focus the search field.
     */
    fun focusSearch() {
        select(TopLevelDestination.Search)
        searchFocusRequested = true
    }

    fun consumeSearchQuery(): String? = pendingSearchQuery.also { pendingSearchQuery = null }

    fun consumeSearchSource(): SearchSource? = pendingSearchSource.also { pendingSearchSource = null }

    fun consumeSearchFocus(): Boolean = searchFocusRequested.also { searchFocusRequested = false }

    internal fun parkingOf(tab: TopLevelDestination) = parkings.getValue(tab)

    internal fun attachStack(tab: TopLevelDestination, stack: TabStack) {
        stacks[tab] = stack
    }

    internal fun detachStack(tab: TopLevelDestination, stack: TabStack) {
        if (stacks[tab] === stack) stacks -= tab
    }

    /**
     * Drops the cached screen data of the sections that are not on screen (low memory, or the app
     * is closing for good). Their stacks come back and load their data again.
     */
    fun trimParked() = TopLevelDestination.entries
        .filter { it != current }
        .forEach { persistMap?.clean(it.persistNamespace) }

    private fun resetTab(tab: TopLevelDestination) {
        if (tab == current) return

        stateHolder?.removeState(tab.name)
        persistMap?.clean(tab.persistNamespace)
    }

    internal companion object {
        fun saver(
            scope: CoroutineScope,
            onTabSelect: (TopLevelDestination) -> Unit
        ) = Saver<MainNavState, List<String?>>(
            save = {
                listOf(
                    it.current.name,
                    it.startTab.name,
                    it.pendingSearchQuery,
                    it.pendingSearchSource?.name
                )
            },
            restore = { saved ->
                MainNavState(
                    initialTab = TopLevelDestination.valueOf(saved[0]!!),
                    startTab = TopLevelDestination.valueOf(saved[1]!!),
                    initialSearchQuery = saved[2],
                    initialSearchSource = saved[3]?.let(SearchSource::valueOf),
                    scope = scope,
                    onTabSelect = onTabSelect
                ).apply {
                    TopLevelDestination.entries.forEach { tab -> parkingOf(tab).isParked = tab != current }
                }
            }
        )
    }
}

/**
 * A section's stack as [TabHost] exposes it to [MainNavState].
 */
internal class TabStack(
    val isAtRoot: () -> Boolean,
    val popToRoot: () -> Unit
)

/**
 * @param initialTab the section to open when there is no saved state
 * @param onTabSelect called on every switch, e.g. to remember the section for the next launch
 */
@Composable
fun rememberMainNavState(
    initialTab: () -> TopLevelDestination,
    onTabSelect: (TopLevelDestination) -> Unit
): MainNavState {
    val scope = rememberCoroutineScope()
    val currentOnTabSelect by rememberUpdatedState(onTabSelect)
    val onSelect: (TopLevelDestination) -> Unit = { currentOnTabSelect(it) }

    return rememberSaveable(saver = MainNavState.saver(scope, onSelect)) {
        val tab = initialTab()

        MainNavState(
            initialTab = tab,
            startTab = tab,
            initialSearchQuery = null,
            initialSearchSource = null,
            scope = scope,
            onTabSelect = onSelect
        ).apply {
            TopLevelDestination.entries.forEach { parkingOf(it).isParked = it != tab }
        }
    }
}

val LocalMainNav = staticCompositionLocalOf<MainNavState> { error("No MainNavState provided") }
