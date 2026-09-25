@file:OptIn(UnstableApi::class)

package app.melogold.android.data.downloads

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.melogold.android.data.repo.trackLinks
import app.melogold.android.service.PlayerService
import app.melogold.android.utils.centerSquare
import app.melogold.android.utils.squareThumbnail
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "FileExport"
private const val FOLDER = "Melogold"
private const val COVER_SIZE = 1200
private const val COVER_QUALITY = 90
private const val AAC_BITRATE = 256_000
private const val MAX_NAME_LENGTH = 120

/** A track saved as a file: where it is and what it is called. */
data class SavedFile(val uri: Uri, val name: String)

/**
 * "Save as file" (the user's choice next to downloads): the track as an .m4a with its title,
 * artist, album and cover, in Music/Melogold, where other players and file managers see it and
 * where it stays after the app is gone.
 *
 * Media3's Transformer reads the track the way the player does — the downloads first, then the
 * network — and re-encodes only what isn't AAC already (Opus from YouTube becomes AAC 256 kbit/s).
 * One file at a time.
 */
class FileExport(private val context: Context, private val downloads: Downloads) {
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    suspend fun save(mediaItem: MediaItem): Result<SavedFile> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val videoId = mediaItem.mediaId
            val metadata = mediaItem.mediaMetadata
            val temp = context.cacheDir.resolve("export").apply { mkdirs() }.resolve("$videoId.m4a")

            runCatching {
                temp.delete()
                transform(videoId, temp)
                Mp4Tags.write(
                    temp,
                    Mp4TagValues(
                        title = metadata.title?.toString(),
                        artist = metadata.artist?.toString(),
                        // The item may not know its album; the watch page does
                        album = metadata.albumTitle?.toString() ?: mediaItem.trackLinks().album?.name,
                        cover = cover(metadata)
                    )
                )
                val name = fileName(metadata, videoId)
                SavedFile(uri = publish(temp, name, metadata), name = name)
            }.also {
                temp.delete()
                it.exceptionOrNull()?.let { error -> Log.e(TAG, "Could not save $videoId", error) }
            }
        }
    }

    private suspend fun transform(videoId: String, output: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            // The downloads first (read-only), then the network in full-speed chunks
            val dataSource = CacheDataSource.Factory()
                .setCache(downloads.cache)
                .setCacheWriteDataSinkFactory(null)
                .setUpstreamDataSourceFactory(
                    PlayerService.createYouTubeDataSourceResolverFactory(context = context, cache = null, chunkLength = null)
                )

            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        context,
                        DefaultDecoderFactory.Builder(context).build(),
                        Clock.DEFAULT,
                        DefaultMediaSourceFactory(dataSource),
                        DataSourceBitmapLoader(context)
                    )
                )
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(AAC_BITRATE).build())
                        .build()
                )
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    }
                )
                .build()

            val item = MediaItem.Builder()
                .setUri("https://music.youtube.com/watch?v=$videoId")
                .setCustomCacheKey(videoId)
                .build()
            transformer.start(EditedMediaItem.Builder(item).setRemoveVideo(true).build(), output.absolutePath)
            continuation.invokeOnCancellation { main.post { transformer.cancel() } }
        }
    }

    /** The artwork as a square JPEG: video frames are cropped to their middle. */
    private suspend fun cover(metadata: MediaMetadata): ByteArray? = runCatching {
        val url = metadata.artworkUri?.toString()?.squareThumbnail(COVER_SIZE) ?: return@runCatching null
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context).data(url).allowHardware(false).build()
        ) as? SuccessResult ?: return@runCatching null

        val square = result.image.toBitmap().centerSquare()
        ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it) }.toByteArray()
    }.getOrNull()

    /** "Artist - Title.m4a", without the characters file systems refuse. */
    private fun fileName(metadata: MediaMetadata, videoId: String): String {
        val base = listOfNotNull(metadata.artist?.toString(), metadata.title?.toString())
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { videoId }
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(MAX_NAME_LENGTH)
        return "$base.m4a"
    }

    /** Puts [file] into Music/Melogold: through MediaStore, or (Android 9 and older) straight into the folder. */
    private fun publish(file: File, name: String, metadata: MediaMetadata): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, MimeTypes.AUDIO_MP4)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                metadata.title?.let { put(MediaStore.Audio.Media.TITLE, it.toString()) }
                metadata.artist?.let { put(MediaStore.Audio.Media.ARTIST, it.toString()) }
                metadata.albumTitle?.let { put(MediaStore.Audio.Media.ALBUM, it.toString()) }
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("MediaStore refused $name")
            runCatching {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("No stream for $uri")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }.onFailure {
                resolver.delete(uri, null, null)
                throw it
            }
            return uri
        }

        @Suppress("DEPRECATION")
        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).resolve(FOLDER).apply { mkdirs() }
        val target = generateSequence(0) { it + 1 }
            .map { index -> folder.resolve(if (index == 0) name else name.replace(".m4a", " ($index).m4a")) }
            .first { !it.exists() }
        file.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(MimeTypes.AUDIO_MP4), null)
        return target.toUri()
    }
}
