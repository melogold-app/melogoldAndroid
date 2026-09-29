package app.melogold.android.data.repo

import app.melogold.providers.songlink.SongLinkCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The answers of song.link kept for a day in a file of the cache (tasks/0017): the service allows about ten requests a
 * minute, and the same link is opened again and again. At most [MAX_ENTRIES] answers; the oldest go first. A file that
 * cannot be read is no cache, not an error.
 */
class SongLinkFileCache(
    private val file: File,
    private val ttlMs: Long = DAY_MS,
    private val now: () -> Long = System::currentTimeMillis
) : SongLinkCache {
    @Serializable
    private data class Entry(val at: Long, val body: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val entries: MutableMap<String, Entry> = load()

    @Synchronized
    override fun get(url: String): String? {
        val entry = entries[url] ?: return null
        if (now() - entry.at > ttlMs) {
            entries.remove(url)
            return null
        }
        return entry.body
    }

    @Synchronized
    override fun put(url: String, body: String) {
        entries[url] = Entry(now(), body)
        val expired = entries.filterValues { now() - it.at > ttlMs }.keys
        expired.forEach(entries::remove)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.minByOrNull { it.value.at }?.key ?: break)
        runCatching { file.writeText(json.encodeToString(serializer, entries)) }
    }

    private fun load(): MutableMap<String, Entry> = runCatching {
        json.decodeFromString(serializer, file.readText()).toMutableMap()
    }.getOrElse { mutableMapOf() }

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val MAX_ENTRIES = 100
    }
}
