package app.melogold.android.models

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** Where a download stands (REWRITE §4.7.4). */
enum class DownloadState {
    Queued,

    /** Queued, but the requirements are not met: see [TrackDownload.waitReason]. */
    Waiting,
    Downloading,

    /** Stopped by the user. */
    Paused,
    Completed,
    Failed
}

/** What a waiting download waits for. */
enum class DownloadWaitReason { Network, Wifi, Storage }

/** Why a download failed (REWRITE §4.7.4): shown next to the track. */
enum class DownloadFailure { Unavailable, Network, StorageFull, Unknown }

/**
 * A track kept on the device to play without a network (REWRITE §4.2, table `Download`). The bytes
 * are in the download cache under the video id; this row mirrors the state of the download.
 *
 * @param manual "Download" was tapped on the track itself, not only on a collection it is in
 */
@Immutable
@Entity(
    tableName = "Download",
    foreignKeys = [
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("state"), Index("completedAt")]
)
data class TrackDownload(
    @PrimaryKey val videoId: String,
    @ColumnInfo(defaultValue = "0") val manual: Boolean = false,
    val state: DownloadState,
    val waitReason: DownloadWaitReason? = null,
    val failureCode: DownloadFailure? = null,
    @ColumnInfo(defaultValue = "0") val bytesDownloaded: Long = 0,
    val contentLength: Long? = null,
    val itag: Int? = null,
    val mimeType: String? = null,
    val requestedAt: Long,
    val completedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0
) {
    /** 0..1, or null while the size is unknown. */
    val progress: Float?
        get() = contentLength?.takeIf { it > 0 }?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }

    val isActive get() = state == DownloadState.Queued || state == DownloadState.Waiting || state == DownloadState.Downloading
}

/**
 * "Keep this collection downloaded" (REWRITE §4.2): exactly one of [playlistId], [albumId] and
 * [liked] is set, which the code checks.
 */
@Immutable
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"], unique = true),
        Index(value = ["albumId"], unique = true)
    ]
)
data class DownloadCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long? = null,
    val albumId: String? = null,
    @ColumnInfo(defaultValue = "0") val liked: Boolean = false,
    val addedAt: Long
)

/** A track with its download, for "Downloads". */
@Immutable
data class SongWithDownload(
    @Embedded val song: Song,
    @Relation(parentColumn = "id", entityColumn = "videoId") val download: TrackDownload?
)
