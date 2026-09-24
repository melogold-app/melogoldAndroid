package app.melogold.providers.innertube.requests

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.BrowseResponse
import app.melogold.providers.innertube.models.MusicTwoRowItemRenderer
import app.melogold.providers.innertube.models.bodies.BrowseBody
import app.melogold.providers.innertube.utils.from
import app.melogold.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody

suspend fun Innertube.discoverPage() = runCatchingCancellable {
    val response = client.post(BROWSE) {
        setBody(BrowseBody(browseId = "FEmusic_explore"))
        mask("contents")
    }.body<BrowseResponse>()

    val sections = response
        .contents
        ?.singleColumnBrowseResultsRenderer
        ?.tabs
        ?.firstOrNull()
        ?.tabRenderer
        ?.content
        ?.sectionListRenderer
        ?.contents

    Innertube.DiscoverPage(
        newReleaseAlbums = sections?.find {
            it.musicCarouselShelfRenderer
                ?.header
                ?.musicCarouselShelfBasicHeaderRenderer
                ?.moreContentButton
                ?.buttonRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
                ?.browseId == "FEmusic_new_releases_albums"
        }?.musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull { it.musicTwoRowItemRenderer?.toNewReleaseAlbumPage() }
            .orEmpty(),
        moods = sections?.find {
            it.musicCarouselShelfRenderer
                ?.header
                ?.musicCarouselShelfBasicHeaderRenderer
                ?.moreContentButton
                ?.buttonRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
                ?.browseId == "FEmusic_moods_and_genres"
        }?.musicCarouselShelfRenderer
            ?.contents
            ?.mapNotNull { it.musicNavigationButtonRenderer?.toMood() }
            .orEmpty(),
        trending = run {
            val renderer = sections?.find {
                it.musicCarouselShelfRenderer
                    ?.header
                    ?.musicCarouselShelfBasicHeaderRenderer
                    ?.moreContentButton
                    ?.buttonRenderer
                    ?.navigationEndpoint
                    ?.browseEndpoint
                    ?.browseEndpointContextSupportedConfigs
                    ?.browseEndpointContextMusicConfig
                    ?.pageType == "MUSIC_PAGE_TYPE_PLAYLIST"
            }?.musicCarouselShelfRenderer

            Innertube.DiscoverPage.Trending(
                songs = renderer
                    ?.toBrowseItem(Innertube.SongItem::from)
                    ?.items
                    ?.filterIsInstance<Innertube.SongItem>()
                    ?.map { song ->
                        // Why, YouTube, why
                        song.copy(
                            authors = song.authors?.firstOrNull()?.let { listOf(it) } ?: emptyList()
                        )
                    }
                    .orEmpty(),
                endpoint = renderer
                    ?.header
                    ?.musicCarouselShelfBasicHeaderRenderer
                    ?.moreContentButton
                    ?.buttonRenderer
                    ?.navigationEndpoint
                    ?.browseEndpoint
            )
        }
    )
}

// The same reading as every album card: "Single • Artist", the year only where it is one
fun MusicTwoRowItemRenderer.toNewReleaseAlbumPage() = Innertube.AlbumItem.from(this)
