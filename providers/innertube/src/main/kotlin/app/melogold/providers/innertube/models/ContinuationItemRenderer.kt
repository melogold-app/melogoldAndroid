package app.melogold.providers.innertube.models

import kotlinx.serialization.Serializable

/**
 * The next page of a list in the newer format (REWRITE §3.8.2, rating #5): an item at the end of
 * the list whose command carries the token.
 */
@Serializable
data class ContinuationItemRenderer(
    val continuationEndpoint: ContinuationEndpoint?
) {
    @Serializable
    data class ContinuationEndpoint(
        val continuationCommand: ContinuationCommand?
    )

    @Serializable
    data class ContinuationCommand(
        val token: String?
    )

    val token get() = continuationEndpoint?.continuationCommand?.token
}
