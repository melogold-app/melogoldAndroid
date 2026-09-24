package app.melogold.android.ui.screens.search

import app.melogold.android.Database
import app.melogold.android.data.NetworkMonitor
import app.melogold.android.models.Playlist
import app.melogold.android.models.SearchQuery
import app.melogold.android.models.Song
import app.melogold.android.ui.model.ScreenModel
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.links.LinkTarget
import app.melogold.providers.innertube.links.YouTubeLinkParser
import app.melogold.providers.innertube.models.bodies.SearchSuggestionsBody
import app.melogold.providers.innertube.requests.searchSuggestions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val RECENT_QUERIES = 10
private const val RECENTLY_PLAYED = 20
private const val LIBRARY_SONGS = 3
private const val LIBRARY_PLAYLISTS = 2
private val LIBRARY_DELAY = 150.milliseconds
private val SUGGESTIONS_DELAY = 250.milliseconds
private val KEEP_WHILE_HIDDEN = 5.seconds

/**
 * Search, root and input (REWRITE §3.1.1, §3.1.2): recent queries and tracks from Room, and while
 * typing — the link in the field, matches in the library and suggestions of YouTube Music.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchModel(network: NetworkMonitor) : ScreenModel() {
    private val query = MutableStateFlow("")

    val isOnline: StateFlow<Boolean> = network.isOnline

    val recentQueries: StateFlow<List<SearchQuery>> = Database
        .queries("%")
        .map { it.take(RECENT_QUERIES) }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN.inWholeMilliseconds), emptyList())

    val recentlyPlayed: StateFlow<List<Song>> = Database
        .history(RECENTLY_PLAYED)
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN.inWholeMilliseconds), emptyList())

    /**
     * What the text in the field links to, if it is a link.
     */
    val link: StateFlow<LinkTarget?> = query
        .map { text ->
            YouTubeLinkParser.parse(text).takeIf { it !is LinkTarget.Search && it !is LinkTarget.Unsupported }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN.inWholeMilliseconds), null)

    private val typed: Flow<String> = query
        .map { it.trim() }
        .distinctUntilChanged()

    val librarySongs: StateFlow<List<Song>> = typed
        .debounce(LIBRARY_DELAY)
        .flatMapLatest { text ->
            if (text.isEmpty()) flowOf(emptyList())
            else patterns(text).let { (asTyped, lower, capitalized) ->
                Database.searchSongs(asTyped, lower, capitalized, LIBRARY_SONGS)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN.inWholeMilliseconds), emptyList())

    val libraryPlaylists: StateFlow<List<Playlist>> = typed
        .debounce(LIBRARY_DELAY)
        .flatMapLatest { text ->
            if (text.isEmpty()) flowOf(emptyList())
            else patterns(text).let { (asTyped, lower, capitalized) ->
                Database.searchPlaylists(asTyped, lower, capitalized, LIBRARY_PLAYLISTS)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN.inWholeMilliseconds), emptyList())

    /**
     * YouTube Music suggestions, 250 ms after typing stops; none without a network or for a link.
     */
    val suggestions: StateFlow<List<String>> = combine(typed.debounce(SUGGESTIONS_DELAY), isOnline) { text, online ->
        text.takeIf { online && it.isNotEmpty() && "://" !in it }
    }
        .distinctUntilChanged()
        .mapLatest { text ->
            if (text == null) emptyList()
            else Innertube.searchSuggestions(SearchSuggestionsBody(input = text))?.getOrNull().orEmpty()
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_WHILE_HIDDEN.inWholeMilliseconds), emptyList())

    fun onQueryChange(text: String) {
        query.value = text
    }

    private fun patterns(text: String): Triple<String, String, String> {
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return Triple(
            "%$escaped%",
            "%${escaped.lowercase()}%",
            "%${escaped.replaceFirstChar { it.uppercase() }}%"
        )
    }
}
