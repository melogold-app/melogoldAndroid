package app.melogold.android.models

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["syncId"], unique = true)]
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val songId: String,
    val timestamp: Long,
    val playTime: Long,
    /** The play on the Melogold server (API §4.8 `play.add`, the event id): given when made, or when first sent. */
    val syncId: String? = null,
    /** The device of the account that played it (API §4.8 `PlayRow.deviceId`); null: this one. */
    val deviceId: String? = null,
    /** The server has it: sent from here, or received from there. */
    @ColumnInfo(defaultValue = "0") val sent: Boolean = false
)
