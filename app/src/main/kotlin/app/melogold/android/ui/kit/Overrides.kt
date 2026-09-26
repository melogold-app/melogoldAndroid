package app.melogold.android.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.melogold.android.data.overrides.TrackOverrides
import app.melogold.android.models.TrackOverride

/** The user's own names of tracks (tasks/0012), for the rows of a list. */
@Composable
fun rememberTrackOverrides(): Map<String, TrackOverride> = TrackOverrides.all.collectAsState().value
