package app.melogold.android.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.melogold.android.Database
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.R
import app.melogold.android.models.SearchQuery
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.query
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.searchResultRoute
import app.melogold.android.ui.screens.searchresult.SearchResultScreen
import app.melogold.android.ui.shell.LocalLinkHandler
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.utils.secondary
import app.melogold.compose.routing.RouteHandlerScope
import app.melogold.core.ui.LocalAppearance
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private const val SEARCH_TAB_ONLINE = 0
private const val SEARCH_TAB_LIBRARY = 1

/**
 * `OnlineSearch` focuses its field 300 ms after `focused` turns on; the request is withdrawn after
 * that, so that the next one is a change again.
 */
private val FOCUS_REQUEST_WINDOW = 500.milliseconds

/**
 * The root of the Search section. **Temporary** (REDESIGN-M3E §6.1): the pre-redesign search
 * screen, online search with history and suggestions and search in the library, under a tab row.
 * Task T2.1 replaces it with the root of §2.2.
 *
 * Takes the query and the focus request of `MainNavState.openSearch()` / a repeated tap on the
 * Search item.
 */
@Route
@Composable
fun RouteHandlerScope.SearchRoot() {
    val nav = LocalMainNav.current
    val links = LocalLinkHandler.current
    val insets = LocalPlayerAwareWindowInsets.current
    val saveableStateHolder = rememberSaveableStateHolder()

    var tabIndex by rememberSaveable { mutableIntStateOf(SEARCH_TAB_ONLINE) }
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var focused by remember { mutableStateOf(false) }

    val pendingQuery = nav.pendingSearchQuery
    LaunchedEffect(pendingQuery) {
        nav.consumeSearchQuery()?.let { query ->
            textFieldValue = TextFieldValue(text = query, selection = TextRange(query.length))
            tabIndex = SEARCH_TAB_ONLINE
        }
        // The temporary root has one source only
        nav.consumeSearchSource()
    }

    val focusRequested = nav.searchFocusRequested
    LaunchedEffect(focusRequested) {
        if (!focusRequested || !nav.consumeSearchFocus()) return@LaunchedEffect

        tabIndex = SEARCH_TAB_ONLINE
        focused = true
        delay(FOCUS_REQUEST_WINDOW)
        focused = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        PrimaryTabRow(
            selectedTabIndex = tabIndex,
            modifier = Modifier
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .fillMaxWidth()
        ) {
            Tab(
                selected = tabIndex == SEARCH_TAB_ONLINE,
                onClick = { tabIndex = SEARCH_TAB_ONLINE },
                text = { Text(text = stringResource(R.string.online), maxLines = 1) }
            )
            Tab(
                selected = tabIndex == SEARCH_TAB_LIBRARY,
                onClick = { tabIndex = SEARCH_TAB_LIBRARY },
                text = { Text(text = stringResource(R.string.search_tab_in_library), maxLines = 1) }
            )
        }

        CompositionLocalProvider(
            LocalPlayerAwareWindowInsets provides insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                saveableStateHolder.SaveableStateProvider(tabIndex) {
                    when (tabIndex) {
                        SEARCH_TAB_ONLINE -> OnlineSearch(
                            textFieldValue = textFieldValue,
                            onTextFieldValueChange = { textFieldValue = it },
                            onSearch = { searchFor(it) },
                            onViewPlaylist = { links.open(it) },
                            decorationBox = { SearchDecorationBox(isEmpty = textFieldValue.text.isEmpty(), innerTextField = it) },
                            focused = focused
                        )

                        else -> LocalSongSearch(
                            textFieldValue = textFieldValue,
                            onTextFieldValueChange = { textFieldValue = it },
                            decorationBox = { SearchDecorationBox(isEmpty = textFieldValue.text.isEmpty(), innerTextField = it) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * `searchRoute` pushed on a stack: the pre-redesign search screen. Nothing opens it after the
 * redesign (Search is a section); kept for T2.1.
 */
@Route
@Composable
fun RouteHandlerScope.SearchRouteEntry(initialTextInput: String) {
    val links = LocalLinkHandler.current

    SearchScreen(
        initialTextInput = initialTextInput,
        onSearch = { searchFor(it) },
        onViewPlaylist = { links.open(it) }
    )
}

/**
 * `searchResultRoute`: the results of [query]. A tap on the query goes back to the root of Search
 * with the query in the field (REDESIGN-M3E §2.2).
 */
@Route
@Composable
fun SearchResultsEntry(query: String) {
    val nav = LocalMainNav.current

    SearchResultScreen(
        query = query,
        onSearchAgain = { nav.openSearch(query) }
    )
}

/**
 * Shows the results of [text] and remembers it in the search history (unless paused).
 */
private fun RouteHandlerScope.searchFor(text: String) {
    searchResultRoute(text)

    if (!DataPreferences.pauseSearchHistory) query {
        Database.insert(SearchQuery(query = text))
    }
}

@Composable
private fun SearchDecorationBox(
    isEmpty: Boolean,
    innerTextField: @Composable () -> Unit
) = Box {
    AnimatedVisibility(
        visible = isEmpty,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
        modifier = Modifier.align(Alignment.CenterEnd)
    ) {
        BasicText(
            text = stringResource(R.string.search_placeholder),
            maxLines = 1,
            style = LocalAppearance.current.typography.xxl.secondary
        )
    }

    innerTextField()
}
