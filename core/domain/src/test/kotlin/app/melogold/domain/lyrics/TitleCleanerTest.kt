package app.melogold.domain.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs `docs/spec/title-cleaner.vectors.json` through [TitleCleaner]. */
class TitleCleanerTest {
    private val cases = Json.parseToJsonElement(
        File(System.getProperty("melogold.specDir"), "title-cleaner.vectors.json").readText()
    ).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun `every vector cleans to its expected artist and title`() {
        assertTrue(cases.size >= 40)

        val failures = cases.mapNotNull { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val input = case.getValue("input").jsonObject
            val expected = case.getValue("expected").jsonObject
            fun String.field(from: kotlinx.serialization.json.JsonObject) =
                from[this]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

            val actual = TitleCleaner.clean(
                title = "title".field(input).orEmpty(),
                channel = "channel".field(input),
                videoType = "videoType".field(input)
            )
            val want = CleanTitle(artist = "artist".field(expected), title = "title".field(expected).orEmpty())
            if (actual == want) null else "$id: expected $want, got $actual"
        }

        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
