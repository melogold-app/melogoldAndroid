package app.melogold.android.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.melogold.android.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "AppUpdater"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val PREFS = "melogold_updates"
private const val KEY_LAST_CHECK = "lastCheck"
private const val KEY_RELEASE = "release"

/**
 * What `update.json` of a GitHub release says (REWRITE §4.14): the APK of a version, its size and
 * SHA-256, and "What's new" per language. `scripts/release.sh` writes it next to the APK.
 */
@Serializable
data class UpdateRelease(
    val version: String,
    val versionCode: Long,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val notes: Map<String, String> = emptyMap(),
    val publishedAt: String? = null
) {
    /** The APK of this release: it sits next to `update.json` (`…/releases/download/v<version>/`). */
    fun downloadUrl(manifestUrl: String) = manifestUrl.substringBefore("/releases/") + "/releases/download/v$version/$fileName"

    /** "What's new" in the language of [locale], else in English. */
    fun notesFor(locale: Locale): String? = (notes[locale.language] ?: notes["en"] ?: notes.values.firstOrNull())
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

/** Where the update of the app stands. */
sealed interface UpdateState {
    /** This build doesn't update itself (debug, staging, nightly): only releases from GitHub do. */
    data object Disabled : UpdateState

    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val checkedAt: Long) : UpdateState

    data class Available(val release: UpdateRelease) : UpdateState

    /** [progress] in 0..1. */
    data class Downloading(val release: UpdateRelease, val progress: Float) : UpdateState

    /** The APK is on the device and checked: it installs next. */
    data class Ready(val release: UpdateRelease) : UpdateState

    data class Installing(val release: UpdateRelease) : UpdateState

    data class Failed(val release: UpdateRelease?, val reason: Reason) : UpdateState

    enum class Reason { Offline, Download, Install }

    /** The release this state is about, if any. */
    val pending: UpdateRelease?
        get() = when (this) {
            is Available -> release
            is Downloading -> release
            is Ready -> release
            is Installing -> release
            is Failed -> release
            else -> null
        }
}

/**
 * The self-update of the app from GitHub Releases, like Clementine's (REWRITE §4.14): a check on
 * start and when the app comes back to the front (at most every 6 hours), "New version" in
 * Settings with a badge on the Settings tab, the download with progress, the checks (size, SHA-256,
 * package, version code) and the system installer.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "updates")
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }
    private var job: Job? = null

    val enabled = manifestUrl.isNotEmpty()

    private val mutableState = MutableStateFlow<UpdateState>(if (enabled) UpdateState.Idle else UpdateState.Disabled)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val installedVersionCode: Long by lazy {
        runCatching { PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }
            .getOrDefault(BuildConfig.VERSION_CODE.toLong())
    }

    /** Checks on start and whenever the app comes back to the front, at most every 6 hours. */
    fun start() {
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            cleanup()
            // A release found earlier shows at once, before the next check
            savedRelease()?.takeIf { it.versionCode > installedVersionCode }?.let { release ->
                mutableState.value = if (verifiedFile(release) != null) UpdateState.Ready(release) else UpdateState.Available(release)
            }
            ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.STARTED) }
                .distinctUntilChanged()
                .filter { it }
                .collect { check(force = false) }
        }
    }

    /** Asks GitHub for the latest release; [force] (a tap on "Check for updates") skips the 6-hour pause. */
    fun check(force: Boolean = true) {
        if (!enabled || job?.isActive == true) return
        val current = state.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        if (!force && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return

        job = scope.launch(Dispatchers.IO) {
            // A release already found stays on screen while it is checked again
            if (current.pending == null) mutableState.value = UpdateState.Checking
            try {
                val release = fetchLatest()
                prefs.edit {
                    putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                    if (release != null) putString(KEY_RELEASE, json.encodeToString(UpdateRelease.serializer(), release))
                    else remove(KEY_RELEASE)
                }
                mutableState.value = when {
                    release == null || release.versionCode <= installedVersionCode ->
                        UpdateState.UpToDate(System.currentTimeMillis())

                    verifiedFile(release) != null -> UpdateState.Ready(release)
                    else -> UpdateState.Available(release)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.i(TAG, "Update check failed: ${e.message}")
                // A quiet check keeps what it knew; a tap says it failed
                mutableState.value = when {
                    current.pending != null -> current
                    force -> UpdateState.Failed(null, UpdateState.Reason.Offline)
                    else -> UpdateState.Idle
                }
            }
        }
    }

    /** Downloads the release with progress, checks it and hands it to the installer. */
    fun download() {
        val release = state.value.pending ?: return
        if (job?.isActive == true) return

        job = scope.launch(Dispatchers.IO) {
            try {
                verifiedFile(release) ?: fetchApk(release)
                mutableState.value = UpdateState.Ready(release)
                install()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.i(TAG, "Update download failed: ${e.message}")
                mutableState.value = UpdateState.Failed(release, UpdateState.Reason.Download)
            }
        }
    }

    /** Whether the system lets the app install packages ("Install unknown apps" of this app). */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    /** Settings › "Install unknown apps" of this app. */
    fun installPermissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Installs the downloaded release through a [PackageInstaller] session; the system always asks
     * the user to confirm (REWRITE §4.14.3).
     */
    fun install() {
        val release = (state.value as? UpdateState.Ready)?.release ?: return
        if (!canInstall()) return
        val file = verifiedFile(release) ?: run {
            mutableState.value = UpdateState.Available(release)
            return
        }
        mutableState.value = UpdateState.Installing(release)

        scope.launch(Dispatchers.IO) {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                    val intent = Intent(context, UpdateInstallReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
                }
            }.onFailure {
                Log.w(TAG, "Update install failed", it)
                mutableState.value = UpdateState.Failed(release, UpdateState.Reason.Install)
            }
        }
    }

    /** What the installer answered ([UpdateInstallReceiver]). */
    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?) {
        val release = state.value.pending ?: return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            // Updated: this process ends in a moment
            PackageInstaller.STATUS_SUCCESS -> mutableState.value = UpdateState.UpToDate(System.currentTimeMillis())

            // "Cancel" in the system dialog: the update stays ready
            PackageInstaller.STATUS_FAILURE_ABORTED -> mutableState.value = UpdateState.Ready(release)

            else -> {
                Log.w(TAG, "Update install failed: $status $message")
                mutableState.value = UpdateState.Failed(release, UpdateState.Reason.Install)
            }
        }
    }

    private suspend fun fetchLatest(): UpdateRelease? {
        val response = client.get(manifestUrl) { header("User-Agent", "melogold-android/${BuildConfig.VERSION_NAME}") }
        // No release published yet
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "HTTP ${response.status}" }
        return json.decodeFromString(UpdateRelease.serializer(), response.bodyAsText())
    }

    private suspend fun fetchApk(release: UpdateRelease): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = apkFile(release)
        val partial = File(directory, "${release.fileName}.part")
        partial.delete()
        mutableState.value = UpdateState.Downloading(release, 0f)

        client.prepareGet(release.downloadUrl(manifestUrl)) {
            header("User-Agent", "melogold-android/${BuildConfig.VERSION_NAME}")
        }.execute { response ->
            check(response.status.isSuccess()) { "HTTP ${response.status}" }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            var received = 0L
            var shown = -1
            partial.outputStream().use { output ->
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read <= 0) continue
                    output.write(buffer, 0, read)
                    received += read
                    val percent = (received * 100 / release.sizeBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    if (percent != shown) {
                        shown = percent
                        mutableState.value = UpdateState.Downloading(release, percent / 100f)
                    }
                }
            }
        }

        check(partial.renameTo(target)) { "Could not keep the download" }
        checkNotNull(verifiedFile(release)) { "The download does not match the release" }
    }

    /** The downloaded APK of [release] when it is complete and really that release; a wrong file is deleted. */
    private fun verifiedFile(release: UpdateRelease): File? {
        val file = apkFile(release)
        if (!file.isFile) return null
        val valid = file.length() == release.sizeBytes &&
            file.sha256().equals(release.sha256, ignoreCase = true) &&
            archiveInfo(file)?.let { it.packageName == context.packageName && PackageInfoCompat.getLongVersionCode(it) == release.versionCode } == true
        if (!valid) file.delete()
        return file.takeIf { valid }
    }

    /** Leftovers: unfinished downloads and APKs of versions already installed. */
    private fun cleanup() {
        directory.listFiles()?.forEach { file ->
            val stale = file.name.endsWith(".part") ||
                archiveInfo(file)?.let { PackageInfoCompat.getLongVersionCode(it) <= installedVersionCode } != false
            if (stale) file.delete()
        }
    }

    private fun savedRelease() = prefs.getString(KEY_RELEASE, null)
        ?.let { runCatching { json.decodeFromString(UpdateRelease.serializer(), it) }.getOrNull() }

    private fun apkFile(release: UpdateRelease) = File(directory, release.fileName.substringAfterLast('/'))

    private fun archiveInfo(file: File): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }
    }.getOrNull()
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
