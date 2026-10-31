package app.melogold.android.utils

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import app.melogold.android.BuildConfig
import app.melogold.android.preferences.DataPreferences
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

private const val TAG = "LogFiles"

/**
 * The app's log in files, like the desktop clients (tasks/0035, 0036): every run writes `logs/melogold-<start>.log`,
 * a new file past 2 MB or with a new date; a crash also goes to `last-crash.txt`. The screen «Logs» reads only the
 * process that runs now: a song that broke off and restarted the app took its log with it (One UI 9, the third minute).
 *
 * «Log» off — nothing is written and the folder is emptied at once; «Keep» — a day, 3 days, a week or a month, and the
 * whole folder at most 50 MB. [export] makes one file to send: the phone and how it treats the app, then the files.
 */
object LogFiles {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    private const val CRASH_FILE = "last-crash.txt"
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** «Keep» in days: a day, 3 days, a week, a month. */
    val retentionDays = persistentListOf(1, 3, 7, 30)

    private lateinit var directory: File
    private var logcat: java.lang.Process? = null
    private var writing: File? = null

    /** Turned on again in this run: the lines from before «off» stay in the system's buffer and must not come back. */
    private var since: Long? = null

    /** Is the log written — «Log» in the settings; off, the player does not log its state either. */
    val enabled get() = DataPreferences.logEnabled

    private val keepMillis get() = DataPreferences.logRetentionDays.coerceIn(1, 365) * DAY_MILLIS

    /** On start, once per process: the old files go, and this run's log starts (or the folder empties, when off). */
    fun start(context: Context) {
        directory = File(context.filesDir, "logs").apply { mkdirs() }
        val crashes = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (enabled) runCatching {
                File(directory, CRASH_FILE).writeText(
                    "Melogold ${BuildConfig.VERSION_NAME}\n${ZonedDateTime.now()}\n\n${throwable.stackTraceToString()}"
                )
            }
            crashes?.uncaughtException(thread, throwable)
        }
        apply()
    }

    /** «Log»: off — the file closes and the folder empties at once; on — a new file at once. */
    fun setEnabled(value: Boolean) {
        if (!value) Log.i(TAG, "The log is off")
        DataPreferences.logEnabled = value
        apply()
        if (value) Log.i(TAG, "The log is on: Melogold ${BuildConfig.VERSION_NAME}")
    }

    /** «Keep»: the new term works at once — what is older goes. */
    fun setRetention(days: Int) {
        DataPreferences.logRetentionDays = days
        synchronized(this) { cleanup(directory, System.currentTimeMillis(), keepMillis, writing) }
    }

    @Synchronized
    private fun apply() {
        if (!enabled) {
            logcat?.destroy()
            logcat = null
            writing = null
            since = System.currentTimeMillis()
            files(directory).forEach { it.delete() }
            return
        }
        cleanup(directory, System.currentTimeMillis(), keepMillis, writing)
        if (logcat != null) return
        val command = listOf("logcat", "-v", "time,year", "--pid=${Process.myPid()}") +
            since?.let { listOf("-T", "%d.%03d".format(it / 1000, it % 1000)) }.orEmpty()
        val process = runCatching { Runtime.getRuntime().exec(command.toTypedArray()) }.getOrElse {
            Log.w(TAG, "The log is not written to files", it)
            return
        }
        logcat = process
        thread(name = "log-files", isDaemon = true) { pump(process) }
    }

    /** Writes the lines of [process] one by one, so the lines up to a kill are on disk. */
    private fun pump(process: java.lang.Process) {
        var writer: Writer? = null
        var written = 0L
        var day: LocalDate? = null
        runCatching {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    synchronized(this) {
                        // Turned off (or on again with a new process): this one writes nothing more
                        if (logcat !== process) return@runCatching
                        val today = LocalDate.now()
                        if (writer == null || written > MAX_BYTES || today != day) {
                            writer?.close()
                            val file = File(directory, "melogold-${ZonedDateTime.now().format(stamp)}.log")
                            writer = file.outputStream().bufferedWriter()
                            written = 0
                            day = today
                            writing = file
                            cleanup(directory, System.currentTimeMillis(), keepMillis, file)
                        }
                        writer?.run {
                            write(line)
                            write("\n")
                            // Line by line: the last moments before a kill are the ones that matter
                            flush()
                        }
                        written += line.length + 1
                    }
                }
            }
        }.onFailure { Log.w(TAG, "The log stopped", it) }
        runCatching { writer?.close() }
    }

    /** The log's files — `*.log` and the crash — from old to new by the time they were changed. */
    private fun files(directory: File) = directory.listFiles { file ->
        file.isFile && (file.extension == "log" || file.name == CRASH_FILE)
    }.orEmpty().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })

    /**
     * Older than the term — deleted; then, while the folder is over 50 MB, the oldest. The file being written stays;
     * `current.log`, `previous.log` and `older.log` of 0.1.22 are `*.log` too and go by the term.
     */
    internal fun cleanup(directory: File, now: Long, keepMillis: Long, writing: File?) {
        val kept = files(directory).filter { file ->
            val old = now - file.lastModified() > keepMillis
            !(old && file != writing && file.delete())
        }
        var total = kept.sumOf { it.length() }
        for (file in kept) {
            if (total <= MAX_TOTAL_BYTES) break
            val size = file.length()
            if (file != writing && file.delete()) total -= size
        }
    }

    /** One file to send: the phone and the app's standing with it, the crash, then the files from old to new. */
    fun export(context: Context): File {
        val share = File(context.cacheDir, "share").apply { mkdirs() }
        val now = ZonedDateTime.now()
        val file = File(share, "melogold-log-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt")
        val all = files(File(context.filesDir, "logs"))
        file.bufferedWriter().use { out ->
            out.write(summary(context, now))
            for (log in all.filter { it.name == CRASH_FILE } + all.filter { it.name != CRASH_FILE }) {
                val title = if (log.name == CRASH_FILE) "Падение (${log.name})" else log.name
                out.write("\n===== $title =====\n")
                log.bufferedReader().use { it.copyTo(out) }
            }
        }
        return file
    }

    /** Sends [export] with the system's share sheet, as a file (not text cut into messages). */
    suspend fun share(context: Context) {
        val file = withContext(Dispatchers.IO) { export(context) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** What the phone is and how it treats the app: the usual reasons music stops in the background. */
    private fun summary(context: Context, now: ZonedDateTime): String = buildString {
        val power = context.getSystemService<PowerManager>()
        val activity = context.getSystemService<ActivityManager>()
        val usage = context.getSystemService<UsageStatsManager>()
        val connectivity = context.getSystemService<ConnectivityManager>()
        appendLine("Melogold ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Время: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine("Журнал: хранить ${DataPreferences.logRetentionDays} дн.")
        appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), сборка ${Build.DISPLAY}")
        systemProperty("ro.build.version.oneui")?.let { appendLine("One UI: $it") }
        appendLine("Батарея без ограничений: ${power?.isIgnoringBatteryOptimizations(context.packageName)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appendLine("Фоновая работа запрещена: ${activity?.isBackgroundRestricted}")
            appendLine("Группа ожидания: ${usage?.appStandbyBucket?.let(::bucketName)}")
        }
        appendLine("Экономия трафика: ${connectivity?.restrictBackgroundStatus?.let(::dataSaverName)}")
        appendLine("Уведомления разрешены: ${NotificationManagerCompat.from(context).areNotificationsEnabled()}")
    }

    private fun bucketName(bucket: Int) = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "активное"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "рабочее"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "частое"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "редкое"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "ограниченное (спящее)"
        else -> bucket.toString()
    }

    private fun dataSaverName(status: Int) = when (status) {
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "выключена"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "включена, Melogold в исключениях"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "включена"
        else -> status.toString()
    }

    private fun systemProperty(name: String): String? = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
