package app.melogold.android.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.melogold.android.service.PlayerService
import app.melogold.android.utils.forcePlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * `--es cmd Play --es arg <videoId>`: plays the video on its own through the normal stream
 * pipeline, without asking YouTube Music for its metadata first. An id that does not exist shows
 * the error card of the player (REWRITE §3.10.9).
 */
object PlayCommand : DebugCommand {
    override suspend fun run(context: Context, arg: String?, extras: Bundle): String {
        val videoId = requireNotNull(arg) { "--es arg <videoId>" }
        val mediaItem = MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(videoId)
            .setCustomCacheKey(videoId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(videoId).build())
            .build()

        withPlayer(context) { binder ->
            withContext(Dispatchers.Main) { binder.player.forcePlay(mediaItem) }
        }
        return "playing $videoId"
    }
}

/** Binds to the player service for [block]; the service outlives the binding while it plays. */
private suspend fun <T> withPlayer(context: Context, block: suspend (PlayerService.Binder) -> T): T {
    lateinit var connection: ServiceConnection

    val binder = suspendCancellableCoroutine { continuation ->
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val player = service as? PlayerService.Binder ?: return
                if (continuation.isActive) continuation.resume(player)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        context.bindService(Intent(context, PlayerService::class.java), connection, Context.BIND_AUTO_CREATE)
        continuation.invokeOnCancellation { context.unbindService(connection) }
    }

    return try {
        block(binder)
    } finally {
        context.unbindService(connection)
    }
}
