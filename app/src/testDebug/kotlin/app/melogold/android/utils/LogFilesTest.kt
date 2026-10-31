package app.melogold.android.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.BuildConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The log in files (LogFiles, tasks/0035, 0036): the term and 50 MB, and one file to send in order. */
@RunWith(RobolectricTestRunner::class)
class LogFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logs = File(context.filesDir, "logs").apply {
        deleteRecursively()
        mkdirs()
    }
    private val now = System.currentTimeMillis()

    private fun aged(name: String, content: String, ageMillis: Long) = File(logs, name).apply {
        writeText(content)
        setLastModified(now - ageMillis)
    }

    @Test
    fun cleanupDropsWhatIsOlderThanTheTermThenTheOldestPast50Mb() {
        val old = aged("melogold-20261001-100000.log", "x", 8 * DAY)
        val crash = aged("last-crash.txt", "x", 8 * DAY)
        val legacy = aged("previous.log", "x", 3 * HOUR)
        val other = aged("notes.json", "x", 100 * DAY)
        LogFiles.cleanup(logs, now, 7 * DAY, writing = null)
        assertFalse(old.exists() || crash.exists(), "older than a week")
        assertTrue(legacy.exists() && other.exists(), "a fresh log and a file that is not the log stay")

        val big = "x".repeat(30 * 1024 * 1024)
        val earlier = aged("melogold-20261030-100000.log", big, 2 * HOUR)
        val writing = aged("melogold-20261030-110000.log", big, HOUR)
        LogFiles.cleanup(logs, now, 7 * DAY, writing)
        assertFalse(legacy.exists() || earlier.exists(), "over 50 MB the oldest go first")
        assertTrue(writing.exists(), "the file being written stays")
    }

    @Test
    fun exportIsThePhoneThenTheCrashThenTheFilesFromOldToNew() {
        aged("melogold-20261031-120000.log", "this run\n", 0)
        aged("melogold-20261030-120000.log", "the run before\n", HOUR)

        var text = LogFiles.export(context).readText()
        assertTrue(text.startsWith("Melogold ${BuildConfig.VERSION_NAME}"), text.take(200))
        assertTrue("Android: " in text)
        assertFalse("Падение" in text, "no crash — no part")
        val before = text.indexOf("===== melogold-20261030-120000.log =====\nthe run before")
        val current = text.indexOf("===== melogold-20261031-120000.log =====\nthis run")
        assertTrue(before > 0 && current > before, text)

        aged("last-crash.txt", "crash", 5 * HOUR)
        text = LogFiles.export(context).readText()
        val crash = text.indexOf("===== Падение (last-crash.txt) =====\ncrash")
        assertTrue(crash > 0 && crash < text.indexOf("===== melogold-20261030"), text)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
