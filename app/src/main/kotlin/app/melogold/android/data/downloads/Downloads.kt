@file:OptIn(UnstableApi::class)

package app.melogold.android.data.downloads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import app.melogold.android.Database
import app.melogold.android.data.repo.applyingDownloads
import app.melogold.android.data.repo.withPending
import app.melogold.android.models.DownloadFailure
import app.melogold.android.models.DownloadState
import app.melogold.android.models.DownloadWaitReason
import app.melogold.android.models.TrackDownload
import app.melogold.android.preferences.DataPreferences
import app.melogold.android.query
import app.melogold.android.service.DownloadsService
import app.melogold.android.service.PlayerService
import app.melogold.android.service.UnplayableException
import app.melogold.android.service.VideoIdMismatchException
import app.melogold.android.service.isLocal
import app.melogold.android.transaction
import app.melogold.android.utils.download
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Downloads"
private const val DIRECTORY = "downloads"
private const val INDEX_NAME = "melogold"
private const val MAX_PARALLEL = 2
private const val MIN_RETRIES = 3
private const val PROGRESS_INTERVAL_MS = 1_000L

/**
 * Real downloads (REWRITE §4.7): Media3's [DownloadManager] writes the bytes into a cache of their
 * own in `filesDir/downloads`, which nothing evicts; [DownloadsService] runs it in the foreground.
 * The state of every download is mirrored into Room (table `Download`), which the screens read.
 *
 * Created on the main thread: the manager lives on the thread that creates it.
 */
class Downloads(private val context: Context, private val scope: CoroutineScope) {
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val main = Handler(Looper.getMainLooper())
    private var progressJob: Job? = null
    private val mutablePaused = MutableStateFlow(false)

    /** The bytes of the downloads, by video id; the player reads it before anything else. */
    val cache: Cache = SimpleCache(context.filesDir.resolve(DIRECTORY), NoOpCacheEvictor(), databaseProvider)

    val manager: DownloadManager = DownloadManager(
        context,
        DefaultDownloadIndex(databaseProvider, INDEX_NAME),
        DefaultDownloaderFactory(
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(
                    PlayerService.createYouTubeDataSourceResolverFactory(context = context, cache = null, chunkLength = null)
                ),
            Executors.newFixedThreadPool(MAX_PARALLEL)
        )
    ).apply {
        maxParallelDownloads = MAX_PARALLEL
        minRetryCount = MIN_RETRIES
        requirements = requirements(DataPreferences.downloadsWifiOnly)
        addListener(Tracker())
    }

    /** Every download by video id, as Room has them. */
    val states: StateFlow<Map<String, TrackDownload>> = Database.downloads()
        .map { list -> list.associateBy { it.videoId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** "Pause" in Downloads: nothing downloads until "Resume". */
    val paused: StateFlow<Boolean> = mutablePaused.asStateFlow()

    /** What the screens show: without the downloads being removed (REWRITE §3.11.9). */
    val visible: StateFlow<Map<String, TrackDownload>> = states
        .withPending { applyingDownloads(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Downloads [mediaItem] ("Download" on the track itself). */
    fun download(mediaItem: MediaItem) {
        if (mediaItem.isLocal) return
        val videoId = mediaItem.mediaId

        transaction {
            Database.insert(mediaItem)
            val current = Database.download(videoId)
            Database.upsert(
                current?.copy(manual = true) ?: TrackDownload(
                    videoId = videoId,
                    manual = true,
                    state = DownloadState.Queued,
                    requestedAt = System.currentTimeMillis()
                )
            )
            if (current == null || current.state == DownloadState.Failed) main.post { request(videoId) }
        }
    }

    /** Tries a failed download again. */
    fun retry(videoId: String) = query {
        val current = Database.download(videoId) ?: return@query
        Database.upsert(current.copy(state = DownloadState.Queued, failureCode = null))
        main.post { request(videoId) }
    }

    /** Stops and forgets the download of [videoId]: its bytes go too. */
    fun remove(videoId: String) {
        query { Database.deleteDownload(videoId) }
        main.post { manager.removeDownload(videoId) }
    }

    fun removeAll() {
        query { Database.deleteAllDownloads() }
        main.post { manager.removeAllDownloads() }
    }

    fun pauseAll() = main.post { manager.pauseDownloads() }

    fun resumeAll() = main.post { manager.resumeDownloads() }

    /** "Only over Wi-Fi" (REWRITE §3.5.6). */
    fun setWifiOnly(wifiOnly: Boolean) {
        DataPreferences.downloadsWifiOnly = wifiOnly
        main.post { manager.requirements = requirements(wifiOnly) }
    }

    /** Bytes the downloads take. */
    val size: Long get() = cache.cacheSpace

    private fun request(videoId: String) {
        val request = DownloadRequest.Builder(videoId, "https://music.youtube.com/watch?v=$videoId".toUri())
            .setCustomCacheKey(videoId)
            .setData(videoId.encodeToByteArray())
            .build()

        context.download<DownloadsService>(request).exceptionOrNull()?.let {
            Log.e(TAG, "Could not start the download of $videoId", it)
        }
    }

    private fun requirements(wifiOnly: Boolean) = Requirements(
        (if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK) or Requirements.DEVICE_STORAGE_NOT_LOW
    )

    /** Mirrors the manager into Room. */
    private inner class Tracker : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) = reconcile(downloadManager)

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val notMet = downloadManager.notMetRequirements
            // In order: the transaction executor runs one block at a time
            transaction { write(download, notMet, finalException) }
            if (download.state == Download.STATE_DOWNLOADING) followProgress(downloadManager)
        }

        override fun onDownloadsPausedChanged(downloadManager: DownloadManager, downloadsPaused: Boolean) {
            mutablePaused.value = downloadsPaused
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = query {
            Database.deleteDownload(download.request.id)
        }

        override fun onRequirementsStateChanged(
            downloadManager: DownloadManager,
            requirements: Requirements,
            notMetRequirements: Int
        ) {
            val queued = downloadManager.currentDownloads.filter { it.state == Download.STATE_QUEUED }
            transaction { queued.forEach { write(it, notMetRequirements, null) } }
        }
    }

    private fun write(download: Download, notMet: Int, finalException: Exception?) {
        val current = Database.download(download.request.id) ?: return
        val state = download.state.toState(notMet)
        if (state == DownloadState.Completed && current.state != DownloadState.Completed) Log.i(
            TAG,
            "${current.videoId}: ${download.bytesDownloaded} bytes in ${download.updateTimeMs - download.startTimeMs} ms"
        )
        if (state == DownloadState.Failed) Log.w(TAG, "${current.videoId} failed", finalException)
        val format = if (state == DownloadState.Completed) Database.formatOf(current.videoId) else null

        Database.upsert(
            current.copy(
                state = state,
                waitReason = if (state == DownloadState.Waiting) notMet.toWaitReason() else null,
                failureCode = if (state == DownloadState.Failed) finalException.toFailure() else null,
                bytesDownloaded = download.bytesDownloaded,
                contentLength = download.contentLength.takeIf { it != C.LENGTH_UNSET.toLong() } ?: current.contentLength,
                itag = format?.itag ?: current.itag,
                mimeType = format?.mimeType ?: current.mimeType,
                completedAt = if (state == DownloadState.Completed) current.completedAt ?: System.currentTimeMillis() else null,
                attempts = if (state == DownloadState.Failed) current.attempts + 1 else current.attempts
            )
        )
    }

    /** While something downloads, its progress goes to Room every second. */
    private fun followProgress(downloadManager: DownloadManager) {
        if (progressJob?.isActive == true) return

        progressJob = scope.launch {
            while (isActive) {
                val running = runCatching {
                    withContext(Dispatchers.Main) {
                        downloadManager.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                    }
                }.getOrDefault(emptyList())
                if (running.isEmpty()) break

                running.forEach { download ->
                    runCatching {
                        // Only while it downloads: a late update must not undo "completed"
                        Database.updateDownloadProgress(
                            videoId = download.request.id,
                            bytes = download.bytesDownloaded,
                            contentLength = download.contentLength.takeIf { it != C.LENGTH_UNSET.toLong() }
                        )
                    }
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    /**
     * On start: what Room wants and what the manager has must agree. A download Room doesn't know
     * goes; a row the manager doesn't know is requested again (a completed one is then checked
     * against the cache and completes at once).
     */
    private fun reconcile(downloadManager: DownloadManager) {
        val present = buildMap {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) cursor.download.let { put(it.request.id, it) }
            }
        }

        query {
            val wanted = Database.allDownloads().associateBy { it.videoId }
            main.post {
                present.keys.filter { it !in wanted }.forEach { downloadManager.removeDownload(it) }
                wanted.keys.filter { it !in present }.forEach { request(it) }
            }
        }
    }
}

private fun Int.toState(notMet: Int) = when (this) {
    Download.STATE_QUEUED -> if (notMet != 0) DownloadState.Waiting else DownloadState.Queued
    Download.STATE_DOWNLOADING -> DownloadState.Downloading
    Download.STATE_COMPLETED -> DownloadState.Completed
    Download.STATE_FAILED -> DownloadState.Failed
    Download.STATE_STOPPED -> DownloadState.Paused
    else -> DownloadState.Queued
}

private fun Int.toWaitReason() = when {
    this and Requirements.NETWORK_UNMETERED != 0 -> DownloadWaitReason.Wifi
    this and Requirements.NETWORK != 0 -> DownloadWaitReason.Network
    this and Requirements.DEVICE_STORAGE_NOT_LOW != 0 -> DownloadWaitReason.Storage
    else -> null
}

private fun Throwable?.toFailure(): DownloadFailure {
    val causes = generateSequence(this) { it.cause }.toList()
    return when {
        causes.any { it is UnplayableException || it is VideoIdMismatchException } -> DownloadFailure.Unavailable
        causes.any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode in 400..499 } ->
            DownloadFailure.Unavailable
        causes.any { it.message?.contains("ENOSPC") == true } -> DownloadFailure.StorageFull
        causes.any { it is IOException } -> DownloadFailure.Network
        else -> DownloadFailure.Unknown
    }
}
