package app.melogold.domain.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs `docs/spec/lyrics.vectors.json` through the parsers, then checks that TTML (and LRC, for
 * what LRC can hold) round-trips.
 */
class LyricsFormatsTest {
    private val cases = Json.parseToJsonElement(
        File(System.getProperty("melogold.specDir"), "lyrics.vectors.json").readText()
    ).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun `every vector parses to its expected lyrics`() {
        assertTrue(cases.size >= 10)

        val failures = cases.mapNotNull { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val input = case.getValue("input").jsonPrimitive.content
            val expected = case.getValue("expected")
            val actual = LyricsFormats.parseSynced(input)?.toJson() ?: JsonNull
            if (actual == expected) null else "$id:\n  expected $expected\n  got      $actual"
        }

        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun `ttml round-trips everything`() {
        cases.mapNotNull { LyricsFormats.parseSynced(it.getValue("input").jsonPrimitive.content) }.forEach { lyrics ->
            assertEquals(lyrics, TtmlFormat.parse(TtmlFormat.write(lyrics)))
        }
    }

    @Test
    fun `lrc round-trips lines, words and duets`() {
        cases
            .filter { it.getValue("id").jsonPrimitive.content.startsWith("lrc-") }
            .mapNotNull { LyricsFormats.parseSynced(it.getValue("input").jsonPrimitive.content) }
            .forEach { lyrics -> assertEquals(lyrics, LrcFormat.parse(LrcFormat.write(lyrics))) }
    }

    @Test
    fun `ttml times in every notation`() {
        assertEquals(1_500, TtmlFormat.parseTime("1.5s"))
        assertEquals(450, TtmlFormat.parseTime("450ms"))
        assertEquals(62_345, TtmlFormat.parseTime("01:02.345"))
        assertEquals(3_723_000, TtmlFormat.parseTime("1:02:03"))
        assertEquals(3_500, TtmlFormat.parseTime("3.5"))
        assertEquals(null, TtmlFormat.parseTime("soon"))
    }

    private fun SyncedLyrics.toJson(): JsonElement = JsonObject(
        mapOf(
            "timing" to JsonPrimitive(timing.name),
            "language" to (language?.let(::JsonPrimitive) ?: JsonNull),
            "agents" to JsonArray(
                agents.map {
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(it.id),
                            "side" to JsonPrimitive(it.side.name),
                            "name" to (it.name?.let(::JsonPrimitive) ?: JsonNull)
                        )
                    )
                }
            ),
            "lines" to JsonArray(lines.map { it.toJson() })
        )
    )

    private fun SyncedLine.toJson() = JsonObject(
        mapOf(
            "startMs" to JsonPrimitive(startMs),
            "endMs" to JsonPrimitive(endMs),
            "text" to JsonPrimitive(text),
            "words" to words.toJson(),
            "side" to JsonPrimitive(side.name),
            "agent" to (agent?.let(::JsonPrimitive) ?: JsonNull),
            "language" to (language?.let(::JsonPrimitive) ?: JsonNull),
            "background" to (
                background?.let {
                    JsonObject(
                        mapOf(
                            "startMs" to JsonPrimitive(it.startMs),
                            "endMs" to JsonPrimitive(it.endMs),
                            "words" to it.words.toJson()
                        )
                    )
                } ?: JsonNull
                ),
            "translation" to (translation?.let(::JsonPrimitive) ?: JsonNull),
            "transliteration" to (transliteration?.let(::JsonPrimitive) ?: JsonNull)
        )
    )

    private fun List<SyncedWord>.toJson() = JsonArray(
        map { JsonArray(listOf(JsonPrimitive(it.startMs), JsonPrimitive(it.endMs), JsonPrimitive(it.text))) }
    )
}
