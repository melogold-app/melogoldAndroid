package app.melogold.domain.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs `docs/spec/server-address.vectors.json` through [ServerAddressPolicy]. */
class ServerAddressPolicyTest {
    private val cases = Json.parseToJsonElement(
        File(System.getProperty("melogold.specDir"), "server-address.vectors.json").readText()
    ).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun `every vector comes to its expected address or refusal`() {
        assertTrue(cases.size >= 30)

        val failures = cases.mapNotNull { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject
            val want = expected["error"]?.jsonPrimitive?.content?.let { "error $it" }
                ?: "${expected.getValue("url").jsonPrimitive.content} insecure=${expected.getValue("insecure").jsonPrimitive.boolean}"
            val got = when (val result = ServerAddressPolicy.normalize(case.getValue("input").jsonPrimitive.content)) {
                is ServerAddress.Valid -> "${result.url} insecure=${result.insecure}"
                is ServerAddress.Invalid -> "error ${result.reason.code}"
            }
            if (want == got) null else "$id: expected $want, got $got"
        }

        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
