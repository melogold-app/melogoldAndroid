package app.melogold.domain.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals

/** Runs `docs/spec/import-ids.vectors.json` through [ImportIds]. */
class ImportIdsTest {
    private val cases = Json.parseToJsonElement(
        File(System.getProperty("melogold.specDir"), "import-ids.vectors.json").readText()
    ).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun `every vector gives its id`() {
        assertEquals(4, cases.size)
        cases.forEach { case ->
            val input = case.getValue("input").jsonObject
            val id = ImportIds.eventId(
                videoId = input.getValue("videoId").jsonPrimitive.content,
                timestampMs = input.getValue("timestampMs").jsonPrimitive.long,
                playTimeMs = input.getValue("playTimeMs").jsonPrimitive.long
            )
            assertEquals(case.getValue("expected").jsonPrimitive.content, id, case.getValue("id").jsonPrimitive.content)
        }
    }

    @Test
    fun `a version 5 uuid of the RFC variant`() {
        val uuid = UUID.fromString(ImportIds.eventId("dQw4w9WgXcQ", 1, 1))
        assertEquals(5, uuid.version())
        assertEquals(2, uuid.variant())
    }
}
