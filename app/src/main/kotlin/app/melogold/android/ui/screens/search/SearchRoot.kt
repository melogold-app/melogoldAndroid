@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.melogold.android.ui.screens.search

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.melogold.android.Database
import app.melogold.android.LocalPlayerAwareWindowInsets
import app.melogold.android.LocalPlayerServiceBinder
import app.melogold.android.R
import app.melogold.android.models.Playlist
import app.melogold.android.models.SearchQuery
import app.melogold.android.models.Song
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.preferences.UIStatePreferences
import app.melogold.android.query
import app.melogold.android.ui.components.LocalMenuState
import app.melogold.android.ui.components.themed.NonQueuedMediaItemMenu
import app.melogold.android.ui.kit.Artwork
import app.melogold.android.ui.kit.SectionHeader
import app.melogold.android.ui.kit.TrackRow
import app.melogold.android.ui.model.rememberScreenModel
import app.melogold.android.ui.screens.Route
import app.melogold.android.ui.screens.localPlaylistRoute
import app.melogold.android.ui.screens.searchResultRoute
import app.melogold.android.ui.screens.searchresult.SearchResultsScreen
import app.melogold.android.ui.shell.LocalLinkHandler
import app.melogold.android.ui.shell.LocalMainNav
import app.melogold.android.ui.shell.SearchSource
import app.melogold.android.ui.shell.TopLevelDestination
import app.melogold.android.utils.asMediaItem
import app.melogold.android.utils.playWithRadio
import app.melogold.compose.routing.RouteHandlerScope
import app.melogold.providers.innertube.links.LinkTarget
import app.melogold.providers.innertube.links.YouTubeLinkParser
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val RecentCardSize = 112.dp

/**
 * The root of Search (REWRITE §3.1, M3 search guidelines): a search app bar on top; tapping it
 * opens focused search full screen — recent searches before typing, then the link in the field,
 * matches in the library and suggestions. Below the bar: the two-sources tip and "Recently played".
 */
@Route
@Composable
fun RouteHandlerScope.SearchRoot() {
    val model = rememberScreenModel("search/model") { SearchModel(network) }
    val nav = LocalMainNav.current
    val links = LocalLinkHandler.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val insets = LocalPlayerAwareWindowInsets.current
    val scope = rememberCoroutineScope()

    val textFieldState = rememberTextFieldState()
    val searchBarState = rememberSearchBarState()
    val rootSnackbar = remember { SnackbarHostState() }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect(model::onQueryChange)
    }

    // A query or focus request of MainNavState.openSearch() / a repeated tap on the Search item.
    // A query with a segment ("Other versions": YouTube) shows its results right away
    val pendingQuery = nav.pendingSearchQuery
    LaunchedEffect(pendingQuery) {
        val text = nav.consumeSearchQuery()
        val source = nav.consumeSearchSource() ?: SearchSource.All

        when {
            text.isNullOrBlank() -> Unit
            source != SearchSource.All -> searchFor(text.trim(), source)
            else -> {
                textFieldState.setTextAndPlaceCursorAtEnd(text)
                searchBarState.animateToExpanded()
            }
        }
    }
    val focusRequested = nav.searchFocusRequested
    LaunchedEffect(focusRequested) {
        if (focusRequested && nav.consumeSearchFocus()) searchBarState.animateToExpanded()
    }

    // M3 search: leaving focused search without searching returns the bar to its original state;
    // after a search the query stays in the field
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(searchBarState) {
        // The first value is the state on entry, not a transition
        snapshotFlow { searchBarState.currentValue }.drop(1).collect { value ->
            if (value == SearchBarValue.Expanded) submitted = false
            else if (!submitted) textFieldState.clearText()
        }
    }

    fun submit(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        submitted = true
        textFieldState.setTextAndPlaceCursorAtEnd(query)
        scope.launch { searchBarState.animateToCollapsed() }
        searchFor(query)
    }

    fun pasteLink() = scope.launch {
        val text = clipboard.getClipEntry()?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()

        if (text.isNullOrEmpty()) {
            rootSnackbar.showSnackbar(context.getString(R.string.search_clipboard_empty))
            return@launch
        }

        when (val target = YouTubeLinkParser.parse(text)) {
            is LinkTarget.Search -> {
                textFieldState.setTextAndPlaceCursorAtEnd(target.query)
                searchBarState.animateToExpanded()
            }

            else -> links.open(target)
        }
    }

    val inputField = @Composable {
        SearchInputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = ::submit,
            onBack = { scope.launch { searchBarState.animateToCollapsed() } },
            onPasteLink = { pasteLink() }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AppBarWithSearch(
                state = searchBarState,
                inputField = inputField,
                windowInsets = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )

            val contentInsets = insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            CompositionLocalProvider(LocalPlayerAwareWindowInsets provides contentInsets) {
                SearchHome(
                    model = model,
                    contentPadding = contentInsets.asPaddingValues(),
                    onTrends = { nav.select(TopLevelDestination.Trends) }
                )
            }
        }

        SnackbarHost(
            hostState = rootSnackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(insets.only(WindowInsetsSides.Bottom).asPaddingValues())
        )
    }

    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = inputField
    ) {
        FocusedSearch(
            model = model,
            text = textFieldState.text.toString(),
            onSubmit = ::submit,
            onFill = { textFieldState.setTextAndPlaceCursorAtEnd(it) },
            onOpenLink = { target ->
                scope.launch { searchBarState.animateToCollapsed() }
                links.open(target)
            },
            onOpenPlaylist = { playlist ->
                scope.launch { searchBarState.animateToCollapsed() }
                localPlaylistRoute(playlist.id)
            }
        )
    }
}

@Composable
private fun SearchInputField(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    onPasteLink: () -> Unit
) {
    val expanded = searchBarState.currentValue == SearchBarValue.Expanded

    SearchBarDefaults.InputField(
        textFieldState = textFieldState,
        searchBarState = searchBarState,
        onSearch = onSearch,
        placeholder = {
            Text(
                text = stringResource(R.string.search_hint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            if (expanded) IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ms_arrow_back),
                    contentDescription = stringResource(R.string.kit_back)
                )
            } else Icon(
                painter = painterResource(R.drawable.ms_search),
                contentDescription = null
            )
        },
        trailingIcon = {
            when {
                expanded && textFieldState.text.isNotEmpty() -> IconButton(onClick = { textFieldState.clearText() }) {
                    Icon(
                        painter = painterResource(R.drawable.ms_close),
                        contentDescription = stringResource(R.string.search_clear_field)
                    )
                }

                !expanded -> IconButton(onClick = onPasteLink) {
                    Icon(
                        painter = painterResource(R.drawable.ms_content_paste),
                        contentDescription = stringResource(R.string.search_paste_link)
                    )
                }
            }
        }
    )
}

/**
 * Under the collapsed search bar: the one-time tip, "Recently played", or a way to Trends.
 */
@Composable
private fun SearchHome(
    model: SearchModel,
    contentPadding: PaddingValues,
    onTrends: () -> Unit
) {
    val online by model.isOnline.collectAsState()
    val recentlyPlayed by model.recentlyPlayed.collectAsState()

    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!online) item(key = "offline") { OfflineBanner() }

        if (!UIStatePreferences.searchTipDismissed) item(key = "tip") {
            TipCard(onDismiss = { UIStatePreferences.searchTipDismissed = true })
        }

        if (recentlyPlayed.isNotEmpty()) {
            item(key = "recent/header") {
                SectionHeader(title = stringResource(R.string.search_recently_played))
            }
            item(key = "recent") { RecentlyPlayedRow(songs = recentlyPlayed) }
        } else item(key = "trends") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                TextButton(onClick = onTrends) {
                    Text(text = stringResource(R.string.whatsnew_whats_trending))
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner() = Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ms_cloud_off),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.search_offline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TipCard(onDismiss: () -> Unit) = Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    shape = RoundedCornerShape(24.dp),
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.search_tip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(text = stringResource(R.string.search_tip_ok))
        }
    }
}

/**
 * Recently played tracks as covers; a tap plays the track with its radio, a long tap opens the
 * track menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentlyPlayedRow(songs: List<Song>) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items = songs, key = { it.id }) { song ->
            Column(
                modifier = Modifier
                    .width(RecentCardSize)
                    .combinedClickable(
                        onClick = { binder?.playWithRadio(song.asMediaItem) },
                        onLongClick = {
                            menuState.display {
                                NonQueuedMediaItemMenu(onDismiss = menuState::hide, mediaItem = song.asMediaItem)
                            }
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Artwork(url = song.thumbnailUrl, size = RecentCardSize, shape = RoundedCornerShape(16.dp))
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Focused search, full screen: recent searches before typing; the link, library matches and
 * suggestions while typing (M3 search: categories separated by labels and gaps).
 */
@Composable
private fun FocusedSearch(
    model: SearchModel,
    text: String,
    onSubmit: (String) -> Unit,
    onFill: (String) -> Unit,
    onOpenLink: (LinkTarget) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit
) {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val recentQueries by model.recentQueries.collectAsState()
    val link by model.link.collectAsState()
    val songs by model.librarySongs.collectAsState()
    val playlists by model.libraryPlaylists.collectAsState()
    val suggestions by model.suggestions.collectAsState()
    var clearing by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (text.isBlank()) {
                if (DataPreferences.pauseSearchHistory) item(key = "paused") {
                    Label(text = stringResource(R.string.search_history_paused))
                } else if (recentQueries.isNotEmpty()) {
                    item(key = "recent/header") {
                        LabelWithAction(
                            text = stringResource(R.string.search_recent_queries),
                            action = stringResource(R.string.search_clear_history),
                            onAction = { clearing = true }
                        )
                    }
                    items(items = recentQueries, key = { "query/${it.id}" }) { searchQuery ->
                        QueryRow(
                            text = searchQuery.query,
                            icon = R.drawable.ms_history,
                            onClick = { onSubmit(searchQuery.query) },
                            onFill = { onFill(searchQuery.query) },
                            onRemove = {
                                query { Database.delete(searchQuery) }
                                scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    val result = snackbar.showSnackbar(
                                        message = context.getString(R.string.search_query_removed),
                                        actionLabel = context.getString(R.string.snackbar_undo)
                                    )
                                    if (result == SnackbarResult.ActionPerformed)
                                        query { Database.insert(SearchQuery(query = searchQuery.query)) }
                                }
                            }
                        )
                    }
                }
            } else {
                link?.let { target ->
                    item(key = "link") { LinkRow(target = target, onOpen = { onOpenLink(target) }) }
                }

                if (songs.isNotEmpty() || playlists.isNotEmpty()) {
                    item(key = "library/header") { Label(text = stringResource(R.string.search_in_library)) }
                    items(items = songs, key = { "song/${it.id}" }) { song ->
                        TrackRow(
                            title = song.title,
                            subtitle = song.artistsText,
                            artworkUrl = song.thumbnailUrl,
                            duration = song.durationText,
                            explicit = song.explicit,
                            onClick = { binder?.playWithRadio(song.asMediaItem) },
                            onMenu = null,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    items(items = playlists, key = { "playlist/${it.id}" }) { playlist ->
                        ListItem(
                            headlineContent = { Text(text = playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Artwork(url = playlist.thumbnail, size = 56.dp, shape = RoundedCornerShape(12.dp))
                            },
                            supportingContent = { Text(text = stringResource(R.string.library_playlists)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.combinedClickable(onClick = { onOpenPlaylist(playlist) })
                        )
                    }
                }

                if (suggestions.isNotEmpty()) {
                    item(key = "suggestions/header") { Label(text = stringResource(R.string.search_suggestions)) }
                    items(items = suggestions, key = { "suggestion/$it" }) { suggestion ->
                        QueryRow(
                            text = suggestion,
                            icon = R.drawable.ms_search,
                            onClick = { onSubmit(suggestion) },
                            onFill = { onFill(suggestion) }
                        )
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text(text = stringResource(R.string.search_clear_history_title)) },
        text = {
            Text(
                text = pluralStringResource(
                    R.plurals.search_clear_history_text,
                    recentQueries.size,
                    recentQueries.size
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clearing = false
                    query { Database.clearQueries() }
                }
            ) {
                Text(
                    text = stringResource(R.string.search_clear_history),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { clearing = false }) { Text(text = stringResource(R.string.kit_cancel)) }
        }
    )
}

@Composable
private fun Label(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
)

@Composable
private fun LabelWithAction(text: String, action: String, onAction: () -> Unit) = Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 8.dp, top = 8.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f)
    )
    TextButton(onClick = onAction) { Text(text = action) }
}

/**
 * A recent search or a suggestion: tap searches, ↖ puts the text into the field to refine it,
 * × (recent searches only) removes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueryRow(
    text: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    onFill: () -> Unit,
    onRemove: (() -> Unit)? = null
) = ListItem(
    headlineContent = { Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    leadingContent = {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    },
    trailingContent = {
        Row {
            IconButton(onClick = onFill) {
                Icon(
                    painter = painterResource(R.drawable.ms_north_west),
                    contentDescription = stringResource(R.string.search_fill),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onRemove != null) IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.ms_close),
                    contentDescription = stringResource(R.string.search_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    },
    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    modifier = Modifier.combinedClickable(onClick = onClick)
)

/**
 * "Open link: YouTube video" when the field holds a link; Apple Music, Yandex Music and Spotify
 * links say that importing comes later.
 */
@Composable
private fun LinkRow(target: LinkTarget, onOpen: () -> Unit) {
    val external = target as? LinkTarget.External
    val label = when {
        external != null -> stringResource(
            when (external.service) {
                "apple" -> R.string.search_import_apple
                "yandex" -> R.string.search_import_yandex
                else -> R.string.search_import_spotify
            }
        )

        else -> stringResource(
            R.string.search_open_link,
            stringResource(
                when (target) {
                    is LinkTarget.Video -> R.string.search_link_video
                    is LinkTarget.Playlist -> R.string.search_link_playlist
                    is LinkTarget.Album -> R.string.search_link_album
                    else -> R.string.search_link_channel
                }
            )
        )
    }

    ListItem(
        headlineContent = { Text(text = label) },
        leadingContent = {
            Icon(
                painter = painterResource(R.drawable.ms_link),
                contentDescription = null,
                tint = if (external == null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = if (external == null) Modifier.combinedClickable(onClick = onOpen) else Modifier
    )
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * `searchResultRoute`: the results of [query], starting on [source]. A tap on the query goes back
 * to Search with the query in the field.
 */
@Route
@Composable
fun SearchResultsEntry(query: String, source: SearchSource) {
    val nav = LocalMainNav.current

    SearchResultsScreen(
        query = query,
        initialSource = source,
        onEditQuery = { nav.openSearch(query) }
    )
}

/**
 * Shows the results of [text] on [source] and remembers it in the search history (unless paused).
 */
private fun RouteHandlerScope.searchFor(text: String, source: SearchSource = SearchSource.All) {
    searchResultRoute(text, source)

    if (!DataPreferences.pauseSearchHistory) query {
        Database.insert(SearchQuery(query = text))
    }
}
