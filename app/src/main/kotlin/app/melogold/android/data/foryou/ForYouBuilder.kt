package app.melogold.android.data.foryou

import android.util.Log
import app.melogold.android.data.repo.applyingHidden
import app.melogold.android.data.repo.pendingMutations
import app.melogold.android.Database
import app.melogold.android.data.repo.Timed
import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.bodies.NextBody
import app.melogold.providers.innertube.requests.relatedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.days

private const val TAG = "ForYouBuilder"
private const val SONG_COUNT = 20
private const val COLLECTION_COUNT = 10

/**
 * The personal picks of "New" (REWRITE §3.4).
 *
 * @param seeds the tracks the picks are based on; empty when there is no history yet
 */
@Serializable
data class ForYou(
    val seeds: List<Seed> = emptyList(),
    val songs: List<Innertube.SongItem> = emptyList(),
    val albums: List<Innertube.AlbumItem> = emptyList(),
    val artists: List<Innertube.ArtistItem> = emptyList(),
    val playlists: List<Innertube.PlaylistItem> = emptyList()
) {
    val hasHistory get() = seeds.isNotEmpty()

    @Serializable
    data class Seed(val videoId: String, val title: String)
}

/**
 * Builds [ForYou] (REWRITE §4.10.5): up to three seeds (the last like, the most played track of
 * 30 days, the last played one) → `related` of each → merged round-robin without duplicates or
 * hidden tracks. The result is cached in a file with its date.
 */
class ForYouBuilder(
    private val file: File,
    private val json: Json
) {
    suspend fun cached(): Timed<ForYou>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString<Timed<ForYou>>(file.readText())
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Dropping unreadable picks cache", e)
            file.delete()
            null
        }
    }

    suspend fun build(): Result<Timed<ForYou>> {
        val now = System.currentTimeMillis()
        val seeds = withContext(Dispatchers.IO) {
            listOfNotNull(
                Database.lastLikedSong(),
                Database.mostPlayedSongSince(now - 30.days.inWholeMilliseconds),
                Database.lastPlayedSong()
            ).distinctBy { it.id }
        }
        // No history yet: nothing to cache, the screen asks to play something first
        if (seeds.isEmpty()) return Result.success(Timed(ForYou(), now))

        val results = coroutineScope {
            seeds
                .map { seed -> async { Innertube.relatedPage(NextBody(videoId = seed.id)) } }
                .awaitAll()
        }
        val pages = results.mapNotNull { it?.getOrNull() }
        if (pages.isEmpty()) return Result.failure(
            results.firstNotNullOfOrNull { it?.exceptionOrNull() } ?: IOException("related pages are empty")
        )

        val hidden = withContext(Dispatchers.IO) {
            Database.hiddenSongIds().toSet().applyingHidden(pendingMutations.pending.value)
        }
        val seedIds = seeds.map { it.id }.toSet()

        val forYou = ForYou(
            seeds = seeds.map { ForYou.Seed(videoId = it.id, title = it.title) },
            songs = roundRobin(pages.map { it.songs.orEmpty() })
                .filter { it.info?.endpoint?.videoId != null }
                .distinctBy { it.key }
                .filter { it.key !in seedIds && it.key !in hidden }
                .take(SONG_COUNT),
            albums = roundRobin(pages.map { it.albums.orEmpty() })
                .filter { it.info?.endpoint?.browseId != null }
                .distinctBy { it.key }
                .take(COLLECTION_COUNT),
            artists = roundRobin(pages.map { it.artists.orEmpty() })
                .filter { it.info?.endpoint?.browseId != null }
                .distinctBy { it.key }
                .take(COLLECTION_COUNT),
            playlists = roundRobin(pages.map { it.playlists.orEmpty() })
                .filter { it.info?.endpoint?.browseId != null }
                .distinctBy { it.key }
                .take(COLLECTION_COUNT)
        )

        val timed = Timed(forYou, now)
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(timed))
                tmp.renameTo(file)
            }.onFailure { Log.w(TAG, "Could not cache picks", it) }
        }
        return Result.success(timed)
    }
}

/**
 * The first item of every list, then the second of every list, and so on.
 */
internal fun <T> roundRobin(lists: List<List<T>>): List<T> = buildList {
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (index in 0 until longest) lists.forEach { list -> list.getOrNull(index)?.let(::add) }
}
