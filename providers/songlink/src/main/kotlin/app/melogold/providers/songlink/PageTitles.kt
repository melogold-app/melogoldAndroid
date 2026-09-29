package app.melogold.providers.songlink

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/**
 * Reads the page of a link of another service, when song.link cannot tell where its track is on YouTube (tasks/0017):
 * the name and the artist the page says of it become the words to search for. Only a little of the page is read (its
 * head has the title), and a failure is nothing, not an error.
 */
class PageFetcher(
    private val http: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpRedirect) { checkHttpMethod = false }
        install(HttpTimeout) {
            connectTimeoutMillis = TIMEOUT_MS
            requestTimeoutMillis = TIMEOUT_MS
        }
    }
) {
    /** The first part of the page as text, or null: no network, an error, a page that is not one. */
    suspend fun fetch(url: String): String? = try {
        val response = http.get(url) {
            // The services send their titles to a browser; a mobile one gets the light page
            header(HttpHeaders.UserAgent, BROWSER)
            header(HttpHeaders.AcceptLanguage, "en")
        }
        if (!response.status.isSuccess()) null
        else response.bodyAsChannel().readRemaining(MAX_BYTES.toLong()).readByteArray().decodeToString()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        null
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val MAX_BYTES = 400_000
        const val BROWSER = "Mozilla/5.0 (Linux; Android 16; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * The words to search for taken from the head of the page of a link: "Never Gonna Give You Up Rick Astley". Each service
 * says its name and artist in its own way (its `<title>`, `og:title` and `og:description`), so each has its rule; a page
 * that says nothing usable (SoundCloud shows a title of its own) gives null.
 */
object PageTitles {
    private const val MAX_QUERY = 120

    fun searchText(link: MusicServiceLink, html: String): String? {
        val title = tagText(html, "title")?.let(::clean)
        val og = meta(html, "og:title")
        val description = meta(html, "og:description")

        val query = when (link.service) {
            MusicService.Spotify -> spotify(link.kind, title, og, description)
            MusicService.AppleMusic -> apple(link.kind, title, og)
            MusicService.YandexMusic -> yandex(link.kind, title, og, description)
            MusicService.Tidal -> tidal(link.kind, title, og)
            MusicService.Deezer -> deezer(link.kind, title, og)
            MusicService.SoundCloud -> soundcloud(title)
        }
        return query?.let(::tidy)?.takeIf { it.isNotEmpty() }
    }

    // "Never Gonna Give You Up - song and lyrics by Rick Astley | Spotify", "Rick Astley | Spotify"
    private val SPOTIFY_TITLE = Regex("""^(.+?) - (?:song and lyrics|song|album|single|EP)(?: and lyrics)? by (.+?) \| Spotify$""", RegexOption.IGNORE_CASE)

    private fun spotify(kind: MusicLinkKind, title: String?, og: String?, description: String?): String? {
        title?.let { SPOTIFY_TITLE.find(it) }?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        if (kind == MusicLinkKind.Artist) return og ?: title?.removeSuffix(" | Spotify")
        // A track page: og:title is the name, the description starts with the artist ("Rick Astley · Album · Song · 1987")
        val artist = description?.takeIf { " · " in it }?.substringBefore(" · ")
        return listOfNotNull(og ?: title?.removeSuffix(" | Spotify"), artist).joinToString(" ")
    }

    // "Never Gonna Give You Up - Song with Lyrics by Rick Astley - Apple Music", "Rick Astley - Apple Music"
    private val APPLE_TITLE = Regex("""^(.+?) - (?:Song|Album|Single|EP)(?: with Lyrics)? by (.+?) - Apple Music$""", RegexOption.IGNORE_CASE)
    private val APPLE_OG = Regex("""^(.+) by (.+?) on Apple Music$""", RegexOption.IGNORE_CASE)

    private fun apple(kind: MusicLinkKind, title: String?, og: String?): String? {
        title?.let { APPLE_TITLE.find(it) }?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        og?.let { APPLE_OG.find(it) }?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        if (kind == MusicLinkKind.Artist) return og?.removeSuffix(" on Apple Music") ?: title?.removeSuffix(" - Apple Music")
        return null
    }

    // "Never Gonna Give You Up Rick Astley слушать онлайн на Яндекс Музыке", the name and "Rick Astley • Трек • 2019"
    private val YANDEX_TAIL = Regex("""\s+слушать онлайн.*$""", RegexOption.IGNORE_CASE)

    private fun yandex(kind: MusicLinkKind, title: String?, og: String?, description: String?): String? {
        val artist = description?.takeIf { " • " in it }?.substringBefore(" • ")
        if (kind == MusicLinkKind.Artist) return og ?: title?.replace(YANDEX_TAIL, "")
        if (og != null) return listOfNotNull(og, artist).joinToString(" ")
        return title?.replace(YANDEX_TAIL, "")
    }

    // "Never Gonna Give You Up by Rick Astley on TIDAL", og:title "Rick Astley - Never Gonna Give You Up"
    private val TIDAL_TITLE = Regex("""^(.+) by (.+?) on TIDAL$""", RegexOption.IGNORE_CASE)

    private fun tidal(kind: MusicLinkKind, title: String?, og: String?): String? {
        title?.let { TIDAL_TITLE.find(it) }?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        if (kind == MusicLinkKind.Artist) return og ?: title?.removeSuffix(" on TIDAL")
        return og?.replace(" - ", " ")
    }

    // "Daft Punk - Harder, Better, Faster, Stronger | Deezer", "Daft Punk | Deezer"
    private fun deezer(kind: MusicLinkKind, title: String?, og: String?): String? {
        val head = title?.removeSuffix("| Deezer")?.trim()?.trimEnd('|')?.trim() ?: og
        return head?.replace(" - ", " ")
    }

    // The old title of a track page: "Stream Never Gonna Give You Up by Rick Astley | Listen online for free on SoundCloud"
    private val SOUNDCLOUD_TITLE = Regex("""^Stream (.+?) by (.+?) \|""", RegexOption.IGNORE_CASE)

    private fun soundcloud(title: String?): String? =
        title?.let { SOUNDCLOUD_TITLE.find(it) }?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }

    // region The head of a page

    private fun tagText(html: String, tag: String): String? =
        Regex("""<$tag[^>]*>(.*?)</$tag>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)

    /** The `content` of `<meta property="[name]" …>` (or `name=`), attributes in any order. */
    private fun meta(html: String, name: String): String? {
        val tags = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
        for (tag in tags) {
            val text = tag.value
            val named = Regex("""(?:property|name)\s*=\s*(["'])${Regex.escape(name)}\1""", RegexOption.IGNORE_CASE).containsMatchIn(text)
            if (!named) continue
            Regex("""content\s*=\s*(["'])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)
                ?.groupValues?.get(2)?.let { return clean(it) }
        }
        return null
    }

    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")

    /** Decodes the entities of HTML, drops the invisible marks some services put in titles, turns no-break spaces into spaces. */
    private fun clean(raw: String): String = ENTITY.replace(raw) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") -> body.drop(2).toIntOrNull(HEX)?.let { String(Character.toChars(it)) }
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[body.lowercase()]
        } ?: match.value
    }.filter { it !in INVISIBLE }.replace(NO_BREAK_SPACES, " ").trim()

    private val NAMED = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ")
    private val NO_BREAK_SPACES = Regex("[\u00A0\u2007\u202F]")
    private val INVISIBLE = setOf('‎', '‏', '​', '﻿', '‪', '‬')
    private const val HEX = 16

    // endregion

    private fun tidy(text: String): String {
        val collapsed = text.replace(Regex("""\s+"""), " ").trim()
        if (collapsed.length <= MAX_QUERY) return collapsed
        val end = if (Character.isHighSurrogate(collapsed[MAX_QUERY - 1])) MAX_QUERY - 1 else MAX_QUERY
        return collapsed.substring(0, end).trim()
    }
}
