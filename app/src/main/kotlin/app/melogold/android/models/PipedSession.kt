package app.melogold.android.models

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.ktor.http.Url

/**
 * A Piped login of the pre-rewrite app. Piped is gone; the table stays in the schema until the
 * frozen v31 database (REWRITE §5.1, S4) drops it.
 */
@Immutable
@Entity(
    indices = [
        Index(
            value = ["apiBaseUrl", "username"],
            unique = true
        )
    ]
)
data class PipedSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val apiBaseUrl: Url,
    val token: String,
    // the username should never change on piped
    val username: String
)
