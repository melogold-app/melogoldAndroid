package app.melogold.android.models

import androidx.compose.runtime.Immutable
import androidx.room.Embedded

/** A song of History › Recent, with when it last played. */
@Immutable
data class SongWithLastPlayed(
    @Embedded val song: Song,
    val lastPlayed: Long
)

/** A song of History › Most played, with how long it played in the period. */
@Immutable
data class SongWithPlayTime(
    @Embedded val song: Song,
    val playTime: Long
)
