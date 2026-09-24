package app.melogold.providers.innertube.utils

import app.melogold.providers.innertube.Innertube
import app.melogold.providers.innertube.models.MusicTwoRowItemRenderer

fun Innertube.AlbumItem.Companion.from(renderer: MusicTwoRowItemRenderer) = Innertube.AlbumItem(
    info = renderer
        .title
        ?.runs
        ?.firstOrNull()
        ?.let(Innertube::Info),
    authors = null,
    year = renderer
        .subtitle
        ?.runs
        ?.lastOrNull()
        ?.text,
    thumbnail = renderer
        .thumbnailRenderer
        ?.musicThumbnailRenderer
        ?.thumbnail
        ?.thumbnails
        ?.firstOrNull()
).takeIf { it.info?.endpoint?.browseId != null }

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
