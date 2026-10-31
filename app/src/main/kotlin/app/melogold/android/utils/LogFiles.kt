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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

private const val TAG = "LogFiles"

/**
 * The app's log in files, like the desktop clients (31.10.2026): `logs/current.log` of this run, the run before in
 * `previous.log`, and past 2 MB the lines so far move to `older.log`. The screen «Logs» reads only the process
 * that runs now: a song that broke off and restarted the app took its log with it (One UI 9, the third minute).
 *
 * A thread reads `logcat` of this process and writes it out line by line, so the lines up to a kill are on disk.
 * [export] makes one file to send: what the phone is and how it treats the app, then the three logs.
 */
object LogFiles {
    private const val MAX_BYTES = 2L * 1024 * 1024

    private fun directory(context: Context) = File(context.filesDir, "logs")

    /** Moves the last run's log aside and starts writing this one; once per process, on start. */
    fun start(context: Context) {
        val directory = directory(context).apply { mkdirs() }
        val current = File(directory, "current.log")
        val previous = File(directory, "previous.log")
        val older = File(directory, "older.log")
        runCatching {
            older.delete()
            if (current.exists()) {
                previous.delete()
                current.renameTo(previous)
            }
        }
        thread(name = "log-files", isDaemon = true) {
            runCatching {
                val logcat = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time,year", "--pid=${Process.myPid()}"))
                var writer = current.outputStream().bufferedWriter()
                var written = 0L
                logcat.inputStream.bufferedReader().forEachLine { line ->
                    if (written > MAX_BYTES) {
                        writer.close()
                        older.delete()
                        current.renameTo(older)
                        writer = current.outputStream().bufferedWriter()
                        written = 0
                    }
                    writer.write(line)
                    writer.newLine()
                    // Line by line: the last moments before a kill are the ones that matter
                    writer.flush()
                    written += line.length + 1
                }
            }.onFailure { Log.w(TAG, "The log is not written to files", it) }
        }
    }

    /** One file to send: the phone and the app's standing with it, the run before, then this one. */
    fun export(context: Context): File {
        val directory = directory(context)
        val share = File(context.cacheDir, "share").apply { mkdirs() }
        val now = ZonedDateTime.now()
        val file = File(share, "melogold-log-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt")
        file.bufferedWriter().use { out ->
            out.write(summary(context, now))
            val parts = listOf(
                "previous.log" to "Прошлый запуск",
                "older.log" to "Этот запуск, раньше",
                "current.log" to "Этот запуск"
            )
            for (part in parts) {
                val log = File(directory, part.first)
                if (!log.exists()) continue
                out.write("\n===== ${part.second} (${part.first}) =====\n")
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
