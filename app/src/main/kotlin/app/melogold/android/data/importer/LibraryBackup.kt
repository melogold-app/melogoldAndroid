package app.melogold.android.data.importer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import app.melogold.android.BuildConfig
import app.melogold.android.Database
import app.melogold.android.internal
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * "Save a copy" (`docs/spec/backup-format.md`): the library as one SQLite file that Melogold on Android, Windows and
 * Apple imports. The app's database already has the tables of the format, so the copy is a whole snapshot of it,
 * marked `MelogoldBackup`, in one file without a WAL.
 */
object LibraryBackup {
    /** `Melogold_backup_yyyyMMddHHmmss.db`, as every client names it. */
    fun suggestedName(now: Date = Date()) = "Melogold_backup_${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now)}.db"

    /** Writes the copy into [output], on the calling thread (not the main one). */
    fun export(context: Context, output: OutputStream) {
        val directory = context.cacheDir.resolve("export").apply { mkdirs() }
        val copy = directory.resolve("${UUID.randomUUID()}.db")
        try {
            snapshot(copy)
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS MelogoldBackup (key TEXT NOT NULL PRIMARY KEY, value TEXT)")
                mapOf(
                    "format" to "1",
                    "platform" to "android",
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "createdAt" to Instant.now().toString()
                ).forEach { (key, value) ->
                    db.execSQL("INSERT OR REPLACE INTO MelogoldBackup (key, value) VALUES (?, ?)", arrayOf(key, value))
                }
                db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            }
            copy.inputStream().use { it.copyTo(output) }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * A whole snapshot even while the app writes: `VACUUM INTO` where SQLite has it (API 30+); before, the file right
     * after a checkpoint, copied under the write lock so that no checkpoint changes it meanwhile.
     */
    private fun snapshot(target: File) {
        val database = Database.internal.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            database.execSQL("VACUUM INTO ?", arrayOf<Any?>(target.path))
        } else {
            Database.checkpoint()
            val path = checkNotNull(database.path) { "The database has no file" }
            Database.internal.runInTransaction { File(path).copyTo(target, overwrite = true) }
        }
    }
}
