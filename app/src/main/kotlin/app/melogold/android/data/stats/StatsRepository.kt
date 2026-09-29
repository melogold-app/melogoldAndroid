package app.melogold.android.data.stats

import app.melogold.android.Database
import app.melogold.android.models.TrackOverride
import java.time.LocalDate
import java.time.ZoneId

/**
 * Loads what one period of statistics needs from the database and counts it (tasks/0016). It blocks: call it off the
 * main thread. Nothing here touches the network: the History is on the device, with the plays of the other devices of
 * the account that the sync brought (API §4.8).
 */
object StatsRepository {
    /**
     * The statistics of [window]. The plays of the period before it are read with it, for the comparison; the
     * tracks, artists and albums only of the period itself.
     */
    fun load(
        window: StatsWindow,
        overrides: Map<String, TrackOverride>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        includes: (deviceId: String?) -> Boolean = { true }
    ): ListeningStats {
        val from = window.previous(zone)?.start ?: window.start
        val input = StatsInput(
            events = Database.statEvents(from, window.end),
            songs = Database.statSongs(window.start, window.end).associateBy { it.id },
            artists = Database.statArtists(window.start, window.end).associateBy { it.songId },
            albums = Database.statAlbums(window.start, window.end).associateBy { it.songId },
            overrides = overrides,
            firstPlays = Database.firstPlays().associate { it.songId to it.firstAt }
        )
        return computeStats(input, window, zone, today, includes)
    }
}
