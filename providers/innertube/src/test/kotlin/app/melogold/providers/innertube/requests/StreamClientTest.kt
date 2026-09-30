package app.melogold.providers.innertube.requests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `stream-clients.json`: the file of this repository parses, a broken one is not applied. */
class StreamClientTest {
    @Test
    fun `the file on main parses into the built-in list`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { it.resolve("config/stream-clients.json") }
            .first { it.exists() }
        assertEquals(StreamClient.builtIn, StreamClient.parse(file.readText()))
    }

    @Test
    fun `another schema, no clients or a client without its fields is not applied`() {
        val client = """{"name":"VISIONOS","id":101,"version":"1.02","host":"www.youtube.com","userAgent":"UA"}"""
        assertEquals(listOf("VISIONOS"), StreamClient.parse("""{"schema":1,"clients":[$client]}""")?.map { it.name })
        assertNull(StreamClient.parse("""{"schema":2,"clients":[$client]}"""))
        assertNull(StreamClient.parse("""{"schema":1,"clients":[]}"""))
        assertNull(StreamClient.parse("""{"schema":1,"clients":[{"name":"VISIONOS","id":101}]}"""))
        assertNull(StreamClient.parse("""{"schema":1,"clients":[${client.replace("\"UA\"", "\"\"")}]}"""))
        assertNull(StreamClient.parse("not json"))
    }
}
