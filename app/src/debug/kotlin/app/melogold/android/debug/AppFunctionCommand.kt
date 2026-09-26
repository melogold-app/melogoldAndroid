package app.melogold.android.debug

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.app.appfunctions.AppFunctionManager as PlatformAppFunctionManager

/**
 * The functions Gemini calls, through the system as an agent calls them (tasks/0006-gemini-app-functions.md).
 * `cmd app_function` of the adb shell exists from Android 17 on; an app may call its own functions, so on
 * Android 16 this command does what an agent would:
 *
 * ```
 * # What the system indexed
 * adb shell am broadcast -a app.melogold.android.debug.CMD --es cmd AppFunction --es arg list \
 *   -n app.melogold.android.debug/app.melogold.android.debug.DebugReceiver
 * # A function by its name, its parameters as extras (strings)
 * adb shell am broadcast -a app.melogold.android.debug.CMD --es cmd AppFunction --es arg playSong \
 *   --es query 'Звезда по имени Солнце' \
 *   -n app.melogold.android.debug/app.melogold.android.debug.DebugReceiver
 * ```
 *
 * The system binds the service with BIND_APP_FUNCTION_SERVICE and runs the generated dispatch, so this checks
 * the manifest, the index and the functions together. The answer is the result document of the system.
 */
object AppFunctionCommand : DebugCommand {
    /** The parameters of the functions; every one is a string. */
    private val PARAMETERS = listOf("query", "videoId", "name")

    private const val BASE = "app.melogold.android.appfunctions.BaseMelogoldAppFunctionService"

    /** The functions the app declares (the generated MelogoldAppFunctionService.FUNCTION_ID_*). */
    val FUNCTIONS = listOf(
        "playSong", "searchSongs", "playArtist", "playAlbum", "playPlaylist",
        "playFavorites", "shuffleFavorites", "pause", "resume", "next"
    )

    override suspend fun run(context: Context, arg: String?, extras: Bundle): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return "AppFunctions need Android 16"
        val function = requireNotNull(arg) { "--es arg list|${FUNCTIONS.joinToString("|")}" }
        return if (function == "list") list(context) else execute(context, function, extras)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun list(context: Context): String {
        val platform = requireNotNull(context.getSystemService(PlatformAppFunctionManager::class.java)) {
            "no AppFunctionManager"
        }
        // The platform index: is each function known, and enabled
        val states = buildList {
            for (name in FUNCTIONS) {
                val state = runCatching { platform.isEnabled("$BASE#$name") }
                    .fold({ if (it) "enabled" else "disabled" }, { "${it::class.simpleName}: ${it.message}" })
                add("$name: $state")
            }
        }.joinToString("\n")
        // What an agent sees through AppSearch: the full metadata with the descriptions (the v2 index)
        val indexed = runCatching {
            AppFunctionManager.getInstance(context)
                ?.searchAppFunctions(AppFunctionSearchSpec(packageNames = setOf(context.packageName)))
                ?.map { "${it.id.substringAfter('#')} (${it.parameters.size} parameters, " +
                    "${it.description.length} chars of description)" }
                ?.sorted()
        }.fold({ it?.joinToString("\n", prefix = "searchAppFunctions:\n") ?: "no androidx AppFunctionManager" }) {
            "searchAppFunctions: ${it::class.simpleName}: ${it.message}"
        }
        return "$states\n$indexed"
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun execute(context: Context, function: String, extras: Bundle): String {
        require(function in FUNCTIONS) { "unknown function $function" }
        // Only the parameters given: an agent leaves out the optional ones
        val given = PARAMETERS.mapNotNull { name -> extras.getString(name)?.let { name to it } }
        val spec = given.map { (name, _) ->
            AppFunctionParameterMetadata(name, false, AppFunctionStringTypeMetadata(true))
        }
        val parameters = AppFunctionData.Builder(spec, AppFunctionComponentsMetadata()).apply {
            given.forEach { (name, value) -> setString(name, value) }
        }.build()
        val request = ExecuteAppFunctionRequest(context.packageName, "$BASE#$function", parameters)
            .toPlatformExecuteAppFunctionRequest()
        val platform = requireNotNull(context.getSystemService(PlatformAppFunctionManager::class.java)) {
            "no AppFunctionManager"
        }

        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            platform.executeAppFunction(
                request,
                context.mainExecutor,
                signal,
                object : OutcomeReceiver<
                    android.app.appfunctions.ExecuteAppFunctionResponse,
                    android.app.appfunctions.AppFunctionException
                    > {
                    override fun onResult(result: android.app.appfunctions.ExecuteAppFunctionResponse) =
                        continuation.resume("$function: ${result.resultDocument}")

                    override fun onError(error: android.app.appfunctions.AppFunctionException) =
                        continuation.resume("$function failed: code ${error.errorCode}: ${error.errorMessage}")
                }
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun PlatformAppFunctionManager.isEnabled(id: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            isAppFunctionEnabled(
                id,
                { it.run() },
                object : OutcomeReceiver<Boolean, Exception> {
                    override fun onResult(result: Boolean) = continuation.resume(result)
                    override fun onError(error: Exception) = continuation.resumeWithException(error)
                }
            )
        }
}
