package app.melogold.providers.innertube.links

import kotlinx.serialization.json.Json
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
 * Runs every case of `docs/spec/youtube-links.vectors.json` (REWRITE §4.9): the desktops run the
 * same file through their own parsers.
 */
class YouTubeLinkParserTest {
    private val vectors = File(System.getProperty("melogold.specDir"), "youtube-links.vectors.json")

    @Test
    fun `every vector parses to its expected target`() {
        val cases = Json.parseToJsonElement(vectors.readText()).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 79, "vectors must not shrink: ${cases.size}")

        val failures = cases.mapNotNull { element ->
            val case = element.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val input = case.getValue("input").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject
            val actual = YouTubeLinkParser.parse(input).toJson()
            if (actual == expected) null else "$id: expected $expected, got $actual"
        }

        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    private fun LinkTarget.toJson(): JsonObject = when (this) {
        is LinkTarget.Video -> json(
            "Video",
            "videoId" to JsonPrimitive(videoId),
            "playlistId" to (playlistId?.let(::JsonPrimitive) ?: JsonNull),
            "index" to (index?.let(::JsonPrimitive) ?: JsonNull),
            "startMs" to (startMs?.let(::JsonPrimitive) ?: JsonNull)
        )

        is LinkTarget.Playlist -> json("Playlist", "playlistId" to JsonPrimitive(playlistId))
        is LinkTarget.Album -> json("Album", "browseId" to JsonPrimitive(browseId))
        is LinkTarget.Channel -> json("Channel", "channelId" to JsonPrimitive(channelId))
        is LinkTarget.Handle -> json("Handle", "handle" to JsonPrimitive(handle))
        is LinkTarget.LegacyChannel -> json("LegacyChannel", "url" to JsonPrimitive(url))
        is LinkTarget.Search -> json("Search", "query" to JsonPrimitive(query))
        is LinkTarget.External -> json("External", "service" to JsonPrimitive(service), "url" to JsonPrimitive(url))
        is LinkTarget.Unsupported -> json("Unsupported", "reason" to JsonPrimitive(reason))
    }

    private fun json(type: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) =
        JsonObject(mapOf("type" to JsonPrimitive(type)) + fields)
}
