package app.melogold.android.data.importer

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.Database
import app.melogold.android.MainApplication
import app.melogold.android.internal
import app.melogold.android.models.Event
import app.melogold.android.models.Lyrics
import app.melogold.android.models.LyricsSource
import app.melogold.android.models.Playlist
import app.melogold.android.models.Song
import app.melogold.android.models.SongPlaylistMap
import app.melogold.domain.importer.ImportIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A ViTune backup (schema v30, as the ViTune of 2026 writes it) goes into the library (REWRITE §4.5): new tracks
 * and plays come, local files and absurd dates stay out, lyrics fill only what is missing, a playlist joins one of
 * the same name, and importing the same file again adds nothing.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyImporterTest {
    private val app = ApplicationProvider.getApplicationContext<MainApplication>()
    private val importer = LegacyImporter(app, CoroutineScope(Dispatchers.Unconfined), afterImport = { })

    /** Room refuses the main thread, where Robolectric runs the tests. */
    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    /** The database outlives a test here: each one starts from an empty library. */
    @Before
    fun emptyLibrary() = io { Database.internal.clearAllTables() }

    @Test
    fun `a ViTune v30 backup merges into the library once`() = io {
        val backup = vitune30Backup()
        val first = importer.import(Uri.fromFile(backup))

        assertEquals(30, first.version)
        assertEquals(2, first.tracks, "the local file is not a track")
        assertEquals(1, first.localSkipped)
        assertEquals(3, first.plays, "4 plays of tracks, one from 1970 left out")
        assertEquals(1, first.datesSkipped)
        assertEquals(1, first.favorites)
        assertEquals(1, first.lyrics)
        assertEquals(1, first.playlists)
        assertEquals(1, first.saved)

        val rick = assertNotNull(Database.songNow(RICK))
        assertEquals(LIKED_AT, rick.likedAt)
        assertEquals(600_000L, rick.totalPlayTimeMs)
        val events = Database.eventsOf(RICK).sortedBy { it.timestamp }
        assertEquals(listOf(PLAYED_AT, PLAYED_AT + 1_000_000), events.map { it.timestamp })
        assertEquals(ImportIds.eventId(RICK, PLAYED_AT, 215_000), events.first().syncId)
        assertTrue(events.all { it.deviceId == null && !it.sent }, "own plays, to be sent")
        assertEquals("[00:01.00]Never gonna give you up", Database.lyricsNow(RICK)?.synced)
        assertEquals(LIKED_AT, Database.albumNow("MPREb_album1")?.bookmarkedAt)
        val playlist = Database.playlistsNow().single { it.name == "Дорога" }
        assertEquals(listOf(OTHER, RICK), Database.playlistMapsNow(playlist.id).sortedBy { it.position }.map { it.songId })

        val again = importer.import(Uri.fromFile(vitune30Backup()))
        assertEquals(0, again.tracks)
        assertEquals(0, again.plays)
        assertEquals(3, again.playsKnown)
        assertEquals(0, again.favorites)
        assertEquals(0, again.playlists, "the tracks are in the playlist already")
        assertEquals(2, Database.eventsOf(RICK).size)
    }

    /**
     * "Save a copy" and "Import" on another device (docs/spec/backup-format.md): own lyrics stay own, the playlist
     * finds itself by its server id even when renamed there, plays keep their ids.
     */
    @Test
    fun `a copy of Melogold goes round`() = io {
        Database.insert(Song(id = RICK, title = "Never Gonna Give You Up", durationText = "3:33", thumbnailUrl = null, likedAt = LIKED_AT))
        Database.upsert(Lyrics(songId = RICK, fixed = "Мои слова", synced = null, fixedSource = LyricsSource.User))
        val here = Database.insert(Playlist(name = "Дорога", syncId = PLAYLIST_SYNC_ID))
        Database.insertSongPlaylistMaps(listOf(SongPlaylistMap(songId = RICK, playlistId = here, position = 0)))
        Database.insertEvents(listOf(Event(songId = RICK, timestamp = PLAYED_AT, playTime = 215_000, syncId = EVENT_SYNC_ID)))

        val copy = File.createTempFile("Melogold_backup", ".db")
        copy.outputStream().use { LibraryBackup.export(app, it) }
        val mark = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT key, value FROM MelogoldBackup", null).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
        }
        assertEquals("1", mark["format"])
        assertEquals("android", mark["platform"])

        // Another device: the same playlist renamed there, nothing else yet
        Database.internal.clearAllTables()
        val there = Database.insert(Playlist(name = "Road trip", syncId = PLAYLIST_SYNC_ID))
        val summary = importer.import(Uri.fromFile(copy))

        assertEquals(1, summary.tracks)
        assertEquals(1, summary.plays)
        assertEquals(LyricsSource.User, Database.lyricsNow(RICK)?.fixedSource, "own lyrics stay own")
        assertEquals(listOf(there), Database.playlistsNow().map { it.id }, "no second playlist")
        assertEquals(listOf(RICK), Database.playlistMapsNow(there).map { it.songId })
        assertEquals(EVENT_SYNC_ID, Database.eventsOf(RICK).single().syncId)
    }

    @Test
    fun `not a backup`() = io {
        val file = File.createTempFile("not", ".db").apply { writeText("hello") }
        val error = runCatching { importer.import(Uri.fromFile(file)) }.exceptionOrNull()
        assertEquals(ImportFailure.NotABackup.name, error?.message)
    }

    /**
     * A real backup given by `-Dmelogold.importSample=/path/to.db` (never committed: it is someone's history):
     * every play of a track comes in.
     */
    @Test
    fun `a real backup when given`() = io {
        val path = System.getProperty("melogold.importSample")?.takeIf { it.isNotBlank() } ?: return@io
        val sample = File(path)
        val expected = SQLiteDatabase.openDatabase(sample.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM Event WHERE songId NOT LIKE 'local:%' AND timestamp BETWEEN 946684800000 AND 4102444799999",
                null
            ).use { it.moveToFirst(); it.getInt(0) }
        }
        val summary = importer.import(Uri.fromFile(sample))
        println("IMPORT SAMPLE: $summary, expected plays $expected")
        assertEquals(expected, summary.plays + summary.playsKnown)
    }

    private fun vitune30Backup(): File {
        val file = File.createTempFile("vitune", ".db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE Song (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artistsText TEXT, durationText TEXT, " +
                    "thumbnailUrl TEXT, likedAt INTEGER, totalPlayTimeMs INTEGER NOT NULL, loudnessBoost REAL, " +
                    "blacklisted INTEGER NOT NULL DEFAULT 0, explicit INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("CREATE TABLE Event (id INTEGER PRIMARY KEY AUTOINCREMENT, songId TEXT NOT NULL, timestamp INTEGER NOT NULL, playTime INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE Lyrics (songId TEXT NOT NULL PRIMARY KEY, fixed TEXT, synced TEXT, startTime INTEGER)")
            db.execSQL(
                "CREATE TABLE Album (id TEXT NOT NULL PRIMARY KEY, title TEXT, thumbnailUrl TEXT, year TEXT, authorsText TEXT, " +
                    "shareUrl TEXT, timestamp INTEGER, bookmarkedAt INTEGER, description TEXT, otherInfo TEXT)"
            )
            db.execSQL("CREATE TABLE Artist (id TEXT NOT NULL PRIMARY KEY, name TEXT, thumbnailUrl TEXT, timestamp INTEGER, bookmarkedAt INTEGER)")
            db.execSQL("CREATE TABLE SongAlbumMap (songId TEXT NOT NULL, albumId TEXT NOT NULL, position INTEGER, PRIMARY KEY (songId, albumId))")
            db.execSQL("CREATE TABLE SongArtistMap (songId TEXT NOT NULL, artistId TEXT NOT NULL, PRIMARY KEY (songId, artistId))")
            db.execSQL("CREATE TABLE Playlist (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, browseId TEXT, thumbnail TEXT)")
            db.execSQL("CREATE TABLE SongPlaylistMap (songId TEXT NOT NULL, playlistId INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY (songId, playlistId))")
            db.execSQL("CREATE TABLE SearchQuery (id INTEGER PRIMARY KEY AUTOINCREMENT, query TEXT NOT NULL)")

            db.execSQL("INSERT INTO Song VALUES ('$RICK', 'Never Gonna Give You Up', 'Rick Astley', '3:33', 'https://i.ytimg.com/vi/$RICK/hq.jpg', $LIKED_AT, 600000, NULL, 0, 0)")
            db.execSQL("INSERT INTO Song VALUES ('$OTHER', '', NULL, NULL, NULL, NULL, 0, NULL, 0, 1)")
            db.execSQL("INSERT INTO Song VALUES ('local:42', 'My file.mp3', NULL, NULL, NULL, NULL, 90000, NULL, 0, 0)")
            db.execSQL("INSERT INTO Event (songId, timestamp, playTime) VALUES ('$RICK', $PLAYED_AT, 215000)")
            db.execSQL("INSERT INTO Event (songId, timestamp, playTime) VALUES ('$RICK', ${PLAYED_AT + 1_000_000}, 385000)")
            db.execSQL("INSERT INTO Event (songId, timestamp, playTime) VALUES ('$OTHER', ${PLAYED_AT + 2_000_000}, 0)")
            db.execSQL("INSERT INTO Event (songId, timestamp, playTime) VALUES ('$OTHER', 5, 1000)")
            db.execSQL("INSERT INTO Event (songId, timestamp, playTime) VALUES ('local:42', $PLAYED_AT, 90000)")
            db.execSQL("INSERT INTO Lyrics VALUES ('$RICK', '', '[00:01.00]Never gonna give you up', NULL)")
            db.execSQL("INSERT INTO Album (id, title, bookmarkedAt) VALUES ('MPREb_album1', 'Whenever You Need Somebody', $LIKED_AT)")
            db.execSQL("INSERT INTO SongAlbumMap VALUES ('$RICK', 'MPREb_album1', 1)")
            db.execSQL("INSERT INTO Playlist (name) VALUES ('Дорога')")
            db.execSQL("INSERT INTO SongPlaylistMap VALUES ('$OTHER', 1, 0)")
            db.execSQL("INSERT INTO SongPlaylistMap VALUES ('$RICK', 1, 1)")
            db.execSQL("INSERT INTO SongPlaylistMap VALUES ('local:42', 1, 2)")
            db.execSQL("INSERT INTO SearchQuery (query) VALUES ('rick astley')")
            db.version = 30
        }
        return file
    }

    private companion object {
        const val RICK = "dQw4w9WgXcQ"
        const val OTHER = "a1B2c3D4e5F"
        const val LIKED_AT = 1_726_000_000_000L
        const val PLAYED_AT = 1_726_000_100_000L
        const val PLAYLIST_SYNC_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val EVENT_SYNC_ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
