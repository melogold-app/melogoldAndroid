package app.melogold.android.data.importer

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import app.melogold.android.Database
import app.melogold.android.internal
import app.melogold.android.models.Album
import app.melogold.android.models.Artist
import app.melogold.android.models.Event
import app.melogold.android.models.Lyrics
import app.melogold.android.models.Playlist
import app.melogold.android.models.SearchQuery
import app.melogold.android.models.Song
import app.melogold.android.models.SongAlbumMap
import app.melogold.android.models.SongArtistMap
import app.melogold.android.models.SongPlaylistMap
import app.melogold.domain.importer.ImportIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.Normalizer
import java.util.UUID

private const val TAG = "LegacyImporter"
private const val MIN_VERSION = 12
private const val MAX_PLAY_TIME_MS = 86_400_000L
private const val BATCH = 500
private const val SEARCH_QUERIES = 200

/** Dates outside 2000-01-01 … 2100-01-01 are dropped (REWRITE §4.5.3): the server would refuse them. */
private val SANE_DATES = 946_684_800_000L until 4_102_444_800_000L
private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** What an import brought (REWRITE §3.2.8), for its summary. */
data class ImportSummary(
    val version: Int,
    val tracks: Int,
    val plays: Int,
    val playsKnown: Int,
    val favorites: Int,
    val lyrics: Int,
    val playlists: Int,
    val saved: Int,
    val localSkipped: Int,
    val datesSkipped: Int
)

enum class ImportFailure { NotABackup, TooOld, Unsupported, Unreadable }

sealed interface ImportState {
    data object Running : ImportState
    data class Done(val summary: ImportSummary) : ImportState
    data class Failed(val reason: ImportFailure) : ImportState
}

private class ImportException(val reason: ImportFailure, cause: Throwable? = null) : Exception(reason.name, cause)

/**
 * Import of a ViTune or ViMusic backup, or of a Melogold one (REWRITE §4.5): the backup is **added** to the library,
 * nothing here is overwritten or deleted, unlike the old "Restore" that copied the file over the live database.
 *
 * - The backup is copied into `cacheDir/import` and read there by its own connection, table by table, by the columns
 *   it has (ViMusic and ViTune v12–30 and Melogold share the tables).
 * - One Room transaction merges it: a failure in the middle changes nothing.
 * - Tracks: new ones are added; for known ones the earliest like, the longest listening time, a missing cover.
 * - Plays: the same track at the same moment is not added twice; each gets the id of [ImportIds] (or keeps its own
 *   from a Melogold backup), so the same backup imported on two devices does not double the history on the server.
 * - Lyrics fill only what is missing here; playlists join one with the same YouTube link or name, else are new.
 * - Local files are not moved (they are not on this device), and neither are plays or likes with absurd dates.
 *
 * The import runs in the app's scope: leaving the screen does not stop it; [state] tells how it went.
 */
class LegacyImporter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val afterImport: () -> Unit
) {
    private val mutableState = MutableStateFlow<ImportState?>(null)
    val state: StateFlow<ImportState?> = mutableState.asStateFlow()

    fun start(uri: Uri) {
        if (mutableState.value == ImportState.Running) return
        mutableState.value = ImportState.Running
        scope.launch(Dispatchers.IO) {
            mutableState.value = runCatching { import(uri) }.fold(
                onSuccess = { summary ->
                    afterImport()
                    ImportState.Done(summary)
                },
                onFailure = { error ->
                    Log.w(TAG, "Could not import $uri", error)
                    ImportState.Failed((error as? ImportException)?.reason ?: ImportFailure.Unreadable)
                }
            )
        }
    }

    fun dismiss() {
        if (mutableState.value != ImportState.Running) mutableState.value = null
    }

    /** The import itself, on the calling thread (tests call it directly). */
    internal fun import(uri: Uri): ImportSummary {
        val directory = context.cacheDir.resolve("import").apply { mkdirs() }
        val copy = directory.resolve("${UUID.randomUUID()}.db")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw ImportException(ImportFailure.Unreadable)
            if (!copy.isSqlite()) throw ImportException(ImportFailure.NotABackup)

            val legacy = runCatching { SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE) }
                .getOrElse { throw ImportException(ImportFailure.Unreadable, it) }
            return legacy.use { db ->
                // Our copy: out of WAL, so it reads as one file
                runCatching { db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() } }
                val tables = db.tables()
                when {
                    // InnerTune, Metrolist and relatives keep lower-case tables of another shape
                    "Song" !in tables && "song" in tables -> throw ImportException(ImportFailure.Unsupported)
                    "Song" !in tables -> throw ImportException(ImportFailure.NotABackup)
                    db.version in 1 until MIN_VERSION -> throw ImportException(ImportFailure.TooOld)
                }
                LegacyReader(db, tables).read().write(version = db.version)
            }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
        }
    }
}

private fun File.isSqlite(): Boolean = inputStream().use { input ->
    val header = ByteArray(16)
    input.read(header) == 16 && String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
}

private fun SQLiteDatabase.tables(): Set<String> =
    rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

private class LegacyEvent(
    val songId: String,
    val timestamp: Long,
    val playTime: Long,
    val syncId: String?,
    val deviceId: String?
)

private class LegacyPlaylist(val name: String, val browseId: String?, val thumbnail: String?, val songIds: List<String>)

private class LegacyBundle(
    val songs: List<Song>,
    val localSkipped: Int,
    val events: List<LegacyEvent>,
    val lyrics: List<Lyrics>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val songAlbums: List<SongAlbumMap>,
    val songArtists: List<SongArtistMap>,
    val playlists: List<LegacyPlaylist>,
    val searches: List<String>
)

/** Reads the tables of a backup by the columns it has; what is missing reads as null. */
private class LegacyReader(private val db: SQLiteDatabase, private val tables: Set<String>) {
    private val columns = HashMap<String, Set<String>>()

    private fun columnsOf(table: String) = columns.getOrPut(table) {
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
    }

    /** `SELECT` of [wanted] columns of [table], `NULL` for the ones it lacks; nothing without the table. */
    private fun <T> select(table: String, wanted: List<String>, orderBy: String? = null, row: (Row) -> T?): List<T> {
        if (table !in tables) return emptyList()
        val have = columnsOf(table)
        val list = wanted.joinToString { if (it in have) "\"$it\"" else "NULL AS \"$it\"" }
        val order = orderBy?.let { " ORDER BY $it" }.orEmpty()
        return db.rawQuery("SELECT $list FROM \"$table\"$order", null).use { cursor ->
            val reader = Row(cursor, wanted)
            buildList { while (cursor.moveToNext()) row(reader)?.let(::add) }
        }
    }

    fun read(): LegacyBundle {
        var localSkipped = 0
        val songs = select(
            "Song",
            listOf("id", "title", "artistsText", "durationText", "thumbnailUrl", "likedAt", "totalPlayTimeMs", "blacklisted", "explicit")
        ) { row ->
            val id = row.string("id") ?: return@select null
            if (!VIDEO_ID.matches(id)) {
                localSkipped++
                return@select null
            }
            Song(
                id = id,
                title = row.string("title")?.takeIf { it.isNotBlank() } ?: id,
                artistsText = row.string("artistsText"),
                durationText = row.string("durationText"),
                thumbnailUrl = row.string("thumbnailUrl"),
                likedAt = row.long("likedAt")?.takeIf { it > 0 },
                totalPlayTimeMs = row.long("totalPlayTimeMs")?.coerceAtLeast(0) ?: 0,
                blacklisted = row.long("blacklisted") == 1L,
                explicit = row.long("explicit") == 1L
            )
        }

        val events = select("Event", listOf("songId", "timestamp", "playTime", "syncId", "deviceId"), orderBy = "timestamp") { row ->
            LegacyEvent(
                songId = row.string("songId") ?: return@select null,
                timestamp = row.long("timestamp") ?: return@select null,
                playTime = (row.long("playTime") ?: 0).coerceIn(1, MAX_PLAY_TIME_MS),
                syncId = row.string("syncId"),
                deviceId = row.string("deviceId")
            )
        }

        val lyrics = select("Lyrics", listOf("songId", "fixed", "synced", "startTime")) { row ->
            Lyrics(
                songId = row.string("songId") ?: return@select null,
                fixed = row.string("fixed")?.takeIf { it.isNotEmpty() },
                synced = row.string("synced")?.takeIf { it.isNotEmpty() },
                startTime = row.long("startTime")
            ).takeIf { it.fixed != null || it.synced != null }
        }

        val albums = select("Album", listOf("id", "title", "thumbnailUrl", "year", "authorsText", "shareUrl", "timestamp", "bookmarkedAt")) { row ->
            Album(
                id = row.string("id") ?: return@select null,
                title = row.string("title"),
                thumbnailUrl = row.string("thumbnailUrl"),
                year = row.string("year"),
                authorsText = row.string("authorsText"),
                shareUrl = row.string("shareUrl"),
                timestamp = row.long("timestamp"),
                bookmarkedAt = row.long("bookmarkedAt")
            )
        }

        val artists = select("Artist", listOf("id", "name", "thumbnailUrl", "timestamp", "bookmarkedAt")) { row ->
            Artist(
                id = row.string("id") ?: return@select null,
                name = row.string("name"),
                thumbnailUrl = row.string("thumbnailUrl"),
                timestamp = row.long("timestamp"),
                bookmarkedAt = row.long("bookmarkedAt")
            )
        }

        val songAlbums = select("SongAlbumMap", listOf("songId", "albumId", "position")) { row ->
            SongAlbumMap(
                songId = row.string("songId") ?: return@select null,
                albumId = row.string("albumId") ?: return@select null,
                position = row.long("position")?.toInt()
            )
        }

        val songArtists = select("SongArtistMap", listOf("songId", "artistId")) { row ->
            SongArtistMap(
                songId = row.string("songId") ?: return@select null,
                artistId = row.string("artistId") ?: return@select null
            )
        }

        // v11 named the playlist map SongInPlaylist
        val mapTable = if ("SongPlaylistMap" in tables) "SongPlaylistMap" else "SongInPlaylist"
        val items = select(mapTable, listOf("songId", "playlistId", "position"), orderBy = "position, rowid") { row ->
            (row.long("playlistId") ?: return@select null) to (row.string("songId") ?: return@select null)
        }.groupBy({ it.first }, { it.second })
        val playlists = select("Playlist", listOf("id", "name", "browseId", "thumbnail"), orderBy = "rowid") { row ->
            val id = row.long("id") ?: return@select null
            LegacyPlaylist(
                name = row.string("name").orEmpty(),
                browseId = row.string("browseId"),
                thumbnail = row.string("thumbnail"),
                songIds = items[id].orEmpty().distinct()
            )
        }

        val searches = select("SearchQuery", listOf("query"), orderBy = "rowid DESC") { it.string("query") }.take(SEARCH_QUERIES)

        return LegacyBundle(songs, localSkipped, events, lyrics, albums, artists, songAlbums, songArtists, playlists, searches)
    }

    class Row(private val cursor: Cursor, names: List<String>) {
        private val index = names.withIndex().associate { (i, name) -> name to i }

        fun string(name: String): String? = index.getValue(name).let { if (cursor.isNull(it)) null else cursor.getString(it) }

        fun long(name: String): Long? = index.getValue(name).let { if (cursor.isNull(it)) null else cursor.getLong(it) }
    }
}

/** Merges the backup into the library in one transaction (REWRITE §4.5.4). */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun LegacyBundle.write(version: Int): ImportSummary = Database.internal.runInTransaction<ImportSummary> {
    var tracks = 0
    var favorites = 0
    var datesSkipped = 0
    val known = HashSet<String>()

    songs.forEach { imported ->
        val likedAt = imported.likedAt?.takeIf { it in SANE_DATES }
        if (imported.likedAt != null && likedAt == null) datesSkipped++
        val song = imported.copy(likedAt = likedAt)
        val local = Database.songNow(song.id)
        if (local == null) {
            Database.insert(song)
            tracks++
            if (likedAt != null) favorites++
        } else {
            val merged = local.copy(
                // A placeholder named by its id learns the real title
                title = if (local.title == local.id && song.title != song.id) song.title else local.title,
                artistsText = local.artistsText ?: song.artistsText,
                durationText = local.durationText ?: song.durationText,
                thumbnailUrl = local.thumbnailUrl ?: song.thumbnailUrl,
                likedAt = listOfNotNull(local.likedAt, likedAt).minOrNull(),
                totalPlayTimeMs = maxOf(local.totalPlayTimeMs, song.totalPlayTimeMs),
                blacklisted = local.blacklisted || song.blacklisted,
                explicit = local.explicit || song.explicit
            )
            if (local.likedAt == null && merged.likedAt != null) favorites++
            if (merged != local) Database.update(merged)
        }
        known += song.id
    }
    fun isTrack(id: String) = id in known || Database.songNow(id)?.also { known += id } != null

    var saved = 0
    albums.forEach { album ->
        Database.insertIfMissing(album.copy(bookmarkedAt = null))
        val at = album.bookmarkedAt?.takeIf { it in SANE_DATES } ?: return@forEach
        val local = Database.albumNow(album.id)?.bookmarkedAt
        if (local == null) saved++
        Database.setAlbumBookmark(album.id, listOfNotNull(local, at).min())
    }
    artists.forEach { artist ->
        Database.insertIfMissing(artist.copy(bookmarkedAt = null))
        val at = artist.bookmarkedAt?.takeIf { it in SANE_DATES } ?: return@forEach
        val local = Database.artistNow(artist.id)?.bookmarkedAt
        if (local == null) saved++
        Database.setArtistBookmark(artist.id, listOfNotNull(local, at).min())
    }
    val albumIds = albums.mapTo(HashSet()) { it.id }
    val artistIds = artists.mapTo(HashSet()) { it.id }
    songAlbums.filter { isTrack(it.songId) && it.albumId in albumIds }.chunked(BATCH).forEach(Database::insertSongAlbumMaps)
    songArtists.filter { isTrack(it.songId) && it.artistId in artistIds }.chunked(BATCH).forEach(Database::insertSongArtistMaps)

    // Plays: the same track at the same moment only once, whatever its id
    val playedAt = Database.eventKeysNow().toHashSet()
    var playsKnown = 0
    val newEvents = events.mapNotNull { event ->
        if (!isTrack(event.songId)) return@mapNotNull null
        if (event.timestamp !in SANE_DATES) {
            datesSkipped++
            return@mapNotNull null
        }
        if (!playedAt.add("${event.songId}:${event.timestamp}")) {
            playsKnown++
            return@mapNotNull null
        }
        Event(
            songId = event.songId,
            timestamp = event.timestamp,
            playTime = event.playTime,
            syncId = event.syncId ?: ImportIds.eventId(event.songId, event.timestamp, event.playTime),
            deviceId = event.deviceId,
            // Plays of other devices of an account are on its server already
            sent = event.deviceId != null
        )
    }
    newEvents.chunked(BATCH).forEach(Database::insertEvents)

    var lyricsAdded = 0
    lyrics.filter { isTrack(it.songId) }.forEach { imported ->
        val local = Database.lyricsNow(imported.songId)
        when {
            local == null -> {
                Database.upsert(imported)
                lyricsAdded++
            }

            // Only the sides missing here: nothing found ("") or never asked (null)
            local.fixed.isNullOrEmpty() && imported.fixed != null ||
                local.synced.isNullOrEmpty() && imported.synced != null -> {
                Database.upsert(
                    local.copy(
                        fixed = local.fixed?.takeIf { it.isNotEmpty() } ?: imported.fixed,
                        synced = local.synced?.takeIf { it.isNotEmpty() } ?: imported.synced,
                        startTime = if (local.synced.isNullOrEmpty()) imported.startTime else local.startTime
                    )
                )
                lyricsAdded++
            }
        }
    }

    // Playlists: one with the same YouTube link or name takes the missing tracks at its end, else a new one
    var playlistsTouched = 0
    val taken = HashSet<Long>()
    val locals = Database.playlistsNow()
    playlists.forEach { imported ->
        val tracksOf = imported.songIds.filter(::isTrack)
        val match = locals.firstOrNull { it.id !in taken && imported.browseId != null && it.browseId == imported.browseId }
            ?: locals.filter { it.id !in taken && norm(it.name) == norm(imported.name) }.singleOrNull()
        val playlistId = match?.id ?: Database.insert(
            Playlist(
                name = imported.name.ifBlank { "—" }.take(200),
                browseId = imported.browseId,
                thumbnail = imported.thumbnail
            )
        )
        taken += playlistId
        val present = Database.playlistMapsNow(playlistId)
        val have = present.mapTo(HashSet()) { it.songId }
        val start = (present.maxOfOrNull { it.position } ?: -1) + 1
        val added = tracksOf.filter { it !in have }
        Database.insertSongPlaylistMaps(added.mapIndexed { i, id -> SongPlaylistMap(id, playlistId, start + i) })
        if (match == null || added.isNotEmpty()) playlistsTouched++
    }

    Database.insertSearchQueries(searches.map { SearchQuery(query = it) })

    ImportSummary(
        version = version,
        tracks = tracks,
        plays = newEvents.size,
        playsKnown = playsKnown,
        favorites = favorites,
        lyrics = lyricsAdded,
        playlists = playlistsTouched,
        saved = saved,
        localSkipped = localSkipped,
        datesSkipped = datesSkipped
    )
}

/** The name rule of the server's merge plan (API §4.7): NFKC, trimmed, spaces collapsed, lower case. */
private fun norm(name: String) = Normalizer.normalize(name, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase()
