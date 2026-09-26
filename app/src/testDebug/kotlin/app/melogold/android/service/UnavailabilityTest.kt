package app.melogold.android.service

import app.melogold.providers.innertube.requests.Playability
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Why a track does not play, from YouTube's answers: the report was Saba "Photosynthesis" (cYKAr38pZcY), open in
 * 122 countries but not in Russia, with yt-dlp saying only "Video unavailable", also behind a Helsinki VPN that
 * Google counts as Russian. Robolectric: a PlaybackException reads the Android clock.
 */
@RunWith(RobolectricTestRunner::class)
class UnavailabilityTest {
    private val open122 = (List(121) { "C$it" } + "FI").take(122)
    private val ytDlpUnavailable = "DownloadError: ERROR: [youtube] cYKAr38pZcY: Video unavailable"

    @Test
    fun `closed in the country YouTube places the device in, with the country and the count`() {
        val error = unavailability(Playability("UNPLAYABLE", "Video unavailable", "RU", open122), ytDlpUnavailable)
        assertIs<RestrictedVideoException>(error)
        assertEquals("RU", error.country)
        assertEquals(122, error.availableCountries)
    }

    @Test
    fun `open in the country, no reason to give, the generic error stays`() {
        assertNull(unavailability(Playability("OK", null, "FI", open122), ytDlpUnavailable))
        assertNull(unavailability(null, ytDlpUnavailable))
        assertNull(unavailability(Playability("UNPLAYABLE", "Video unavailable", "RU", emptyList()), null))
    }

    @Test
    fun `yt-dlp's own words about the country count without the list`() {
        val error = unavailability(
            null,
            "ERROR: [youtube] abc: The uploader has not made this video available in your country"
        )
        assertIs<RestrictedVideoException>(error)
        assertNull(error.country)
        assertNull(error.availableCountries)
    }

    @Test
    fun `age and removed videos`() {
        assertIs<LoginRequiredException>(unavailability(null, "ERROR: Sign in to confirm your age"))
        assertIs<UnplayableException>(unavailability(null, "ERROR: [youtube] abc: Private video"))
        assertIs<UnplayableException>(
            unavailability(Playability("ERROR", "This video has been removed by the uploader", null, emptyList()), null)
        )
    }
}
