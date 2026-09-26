package app.melogold.android.ui.screens.player

import androidx.test.core.app.ApplicationProvider
import app.melogold.android.MainApplication
import app.melogold.android.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The message of a track closed in the country YouTube places the device in: the country's name and the count. */
@RunWith(RobolectricTestRunner::class)
class GeoMessageTest {
    private val resources get() = ApplicationProvider.getApplicationContext<MainApplication>().resources

    @Test
    fun `country names from ISO codes`() {
        assertEquals("Россия", countryName("RU", Locale.forLanguageTag("ru")))
        assertEquals("Finland", countryName("FI", Locale.ENGLISH))
        assertEquals("not a code", countryName("not a code", Locale.ENGLISH))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `Russian plural forms of the count`() {
        val many = resources.getQuantityString(R.plurals.player_error_geo_country_open, 122, "Россия", 122)
        assertTrue(many.startsWith("Недоступно в стране «Россия»: YouTube считает, что вы там"), many)
        assertTrue("в 122 других странах" in many, many)
        val one = resources.getQuantityString(R.plurals.player_error_geo_country_open, 121, "Россия", 121)
        assertTrue("в 121 другой стране" in one, one)
        assertTrue("некоторые серверы YouTube тоже относит к стране «Россия»" in one, one)
    }

    @Test
    fun `English`() {
        val text = resources.getQuantityString(R.plurals.player_error_geo_country_open, 122, "Russia", 122)
        assertTrue(text.startsWith("Unavailable in Russia: YouTube places you there"), text)
        assertTrue("in 122 other countries" in text, text)
    }
}
