package app.melogold.android.ui.shell

import app.melogold.domain.server.MelogoldLink
import app.melogold.domain.server.MelogoldLinkParser
import app.melogold.domain.share.ShareLinkParser
import app.melogold.domain.share.ShareRef
import app.melogold.providers.innertube.links.LinkTarget
import app.melogold.providers.innertube.links.YouTubeLinkParser
import app.melogold.providers.songlink.MusicServiceLink
import app.melogold.providers.songlink.MusicServiceLinkParser

/** What a link or a shared text is for the app (tasks/0017). */
sealed interface AppLink {
    /** YouTube and YouTube Music links, and words to search for ([LinkTarget.Search]); also what is not a link. */
    data class YouTube(val target: LinkTarget) : AppLink

    /** A playlist someone shared with a link of Melogold: `melogold://share` or `https://<server>/s/<id>`. */
    data class SharedPlaylist(val ref: ShareRef) : AppLink

    /** A link of Spotify, Apple Music, Yandex Music, Deezer, Tidal or SoundCloud. */
    data class OtherService(val link: MusicServiceLink) : AppLink

    /** `melogold://server` or `melogold://link` (API §7.2). */
    data class App(val link: MelogoldLink) : AppLink
}

/**
 * Tells what [text] is: a link of the app first (its scheme is nobody else's), then YouTube, then the other services and
 * the page of a shared playlist, which any server can have and so goes last. A text that is none of them stays what
 * [YouTubeLinkParser] made of it: words to search for, or a link the app cannot open.
 */
fun classifyLink(text: String): AppLink {
    if (text.contains("melogold://", ignoreCase = true)) {
        // Its scheme is nobody else's: a link of the app that is none of these is a link nobody can open
        return ShareLinkParser.parse(text)?.let { AppLink.SharedPlaylist(it) }
            ?: MelogoldLinkParser.parse(text)?.let { AppLink.App(it) }
            ?: AppLink.YouTube(LinkTarget.Unsupported(LinkTarget.REASON_UNKNOWN_PATH))
    }

    val youtube = YouTubeLinkParser.parse(text)
    val foreign = youtube is LinkTarget.External || (
        youtube is LinkTarget.Unsupported &&
            youtube.reason in setOf(LinkTarget.REASON_UNKNOWN_HOST, LinkTarget.REASON_UNSUPPORTED_EXTERNAL)
        )
    if (!foreign) return AppLink.YouTube(youtube)

    MusicServiceLinkParser.parse(text)?.let { return AppLink.OtherService(it) }
    ShareLinkParser.parse(text)?.let { return AppLink.SharedPlaylist(it) }
    return AppLink.YouTube(youtube)
}
