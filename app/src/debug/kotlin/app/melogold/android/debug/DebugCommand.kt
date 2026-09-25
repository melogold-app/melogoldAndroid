package app.melogold.android.debug

import android.content.Context
import android.os.Bundle

/**
 * A debug command run from the adb shell through [DebugReceiver] (REWRITE §6.0.3):
 *
 * ```
 * adb shell am broadcast -a app.melogold.android.debug.CMD --es cmd Seed --es arg library \
 *   -n app.melogold.android.debug/app.melogold.android.debug.DebugReceiver
 * ```
 *
 * `--es cmd Seed` runs `app.melogold.android.debug.SeedCommand`: a Kotlin `object` or a class with a
 * constructor without parameters. Every task adds its command as a separate file in this package
 * and leaves [DebugReceiver] alone.
 */
interface DebugCommand {
    /**
     * Runs off the main thread. The returned text goes to logcat (tag `MelogoldDebug`) and to the
     * broadcast result, which `am broadcast` prints as `data="…"`; a thrown exception makes the
     * result code [DebugReceiver.RESULT_FAILED].
     *
     * A command has [DebugReceiver.TIMEOUT_MS] before the system would call the broadcast stuck;
     * longer work runs in its own scope and reports its progress to logcat.
     *
     * @param arg the `--es arg` extra, if any
     * @param extras all the extras of the broadcast, for commands that need more than one argument
     */
    suspend fun run(context: Context, arg: String?, extras: Bundle): String
}
