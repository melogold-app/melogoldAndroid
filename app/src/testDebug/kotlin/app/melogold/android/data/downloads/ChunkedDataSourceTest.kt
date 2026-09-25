package app.melogold.android.data.downloads

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class ChunkedDataSourceTest {
    private val data = ByteArray(10_000) { (it % 251).toByte() }

    /** Serves [data] by ranges like googlevideo; the first response of [dropAfter] bytes ends early. */
    private inner class FakeServer(private val dropAfter: Int? = null) : DataSource {
        val requests = mutableListOf<Pair<Long, Long>>()
        private var position = 0
        private var end = 0
        private var dropped = false
        private var headers = emptyMap<String, List<String>>()

        override fun open(dataSpec: DataSpec): Long {
            val start = dataSpec.position.toInt()
            val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size - start else dataSpec.length.toInt()
            position = start
            end = minOf(data.size, start + length)
            requests += dataSpec.position to dataSpec.length
            headers = mapOf("content-range" to listOf("bytes $start-${end - 1}/${data.size}"))
            return (end - start).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= end) return C.RESULT_END_OF_INPUT
            if (dropAfter != null && !dropped && position >= dropAfter) {
                dropped = true
                return C.RESULT_END_OF_INPUT
            }
            val count = minOf(length, end - position, dropAfter?.takeIf { !dropped }?.let { it - position } ?: Int.MAX_VALUE)
            data.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun getResponseHeaders() = headers
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    private fun ChunkedDataSource.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(700)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri("https://example.com/a").setPosition(position).setLength(length).build()

    @Test
    fun `the whole resource comes in chunks, with its real length`() {
        val server = FakeServer()
        val source = ChunkedDataSource(server, chunkSize = 4_096)

        assertEquals(data.size.toLong(), source.open(spec()))
        assertContentEquals(data, source.readAll())
        assertEquals(listOf(0L to 4_096L, 4_096L to 4_096L, 8_192L to 1_808L), server.requests)
    }

    @Test
    fun `a range stays a range`() {
        val source = ChunkedDataSource(FakeServer(), chunkSize = 4_096)

        assertEquals(5_000L, source.open(spec(position = 3_000, length = 5_000)))
        assertContentEquals(data.copyOfRange(3_000, 8_000), source.readAll())
    }

    @Test
    fun `a response that ends early is picked up where it stopped`() {
        val server = FakeServer(dropAfter = 1_500)
        val source = ChunkedDataSource(server, chunkSize = 4_096)

        source.open(spec())
        assertContentEquals(data, source.readAll())
        assertEquals(1_500L to 4_096L, server.requests[1])
    }
}
