package app.melogold.android.ui.model

import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What a screen shows for data it loads (REWRITE §3.0, §4.11.3).
 */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    /**
     * @param refreshing a refresh is running on top of [value]
     * @param staleSince when [value] was fetched, if the last refresh failed (the "data from 14:02"
     * chip); null while [value] is fresh
     * @param staleReason why the last refresh failed
     */
    data class Content<T>(
        val value: T,
        val refreshing: Boolean = false,
        val staleSince: Long? = null,
        val staleReason: Error.Kind? = null
    ) : Loadable<T>

    data class Error(val kind: Kind, val cause: Throwable? = null) : Loadable<Nothing> {
        enum class Kind { Offline, Blocked, Parser, Unknown }
    }
}

val <T> Loadable<T>.valueOrNull get() = (this as? Loadable.Content<T>)?.value

val Loadable<*>.isRefreshing get() = (this as? Loadable.Content<*>)?.refreshing == true

/**
 * Sorts a failure into what the user can do about it.
 */
fun classify(throwable: Throwable): Loadable.Error.Kind {
    val chain = generateSequence(throwable) { it.cause }.take(8).toList()

    return when {
        chain.any { it is ResponseException && it.response.status.value in setOf(403, 429) } ->
            Loadable.Error.Kind.Blocked

        chain.any { it.message?.contains("bot", ignoreCase = true) == true } -> Loadable.Error.Kind.Blocked

        chain.any {
            it is UnknownHostException || it is ConnectException || it is SocketTimeoutException ||
                it is SSLException || it.javaClass.simpleName.contains("Timeout")
        } -> Loadable.Error.Kind.Offline

        chain.any { it is SerializationException || it is NoSuchElementException || it is NullPointerException } ->
            Loadable.Error.Kind.Parser

        chain.any { it is IOException } -> Loadable.Error.Kind.Offline

        else -> Loadable.Error.Kind.Unknown
    }
}
