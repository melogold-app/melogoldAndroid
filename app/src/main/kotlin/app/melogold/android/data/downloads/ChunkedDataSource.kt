package app.melogold.android.data.downloads

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** 4 MiB: googlevideo serves a range this size at full speed and throttles one long request. */
const val DOWNLOAD_CHUNK_BYTES = 4L * 1024 * 1024

/**
 * One continuous stream made of range requests of [chunkSize] (REWRITE §4.7.2). The total length
 * comes from the first response's `Content-Range`, so the cache learns the real size instead of
 * taking the end of a chunk for the end of the track.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long = DOWNLOAD_CHUNK_BYTES
) : BaseDataSource(/* isNetwork = */ true) {
    private var dataSpec: DataSpec? = null
    private var position = 0L

    // Where the request ends (exclusive), when known: its own length or the resource's
    private var end: Long? = null
    private var chunkEnd = 0L
    private var chunkOpen = false

    // Nothing read from the chunk just opened yet: an empty one ends the stream, not the loop
    private var chunkFresh = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) null else dataSpec.position + dataSpec.length

        transferInitializing(dataSpec)
        openChunk(dataSpec)
        transferStarted(dataSpec)

        return end?.let { it - dataSpec.position } ?: C.LENGTH_UNSET.toLong()
    }

    private fun openChunk(spec: DataSpec) {
        val length = end?.let { minOf(chunkSize, it - position) } ?: chunkSize
        val opened = upstream.open(spec.buildUpon().setPosition(position).setLength(length).build())
        chunkOpen = true
        chunkFresh = true
        chunkEnd = position + (if (opened == C.LENGTH_UNSET.toLong()) length else opened)

        // "bytes 0-4194303/7456789": the size of the whole resource
        if (end == null) end = totalLength() ?: run {
            // No range support: this one response is the whole resource
            if (opened != C.LENGTH_UNSET.toLong() && opened < length) chunkEnd else null
        }
    }

    private fun totalLength(): Long? = upstream.responseHeaders.entries
        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
        ?.value?.firstOrNull()
        ?.substringAfterLast('/')
        ?.trim()
        ?.toLongOrNull()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val spec = dataSpec ?: return C.RESULT_END_OF_INPUT

        while (true) {
            val limit = end
            if (limit != null && position >= limit) return C.RESULT_END_OF_INPUT

            val read = if (chunkOpen) upstream.read(buffer, offset, length) else C.RESULT_END_OF_INPUT
            if (read != C.RESULT_END_OF_INPUT) {
                chunkFresh = false
                position += read
                bytesTransferred(read)
                return read
            }
            if (chunkFresh) return C.RESULT_END_OF_INPUT

            // This chunk is over: the next one, unless the resource is
            if (chunkOpen) {
                upstream.close()
                chunkOpen = false
            }
            // Less than asked for, with no size known: that is all there is
            if (limit == null && position < chunkEnd) return C.RESULT_END_OF_INPUT
            openChunk(spec)
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            if (chunkOpen) upstream.close()
        } finally {
            chunkOpen = false
            if (dataSpec != null) {
                dataSpec = null
                transferEnded()
            }
        }
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkSize: Long = DOWNLOAD_CHUNK_BYTES
    ) : DataSource.Factory {
        override fun createDataSource() = ChunkedDataSource(upstream.createDataSource(), chunkSize)
    }
}
