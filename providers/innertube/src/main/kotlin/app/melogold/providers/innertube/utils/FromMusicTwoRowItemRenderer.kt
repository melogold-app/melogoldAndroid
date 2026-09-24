package app.melogold.providers.innertube.utils

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.MusicTwoRowItemRenderer
import app.melogold.providers.innertube.models.NavigationEndpoint
import app.melogold.providers.innertube.models.splitBySeparator

private val YEAR = Regex("""\d{4}""")

/**
 * An album of a carousel or a grid. Its subtitle reads "2024", "Album • 2024" or "Single • Artist":
 * the year is the four digits, the artists are the links, the type is the plain first part.
 */
fun Innertube.AlbumItem.Companion.from(renderer: MusicTwoRowItemRenderer): Innertube.AlbumItem? {
    val runs = renderer.subtitle?.runs.orEmpty()
    val first = runs.splitBySeparator().firstOrNull()?.singleOrNull()

    return Innertube.AlbumItem(
        info = renderer
            .title
            ?.runs
            ?.firstOrNull()
            ?.let(Innertube::Info),
        authors = runs
            .filter { it.navigationEndpoint?.browseEndpoint != null }
            .map { Innertube.Info<NavigationEndpoint.Endpoint.Browse>(it) }
            .ifEmpty { null },
        year = runs.firstOrNull { it.text?.matches(YEAR) == true }?.text,
        thumbnail = renderer
            .thumbnailRenderer
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.firstOrNull(),
        typeText = first
            ?.takeIf { it.navigationEndpoint == null && it.text?.matches(YEAR) == false }
            ?.text
    ).takeIf { it.info?.endpoint?.browseId != null }
}

fun Innertube.ArtistItem.Companion.from(renderer: MusicTwoRowItemRenderer) = Innertube.ArtistItem(
    info = renderer
        .title
        ?.runs
        ?.firstOrNull()
        ?.let(Innertube::Info),
    subscribersCountText = renderer
        .subtitle
        ?.runs
        ?.firstOrNull()
        ?.text,
    thumbnail = renderer
        .thumbnailRenderer
        ?.musicThumbnailRenderer
        ?.thumbnail
        ?.thumbnails
        ?.firstOrNull()
).takeIf { it.info?.endpoint?.browseId != null }

fun Innertube.PlaylistItem.Companion.from(renderer: MusicTwoRowItemRenderer) =
    Innertube.PlaylistItem(
        info = renderer
            .title
            ?.runs
            ?.firstOrNull()
            ?.let(Innertube::Info),
        channel = renderer
            .subtitle
            ?.runs
            ?.getOrNull(2)
            ?.let(Innertube::Info),
        songCount = renderer
            .subtitle
            ?.runs
            ?.getOrNull(4)
            ?.text
            ?.split(' ')
            ?.firstOrNull()
            ?.toIntOrNull(),
        thumbnail = renderer
            .thumbnailRenderer
            ?.musicThumbnailRenderer
            ?.thumbnail
            ?.thumbnails
            ?.firstOrNull()
    ).takeIf { it.info?.endpoint?.browseId != null }

/** A video of a carousel (an artist's "Videos"): the item itself is the watch link. */
fun Innertube.VideoItem.Companion.from(renderer: MusicTwoRowItemRenderer) = Innertube.VideoItem(
    info = Innertube.Info(
        name = renderer.title?.runs?.firstOrNull()?.text,
        endpoint = renderer.navigationEndpoint?.watchEndpoint
    ),
    authors = renderer
        .subtitle
        ?.runs
        ?.filter { it.navigationEndpoint?.browseEndpoint != null }
        ?.map(Innertube::Info),
    viewsText = renderer
        .subtitle
        ?.runs
        ?.lastOrNull()
        ?.takeIf { it.navigationEndpoint == null }
        ?.text,
    durationText = null,
    thumbnail = renderer
        .thumbnailRenderer
        ?.musicThumbnailRenderer
        ?.thumbnail
        ?.thumbnails
        ?.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
).takeIf { it.info?.endpoint?.videoId != null }
