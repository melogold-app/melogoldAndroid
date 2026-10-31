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

/** The log as one file to send (LogFiles, 31.10.2026): the phone first, then the run before and this one, in order. */
@RunWith(RobolectricTestRunner::class)
class LogFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun exportIsThePhoneThenTheRunBeforeThenThisOne() {
        val logs = File(context.filesDir, "logs").apply { mkdirs() }
        File(logs, "previous.log").writeText("the run before\n")
        File(logs, "current.log").writeText("this run\n")
        File(logs, "older.log").delete()

        val text = LogFiles.export(context).readText()

        assertTrue(text.startsWith("Melogold ${BuildConfig.VERSION_NAME}"), text.take(200))
        assertTrue("Android: " in text)
        assertFalse("older.log" in text, "no file — no part")
        val previous = text.indexOf("===== Прошлый запуск (previous.log) =====\nthe run before")
        val current = text.indexOf("===== Этот запуск (current.log) =====\nthis run")
        assertTrue(previous > 0 && current > previous, text)
    }
}
