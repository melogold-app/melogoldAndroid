package app.melogold.android.appfunctions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.test.core.app.ApplicationProvider
import app.melogold.android.R
import app.melogold.domain.voice.VoiceException
import app.melogold.domain.voice.VoiceResult
import app.melogold.domain.voice.VoiceSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the system and Gemini read of the functions (tasks/0006-gemini-app-functions.md): the index KSP writes,
 * the service in the manifest and the description of the app. The functions themselves are tested on a catalog in
 * memory in `:core:domain` (VoiceCommandsTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppFunctionsRegistrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The ids the generated service dispatches: the index must list exactly these. */
    private val ids = listOf(
        MelogoldAppFunctionService.FUNCTION_ID_PLAY_SONG,
        MelogoldAppFunctionService.FUNCTION_ID_SEARCH_SONGS,
        MelogoldAppFunctionService.FUNCTION_ID_PLAY_ARTIST,
        MelogoldAppFunctionService.FUNCTION_ID_PLAY_ALBUM,
        MelogoldAppFunctionService.FUNCTION_ID_PLAY_PLAYLIST,
        MelogoldAppFunctionService.FUNCTION_ID_PLAY_FAVORITES,
        MelogoldAppFunctionService.FUNCTION_ID_SHUFFLE_FAVORITES,
        MelogoldAppFunctionService.FUNCTION_ID_PAUSE,
        MelogoldAppFunctionService.FUNCTION_ID_RESUME,
        MelogoldAppFunctionService.FUNCTION_ID_NEXT
    )

    /** An asset KSP generated: it goes into the APK as a Java resource under `assets/`. */
    private fun asset(name: String): Element {
        val stream = assertNotNull(javaClass.classLoader?.getResourceAsStream("assets/$name"), name)
        return stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement }
    }

    private fun Element.children(tag: String) =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.text(tag: String) = children(tag).singleOrNull()?.textContent?.trim()

    private fun assertBilingual(text: String?, what: String) {
        assertNotNull(text, "$what has no description")
        assertTrue(text.any { it in 'а'..'я' || it in 'А'..'Я' }, "$what: no Russian in «$text»")
        assertTrue(text.any { it in 'a'..'z' }, "$what: no English in «$text»")
    }

    @Test
    fun `the index lists every function, described in English and Russian`() {
        val functions = asset("melogold_app_functions.xml").children("appfunction")

        assertEquals(ids.sorted(), functions.map { it.text("id").orEmpty() }.sorted())
        functions.forEach { function ->
            val id = function.text("id")
            assertEquals("true", function.text("enabledByDefault"), id)
            assertBilingual(function.text("description"), id.orEmpty())
            function.children("parameters").forEach { parameter ->
                assertBilingual(parameter.text("description"), "$id(${parameter.text("name")})")
            }
        }

        val parameters = functions.associate { f -> f.text("id") to f.children("parameters").map { it.text("name") } }
        assertEquals(listOf("query", "videoId"), parameters[MelogoldAppFunctionService.FUNCTION_ID_PLAY_SONG])
        assertEquals(listOf("query"), parameters[MelogoldAppFunctionService.FUNCTION_ID_SEARCH_SONGS])
        assertEquals(listOf("name"), parameters[MelogoldAppFunctionService.FUNCTION_ID_PLAY_PLAYLIST])
        assertEquals(emptyList(), parameters[MelogoldAppFunctionService.FUNCTION_ID_PLAY_FAVORITES])
    }

    @Test
    fun `the legacy index of Android 16 lists the same functions`() {
        val functions = asset("melogold_app_functions-v1.xml").children("appfunction")

        assertEquals(ids.sorted(), functions.map { it.text("function_id").orEmpty() }.sorted())
    }

    @Test
    fun `only the system can bind the service, and it finds it by its action`() {
        val component = ComponentName(context, MelogoldAppFunctionService::class.java)
        val service = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)

        assertEquals("android.permission.BIND_APP_FUNCTION_SERVICE", service.permission)
        assertTrue(service.exported)

        val found = context.packageManager.queryIntentServices(
            Intent("android.app.appfunctions.AppFunctionService").setPackage(context.packageName),
            0
        )
        assertEquals(listOf(component.className), found.map { it.serviceInfo.name })
    }

    @Test
    fun `the app is described with the names Gemini hears instead of Melogold`() {
        val parser = context.resources.getXml(R.xml.app_metadata)
        while (parser.eventType != XmlPullParser.START_TAG) parser.next()
        assertEquals("AppFunctionAppMetadata", parser.name)

        val description = parser.getAttributeValue(NAMESPACE, "description")
        listOf("Melogold", "Mellow Gold", "Мелоголд", "playSong", "searchSongs", "playFavorites").forEach {
            assertTrue(it in description, "no «$it» in the description")
        }
        assertBilingual(description, "the app")
        assertEquals(
            R.string.app_functions_display_description,
            parser.getAttributeResourceValue(NAMESPACE, "displayDescription", 0),
            "the description for the person is localized"
        )
    }

    @Test
    fun `errors of the voice commands become the errors an agent understands`() {
        fun error(kind: VoiceException.Kind) = VoiceException(kind, "Не найдено / Not found").toAppFunctionException()

        assertIs<AppFunctionElementNotFoundException>(error(VoiceException.Kind.NotFound))
        assertIs<AppFunctionInvalidArgumentException>(error(VoiceException.Kind.InvalidArgument))
        assertIs<AppFunctionAppUnknownException>(error(VoiceException.Kind.Unavailable))
        assertEquals("Не найдено / Not found", error(VoiceException.Kind.NotFound).errorMessage)
    }

    @Test
    fun `an answer says where the music was found`() {
        val result = PlaybackResult.of(
            VoiceResult("Звезда по имени Солнце", "Кино", source = VoiceSource.YouTubeMusic),
            openApp = null
        )

        assertEquals(PlaybackResult("Звезда по имени Солнце", "Кино", null, "youtube_music", null), result)
        assertEquals(
            listOf("library", "youtube_music", "youtube", "queue"),
            VoiceSource.entries.map { it.code }
        )
    }

    private companion object {
        const val NAMESPACE = "http://schemas.android.com/apk/androidx.appfunctions"
    }
}
