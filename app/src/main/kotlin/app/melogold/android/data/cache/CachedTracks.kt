package app.melogold.android.data.cache

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import app.melogold.android.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CachedTracks"

/**
 * The tracks the player's cache holds whole, with their size (tasks/0001-audio-cache.md): they play without a
 * network and without resolving a stream, and show as "available offline". Every played track is cached and the
 * oldest go first, so this changes as music plays: it is counted again after [refresh] (start, a change of track,
 * "Downloads" opening), one count at a time.
 *
 * A track is whole when the cache has every byte from 0 to the length its stream had ([Database.contentLengthNow]).
 * Its length also goes into the cache's metadata: reading it to the end (a download copying it) then stops there
 * instead of asking the network what comes after.
 */
@OptIn(UnstableApi::class)
class CachedTracks(private val cache: () -> Cache, scope: CoroutineScope) {
    private val mutableTracks = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Video id → bytes of every track whole in the cache. */
    val tracks: StateFlow<Map<String, Long>> = mutableTracks.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            requests.collect {
                runCatching { count() }
                    .onSuccess { mutableTracks.value = it }
                    .onFailure { Log.w(TAG, "Could not count the cached tracks", it) }
            }
        }
    }

    fun refresh() {
        requests.tryEmit(Unit)
    }

    private fun count(): Map<String, Long> {
        val cache = cache()
        val whole = HashMap<String, Long>()
        for (key in cache.keys) {
            val length = Database.contentLengthNow(key)?.takeIf { it > 0 } ?: continue
            if (!cache.isCached(key, 0, length)) continue
            whole[key] = length
            if (ContentMetadata.getContentLength(cache.getContentMetadata(key)) != length) {
                val mutations = ContentMetadataMutations()
                ContentMetadataMutations.setContentLength(mutations, length)
                runCatching { cache.applyContentMetadataMutations(key, mutations) }
            }
        }
        return whole
    }
}
