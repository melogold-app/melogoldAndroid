package app.melogold.android.debug

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class DebugReceiverTest {
    @Test
    fun `finds a command by its short name`() {
        assertSame(PingCommand, DebugReceiver.findCommand("Ping"))
    }

    @Test
    fun `refuses anything but a command of this package`() {
        listOf(
            "Nope", // no such class
            "Debug", // DebugCommand itself is an interface
            "ping", // must start with a capital letter
            "java.lang.Runtime",
            "../Ping",
            ""
        ).forEach { assertNull(DebugReceiver.findCommand(it), it) }
    }

    @Test
    fun `ping answers with the package, the version and the argument`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(
            "pong app.melogold.android.debug 0.1.0-DEBUG hello",
            PingCommand.run(context, "hello", Bundle.EMPTY)
        )
    }
}
