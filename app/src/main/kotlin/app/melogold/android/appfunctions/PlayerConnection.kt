package app.melogold.android.appfunctions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.melogold.android.service.PlayerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Binds the player service on the first command and keeps it until [close], as PlayerMediaBrowserService does.
 * While it plays, the service is started and outlives the binding.
 */
internal class PlayerConnection(private val context: Context) : ServiceConnection {
    private val connected = CompletableDeferred<PlayerService.Binder>()
    private var bound = false

    /** The player service, bound if it was not; [Unavailable] if it does not come. */
    suspend fun binder(): PlayerService.Binder {
        synchronized(this) {
            if (!bound) {
                bound = context.bindService(Intent(context, PlayerService::class.java), this, Context.BIND_AUTO_CREATE)
                if (!bound) throw Unavailable()
            }
        }
        return withTimeoutOrNull(BIND_TIMEOUT) { connected.await() } ?: throw Unavailable()
    }

    fun close() = synchronized(this) {
        if (bound) context.unbindService(this)
        bound = false
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        (service as? PlayerService.Binder)?.let(connected::complete)
    }

    override fun onServiceDisconnected(name: ComponentName?) = Unit

    class Unavailable : IllegalStateException("Плеер Melogold не запустился / The Melogold player did not start")

    private companion object {
        val BIND_TIMEOUT = 5.seconds
    }
}
