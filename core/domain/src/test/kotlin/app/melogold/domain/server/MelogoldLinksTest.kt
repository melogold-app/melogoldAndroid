package app.melogold.domain.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** API §7.2: `melogold://server` and `melogold://link`. */
class MelogoldLinksTest {
    private val sid = "6f1c2c0e-8a3b-4f7e-9c1d-2b5e7a9f0c11"
    private val token = "q3JdV0hZxK2mP9sT4uW7yB1cE5fH8jL0nR3vX6zA2dG"

    @Test
    fun `a server link carries the address and optionally the server id`() {
        assertEquals(
            MelogoldLink.Server("https://music.example.com", insecure = false, serverId = sid),
            MelogoldLinkParser.parse("melogold://server?v=1&url=https%3A%2F%2Fmusic.example.com%2F&sid=$sid")
        )
        assertEquals(
            MelogoldLink.Server("http://192.168.1.10:8787", insecure = true, serverId = null),
            MelogoldLinkParser.parse("Сервер: melogold://server?v=1&url=http%3A%2F%2F192.168.1.10%3A8787.")
        )
    }

    @Test
    fun `a device link needs a mode, a server, its id and a token`() {
        assertEquals(
            MelogoldLink.DeviceLink(MelogoldLink.DeviceLink.Mode.Invite, "https://music.example.com", sid, token),
            MelogoldLinkParser.parse("melogold://link?v=1&mode=invite&server=https%3A%2F%2Fmusic.example.com&sid=$sid&token=$token")
        )
        assertNull(MelogoldLinkParser.parse("melogold://link?v=1&mode=steal&server=https%3A%2F%2Fa.b&sid=$sid&token=$token"))
        assertNull(MelogoldLinkParser.parse("melogold://link?v=1&mode=invite&server=https%3A%2F%2Fa.b&sid=$sid&token=short"))
    }

    @Test
    fun `other versions, hosts and bad servers are nothing`() {
        assertNull(MelogoldLinkParser.parse("melogold://server?v=2&url=https%3A%2F%2Fa.b"))
        assertNull(MelogoldLinkParser.parse("melogold://share?v=1&url=https%3A%2F%2Fa.b&id=Ab3dE5gH9k"))
        assertNull(MelogoldLinkParser.parse("melogold://server?v=1&url=ftp%3A%2F%2Fa.b"))
        assertNull(MelogoldLinkParser.parse("melogold://server?v=1&url=https%3A%2F%2Fa.b&sid=NOT-A-UUID"))
    }
}
