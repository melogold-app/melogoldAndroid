package app.melogold.providers.innertube.requests

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.BrowseResponse
import app.melogold.providers.innertube.models.Context
import app.melogold.providers.innertube.models.MusicCarouselShelfRenderer
import app.melogold.providers.innertube.models.MusicShelfRenderer
import app.melogold.providers.innertube.models.SectionListRenderer
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.utils.findSectionByTitle
import app.melogold.providers.innertube.utils.title
import app.melogold.providers.innertube.utils.from
import app.melogold.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext

suspend fun Innertube.artistPage(body: BrowseBody) = runCatchingCancellable {
    val ctx = currentCoroutineContext()
    val response = client.post(BROWSE) {
        setBody(body)
        mask("contents,header")
    }.body<BrowseResponse>()

    val responseNoLang by lazy {
        CoroutineScope(ctx).async(start = CoroutineStart.LAZY) {
            client.post(BROWSE) {
                setBody(body.copy(context = Context.DefaultWebNoLang))
                mask("contents,header")
            }.body<BrowseResponse>()
        }
    }

    fun BrowseResponse.sections() = contents
        ?.singleColumnBrowseResultsRenderer
        ?.tabs
        ?.get(0)
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer

    // The titles are English only in the response without a language: find the section there,
    // then take the same one from the localized response, whose items read "1,2 млн просмотров"
    suspend fun findSectionByTitle(text: String): SectionListRenderer.Content? {
        val localized = response.sections()
        localized?.findSectionByTitle(text)?.let { return it }

        val english = responseNoLang.await().sections()?.contents ?: return null
        val index = english.indexOfFirst { it.title == text }
            .takeIf { it >= 0 }
            ?: english.indexOfFirst { it.title?.contains(text, ignoreCase = true) == true }
        val section = english.getOrNull(index) ?: return null

        return localized?.contents?.getOrNull(index)?.takeIf { it.isSameKindAs(section) } ?: section
    }

    val songsSection = findSectionByTitle("Songs")?.musicShelfRenderer
    val albumsSection = findSectionByTitle("Albums")?.musicCarouselShelfRenderer
    val singlesSection = (findSectionByTitle("Singles & EPs") ?: findSectionByTitle("Singles"))
        ?.musicCarouselShelfRenderer
    val videosSection = findSectionByTitle("Videos")?.musicCarouselShelfRenderer
    val relatedSection = findSectionByTitle("Fans might also like")?.musicCarouselShelfRenderer

    Innertube.ArtistPage(
        name = response
            .header
            ?.musicImmersiveHeaderRenderer
            ?.title
            ?.text,
        description = response
            .header
            ?.musicImmersiveHeaderRenderer
            ?.description
            ?.text,
        thumbnail = (
            response
                .header
                ?.musicImmersiveHeaderRenderer
                ?.foregroundThumbnail
                ?: response
                    .header
                    ?.musicImmersiveHeaderRenderer
                    ?.thumbnail
            )
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.getOrNull(0),
        shuffleEndpoint = response
            .header
            ?.musicImmersiveHeaderRenderer
            ?.playButton
            ?.buttonRenderer
            ?.navigationEndpoint
            ?.watchEndpoint,
        radioEndpoint = response
            .header
            ?.musicImmersiveHeaderRenderer
            ?.startRadioButton
            ?.buttonRenderer
            ?.navigationEndpoint
            ?.watchEndpoint,
        songs = songsSection
            ?.contents
            ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
            ?.mapNotNull(Innertube.SongItem::from),
        songsEndpoint = songsSection
            ?.bottomEndpoint
            ?.browseEndpoint,
        albums = albumsSection
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
            ?.mapNotNull(Innertube.AlbumItem::from),
        albumsEndpoint = albumsSection
            ?.header
            ?.musicCarouselShelfBasicHeaderRenderer
            ?.moreContentButton
            ?.buttonRenderer
            ?.navigationEndpoint
            ?.browseEndpoint,
        singles = singlesSection
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
            ?.mapNotNull(Innertube.AlbumItem::from),
        singlesEndpoint = singlesSection
            ?.header
            ?.musicCarouselShelfBasicHeaderRenderer
            ?.moreContentButton
            ?.buttonRenderer
            ?.navigationEndpoint
            ?.browseEndpoint,
        subscribersCountText = response
            .header
            ?.musicImmersiveHeaderRenderer
            ?.subscriptionButton
            ?.subscribeButtonRenderer
            ?.subscriberCountText
            ?.text,
        videos = videosSection
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
            ?.mapNotNull(Innertube.VideoItem::from),
        videosEndpoint = videosSection
            ?.header
            ?.musicCarouselShelfBasicHeaderRenderer
            ?.moreContentButton
            ?.buttonRenderer
            ?.navigationEndpoint
            ?.browseEndpoint,
        relatedArtists = relatedSection
            ?.contents
            ?.mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
            ?.mapNotNull(Innertube.ArtistItem::from)
    )
}

private fun SectionListRenderer.Content.isSameKindAs(other: SectionListRenderer.Content) =
    (musicShelfRenderer != null) == (other.musicShelfRenderer != null) &&
        (musicCarouselShelfRenderer != null) == (other.musicCarouselShelfRenderer != null) &&
        musicCarouselShelfRenderer?.contents?.size == other.musicCarouselShelfRenderer?.contents?.size &&
        musicShelfRenderer?.contents?.size == other.musicShelfRenderer?.contents?.size
