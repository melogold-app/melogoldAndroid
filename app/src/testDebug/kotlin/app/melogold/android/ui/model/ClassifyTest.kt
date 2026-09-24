package app.melogold.android.ui.model

import kotlinx.serialization.SerializationException
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.assertEquals

class ClassifyTest {
    @Test
    fun `network failures are offline`() {
        assertEquals(Loadable.Error.Kind.Offline, classify(UnknownHostException("music.youtube.com")))
        assertEquals(Loadable.Error.Kind.Offline, classify(SocketTimeoutException()))
        assertEquals(Loadable.Error.Kind.Offline, classify(IOException("wrapped", UnknownHostException())))
    }

    @Test
    fun `broken answers are parser errors`() {
        assertEquals(Loadable.Error.Kind.Parser, classify(SerializationException("unexpected token")))
        assertEquals(Loadable.Error.Kind.Parser, classify(NoSuchElementException("no sections")))
    }

    @Test
    fun `a bot check is blocked`() {
        assertEquals(Loadable.Error.Kind.Blocked, classify(IllegalStateException("Sign in to confirm you're not a bot")))
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(Loadable.Error.Kind.Unknown, classify(IllegalArgumentException("?")))
    }
}
