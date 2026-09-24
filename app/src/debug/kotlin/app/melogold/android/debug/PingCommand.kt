package app.melogold.android.debug

import android.content.Context
import android.os.Bundle
import app.melogold.android.BuildConfig

/** `--es cmd Ping [--es arg <text>]`: checks that debug commands reach the app */
object PingCommand : DebugCommand {
    override suspend fun run(context: Context, arg: String?, extras: Bundle) =
        listOfNotNull("pong", context.packageName, BuildConfig.VERSION_NAME, arg).joinToString(" ")
}
