package app.melogold.android.data.repo

import android.util.Log
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.requests.discoverPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private const val TAG = "CatalogRepository"

// Trends and New both load `explore`: a second request right after the first reuses its answer
private val SHARED_FETCH_WINDOW = 30.seconds

/**
 * A value together with the moment it was fetched from the network.
 */
@Serializable
data class Timed<T>(val value: T, val fetchedAt: Long)

/**
 * YouTube Music catalog pages (REWRITE §4.11.2). `explore` (Trends and New releases, one
 * `FEmusic_explore` request) is cached in a file with its date so the screens have something to
 * show without the network.
 */
class CatalogRepository(
    private val dir: File,
    private val json: Json
) {
    private val exploreMutex = Mutex()

    @Volatile
    private var exploreInMemory: Pair<String, Timed<Innertube.DiscoverPage>>? = null

    // The cache is per locale: switching the language must not show the old titles
    private val localeTag get() = Locale.getDefault().toLanguageTag()
    private fun exploreFile(locale: String) = dir.resolve("explore-$locale.json")

    /**
     * The last successful `explore` response, or null if there is none.
     */
    suspend fun cachedExplore(): Timed<Innertube.DiscoverPage>? {
        val locale = localeTag
        exploreInMemory?.takeIf { it.first == locale }?.let { return it.second }

        return withContext(Dispatchers.IO) {
            val file = exploreFile(locale)
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString<Timed<Innertube.DiscoverPage>>(file.readText())
                    .also { exploreInMemory = locale to it }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "Dropping unreadable explore cache", e)
                file.delete()
                null
            }
        }
    }

    /**
     * Fetches `explore` and, on success, replaces the cached copy.
     */
    suspend fun fetchExplore(): Result<Timed<Innertube.DiscoverPage>> = exploreMutex.withLock {
        val locale = localeTag
        exploreInMemory
            ?.takeIf { (cachedLocale, timed) ->
                cachedLocale == locale &&
                    System.currentTimeMillis() - timed.fetchedAt < SHARED_FETCH_WINDOW.inWholeMilliseconds
            }
            ?.let { return@withLock Result.success(it.second) }

        val page = Innertube.discoverPage()
            ?: throw CancellationException("explore request cancelled")

        page.mapCatching { value ->
            if (value.trending.songs.isEmpty() && value.moods.isEmpty() && value.newReleaseAlbums.isEmpty())
                throw NoSuchElementException("explore page has no known sections")

            Timed(value = value, fetchedAt = System.currentTimeMillis()).also { timed ->
                exploreInMemory = locale to timed
                withContext(Dispatchers.IO) {
                    runCatching {
                        dir.mkdirs()
                        val file = exploreFile(locale)
                        val tmp = File(dir, "${file.name}.tmp")
                        tmp.writeText(json.encodeToString(timed))
                        tmp.renameTo(file)
                    }.onFailure { Log.w(TAG, "Could not cache explore", it) }
                }
            }
        }
    }
}
