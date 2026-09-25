package app.melogold.domain.server

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/** What the address of a Melogold server typed by the user comes to (REWRITE §3.5.12, API §7.1). */
sealed interface ServerAddress {
    /** [url] is the base URL: `scheme://host[:port][/prefix]`, no `/` at the end. [insecure]: plain `http`. */
    data class Valid(val url: String, val insecure: Boolean) : ServerAddress

    data class Invalid(val reason: Reason) : ServerAddress

    enum class Reason(val code: String) {
        Empty("empty"),
        Malformed("malformed"),

        /** Only `https` and `http`. */
        UnsupportedScheme("unsupported_scheme"),

        /** A login, parameters or `#` in the address. */
        CredentialsOrParams("credentials_or_params"),

        /** `http` to a host on the internet. */
        HttpsRequired("https_required")
    }
}

/**
 * The address rules every client shares (API §7.1; vectors `docs/spec/server-address.vectors.json`):
 *
 * 1. trim; without a scheme `https://`; scheme and host in lower case; the `/` at the end goes, a path prefix stays;
 *    a login, parameters or a fragment are refused;
 * 2. `https` goes to any host; `http` only to a private one ([isPrivateHost]). The check after DNS (every address
 *    private) is the caller's: this function never resolves names.
 */
object ServerAddressPolicy {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")

    fun normalize(input: String): ServerAddress {
        val text = input.trim()
        if (text.isEmpty()) return ServerAddress.Invalid(ServerAddress.Reason.Empty)
        val withScheme = if (SCHEME.containsMatchIn(text)) text else "https://$text"

        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return ServerAddress.Invalid(ServerAddress.Reason.Malformed)
        }
        val scheme = uri.scheme?.lowercase() ?: return ServerAddress.Invalid(ServerAddress.Reason.Malformed)
        if (scheme != "https" && scheme != "http") return ServerAddress.Invalid(ServerAddress.Reason.UnsupportedScheme)
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || withScheme.endsWith("?") ||
            withScheme.endsWith("#")
        ) {
            return ServerAddress.Invalid(ServerAddress.Reason.CredentialsOrParams)
        }
        // A host URI can't parse (an underscore, a bad label) ends up in the authority only
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return ServerAddress.Invalid(
                if (uri.rawAuthority?.contains('@') == true) ServerAddress.Reason.CredentialsOrParams else ServerAddress.Reason.Malformed
            )

        val insecure = scheme == "http"
        if (insecure && !isPrivateHost(host)) return ServerAddress.Invalid(ServerAddress.Reason.HttpsRequired)

        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return ServerAddress.Valid(url = "$scheme://$host$port$path", insecure = insecure)
    }

    /**
     * A host plain `http` may go to: IPv4 of `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `127/8`, `100.64/10`
     * (Tailscale); IPv6 `fc00::/7`, `fe80::/10`, `::1`; a name `*.local`, `*.lan`, `*.home.arpa`, `*.internal` or of
     * one word. [host] is in lower case, an IPv6 literal in brackets.
     */
    fun isPrivateHost(host: String): Boolean {
        if (host.startsWith("[") && host.endsWith("]")) {
            // A literal: no DNS lookup happens for it
            val address = runCatching { InetAddress.getByName(host.substring(1, host.length - 1)) }.getOrNull()
            return address is Inet6Address && address.isPrivate6()
        }
        IPV4.matchEntire(host)?.let { match ->
            val octets = match.groupValues.drop(1).map { it.toInt() }
            if (octets.any { it > 255 }) return false
            val (a, b) = octets
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        return '.' !in host || PRIVATE_SUFFIXES.any { host.endsWith(it) && host.length > it.length }
    }

    private fun Inet6Address.isPrivate6(): Boolean {
        val bytes = address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return isLoopbackAddress || (first and 0xFE) == 0xFC || (first == 0xFE && (second and 0xC0) == 0x80)
    }
}
