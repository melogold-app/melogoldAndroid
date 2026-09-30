package app.melogold.domain.server

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * The links of the app that are not a shared playlist (API §7.2):
 *
 * - `melogold://server?v=1&url=<base>&sid=<serverId>` — the address of a server (the QR of `melogold qr` on the server
 *   host): the Server screen opens with it filled in and connects only when the person confirms;
 * - `melogold://link?v=1&mode=<request|invite>&server=<base>&sid=<serverId>&token=<linkToken>` — linking a device.
 *   Nothing signs in or approves by such a link alone.
 */
sealed interface MelogoldLink {
    data class Server(val url: String, val insecure: Boolean, val serverId: String?) : MelogoldLink

    data class DeviceLink(val mode: Mode, val serverUrl: String, val serverId: String, val token: String) : MelogoldLink {
        /** `request`: the QR of a new device, approved in Add device; `invite`: a signed-in device invites a new one. */
        enum class Mode { Request, Invite }
    }
}

object MelogoldLinkParser {
    private val LINK = Regex("""(?i)melogold://\S+""")
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val LINK_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    private const val TRAILING = ".,;:!?)]}>»\"'…"

    fun parse(text: String): MelogoldLink? {
        val link = LINK.find(text)?.value?.trimEnd { it in TRAILING } ?: return null
        val uri = try {
            URI(link)
        } catch (_: URISyntaxException) {
            return null
        }
        val query = queryOf(uri.rawQuery)
        if (query["v"] != "1") return null
        return when (uri.host?.lowercase()) {
            "server" -> {
                val address = ServerAddressPolicy.normalize(query["url"].orEmpty()) as? ServerAddress.Valid ?: return null
                val sid = query["sid"]
                if (sid != null && !UUID.matches(sid)) return null
                MelogoldLink.Server(address.url, address.insecure, sid)
            }

            "link" -> {
                val mode = when (query["mode"]) {
                    "request" -> MelogoldLink.DeviceLink.Mode.Request
                    "invite" -> MelogoldLink.DeviceLink.Mode.Invite
                    else -> return null
                }
                val server = (ServerAddressPolicy.normalize(query["server"].orEmpty()) as? ServerAddress.Valid)?.url ?: return null
                val sid = query["sid"]?.takeIf { UUID.matches(it) } ?: return null
                val token = query["token"]?.takeIf { LINK_TOKEN.matches(it) } ?: return null
                MelogoldLink.DeviceLink(mode, server, sid, token)
            }

            else -> null
        }
    }

    private fun queryOf(raw: String?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val key = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            out.putIfAbsent(decode(key), decode(value))
        }
        return out
    }

    private fun decode(value: String) = try {
        URLDecoder.decode(value, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }
}
