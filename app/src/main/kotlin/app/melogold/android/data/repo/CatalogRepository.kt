package app.melogold.android.data.repo

import android.util.Log
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.requests.discoverPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

private const val TAG = "CatalogRepository"

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
    // The cache is per locale: switching the language must not show the old titles
    private val exploreFile get() = dir.resolve("explore-${Locale.getDefault().toLanguageTag()}.json")

    /**
     * The last successful `explore` response, or null if there is none.
     */
    suspend fun cachedExplore(): Timed<Innertube.DiscoverPage>? = withContext(Dispatchers.IO) {
        val file = exploreFile
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString<Timed<Innertube.DiscoverPage>>(file.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Dropping unreadable explore cache", e)
            file.delete()
            null
        }
    }

    /**
     * Fetches `explore` and, on success, replaces the cached copy.
     */
    suspend fun fetchExplore(): Result<Timed<Innertube.DiscoverPage>> {
        val page = Innertube.discoverPage()
            ?: throw CancellationException("explore request cancelled")

        return page.mapCatching { value ->
            if (value.trending.songs.isEmpty() && value.moods.isEmpty() && value.newReleaseAlbums.isEmpty())
                throw NoSuchElementException("explore page has no known sections")

            Timed(value = value, fetchedAt = System.currentTimeMillis()).also { timed ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        dir.mkdirs()
                        val tmp = File(dir, "${exploreFile.name}.tmp")
                        tmp.writeText(json.encodeToString(timed))
                        tmp.renameTo(exploreFile)
                    }.onFailure { Log.w(TAG, "Could not cache explore", it) }
                }
            }
        }
    }
}
