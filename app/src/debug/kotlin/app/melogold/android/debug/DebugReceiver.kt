package app.melogold.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Runs a [DebugCommand] from the adb shell. Only debug builds declare it, and only a sender holding
 * `android.permission.DUMP` (the adb shell) can reach it (`app/src/debug/AndroidManifest.xml`).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_CMD)
        val command = name?.let(::findCommand)

        if (command == null) {
            val message = if (name == null) "missing --es $EXTRA_CMD <Name>" else "unknown command: $name"
            Log.w(TAG, message)
            resultCode = RESULT_UNKNOWN
            resultData = message
            return
        }

        val arg = intent.getStringExtra(EXTRA_ARG)
        val extras = intent.extras ?: Bundle.EMPTY
        val appContext = context.applicationContext
        val pending = goAsync()

        scope.launch {
            Log.i(TAG, "$name ${arg.orEmpty()}")
            runCatching { withTimeout(TIMEOUT_MS) { command.run(appContext, arg, extras) } }
                .onSuccess { result ->
                    Log.i(TAG, "$name: $result")
                    pending.setResult(RESULT_OK, result, null)
                }
                .onFailure { error ->
                    Log.e(TAG, "$name failed", error)
                    pending.setResult(RESULT_FAILED, "${error::class.simpleName}: ${error.message}", null)
                }
            pending.finish()
        }
    }

    companion object {
        const val ACTION = "app.melogold.android.debug.CMD"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ARG = "arg"

        const val RESULT_OK = 0
        const val RESULT_FAILED = 1
        const val RESULT_UNKNOWN = 2

        /** A background broadcast gets 60 s before the system reports it; leave a margin */
        const val TIMEOUT_MS = 50_000L

        private const val TAG = "MelogoldDebug"
        private const val PACKAGE = "app.melogold.android.debug"

        // A plain class name only: nothing outside this package can be loaded
        private val commandName = Regex("[A-Z][A-Za-z0-9]*")

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** `Seed` → `app.melogold.android.debug.SeedCommand`, or null if there is no such command */
        internal fun findCommand(name: String): DebugCommand? {
            if (!commandName.matches(name)) return null

            val type = runCatching { Class.forName("$PACKAGE.${name}Command") }.getOrNull()
                ?.takeIf { DebugCommand::class.java.isAssignableFrom(it) && !it.isInterface }
                ?: return null

            // A Kotlin object, otherwise a class with a constructor without parameters
            val instance = runCatching { type.getField("INSTANCE").get(null) }.getOrNull()
                ?: runCatching { type.getDeclaredConstructor().newInstance() }.getOrNull()

            return instance as? DebugCommand
        }
    }
}
