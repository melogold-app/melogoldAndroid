package app.melogold.providers.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContinuationResponse(
    val continuationContents: ContinuationContents?,
    /** The newer format: the items of the next page are appended by an action. */
    val onResponseReceivedActions: List<ResponseReceivedAction>? = null
) {
    @Serializable
    data class ResponseReceivedAction(
        val appendContinuationItemsAction: AppendContinuationItemsAction?
    )

    @Serializable
    data class AppendContinuationItemsAction(
        val continuationItems: List<MusicShelfRenderer.Content>?
    )

    @Serializable
    data class ContinuationContents(
        @JsonNames("musicPlaylistShelfContinuation")
        val musicShelfContinuation: MusicShelfRenderer?,
        val playlistPanelContinuation: NextResponse.MusicQueueRenderer.Content.PlaylistPanelRenderer?
    )
}
