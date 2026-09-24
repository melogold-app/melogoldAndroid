package app.melogold.android.data.downloads

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/** What a saved file says about itself: the iTunes-style tags every player reads. */
data class Mp4TagValues(
    val title: String?,
    val artist: String?,
    val album: String?,
    /** A JPEG. */
    val cover: ByteArray?
)

/**
 * Writes iTunes-style tags (`moov/udta/meta/ilst`: ©nam, ©ART, ©alb, covr) into an MP4 audio file.
 * An existing `udta` is replaced. When `moov` comes before the media data, the chunk offsets
 * (`stco`, `co64`) move by what `moov` grew.
 */
object Mp4Tags {
    private const val HEADER = 8
    private const val TYPE_UTF8 = 1
    private const val TYPE_JPEG = 13

    private class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    fun write(file: File, tags: Mp4TagValues) {
        val boxes = RandomAccessFile(file, "r").use { topLevel(it) }
        val moov = boxes.firstOrNull { it.type == "moov" } ?: error("No moov box")
        val mediaAfterMoov = boxes.any { it.type == "mdat" && it.offset > moov.offset }

        val oldMoov = RandomAccessFile(file, "r").use { raf ->
            ByteArray(moov.size.toInt()).also {
                raf.seek(moov.offset)
                raf.readFully(it)
            }
        }
        val newMoov = rebuildMoov(oldMoov, moov.headerSize, udta(tags))
        val delta = newMoov.size - oldMoov.size
        if (mediaAfterMoov && delta != 0) shiftChunkOffsets(newMoov, moov.headerSize, delta.toLong())

        val output = File(file.parentFile, "${file.name}.tagged")
        RandomAccessFile(file, "r").use { input ->
            output.outputStream().buffered().use { out ->
                copy(input, 0, moov.offset, out)
                out.write(newMoov)
                copy(input, moov.offset + moov.size, input.length() - (moov.offset + moov.size), out)
            }
        }
        if (!output.renameTo(file)) {
            output.copyTo(file, overwrite = true)
            output.delete()
        }
    }

    private fun topLevel(raf: RandomAccessFile): List<Box> {
        val boxes = mutableListOf<Box>()
        var offset = 0L
        val length = raf.length()
        while (offset + HEADER <= length) {
            raf.seek(offset)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            var headerSize = HEADER
            if (size == 1L) {
                size = raf.readLong()
                headerSize = 16
            } else if (size == 0L) size = length - offset
            if (size < headerSize) break
            boxes += Box(type, offset, size, headerSize)
            offset += size
        }
        return boxes
    }

    /** `moov` with its `udta` replaced by [udta]. */
    private fun rebuildMoov(moov: ByteArray, headerSize: Int, udta: ByteArray): ByteArray {
        val children = ByteArrayOutputStream()
        var offset = headerSize
        while (offset + HEADER <= moov.size) {
            val size = readInt(moov, offset).toLong() and 0xFFFFFFFFL
            val type = String(moov, offset + 4, 4, Charsets.ISO_8859_1)
            val end = if (size == 0L) moov.size else (offset + size).toInt().coerceAtMost(moov.size)
            if (type != "udta") children.write(moov, offset, end - offset)
            if (end <= offset) break
            offset = end
        }
        children.write(udta)

        val body = children.toByteArray()
        return ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(HEADER + body.size)
                writeBytes("moov")
            }
            write(body)
        }.toByteArray()
    }

    private fun udta(tags: Mp4TagValues): ByteArray {
        val ilst = ByteArrayOutputStream().apply {
            tags.title?.let { write(textItem("©nam", it)) }
            tags.artist?.let { write(textItem("©ART", it)) }
            tags.album?.let { write(textItem("©alb", it)) }
            tags.cover?.let { write(item("covr", dataBox(TYPE_JPEG, it))) }
        }.toByteArray()

        // meta is a full box: version and flags before its children
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + "mdir".latin1() + "appl".latin1() + ByteArray(8) + ByteArray(1))
        val meta = box("meta", ByteArray(4) + hdlr + box("ilst", ilst))
        return box("udta", meta)
    }

    private fun textItem(type: String, value: String) = item(type, dataBox(TYPE_UTF8, value.toByteArray(Charsets.UTF_8)))

    private fun item(type: String, data: ByteArray) = box(type, data)

    private fun dataBox(type: Int, value: ByteArray) = box("data", intBytes(type) + ByteArray(4) + value)

    private fun box(type: String, body: ByteArray) = intBytes(HEADER + body.size) + type.latin1() + body

    /** Adds [delta] to every chunk offset of every track in [moov]. */
    private fun shiftChunkOffsets(moov: ByteArray, headerSize: Int, delta: Long) {
        forEachBox(moov, headerSize, moov.size) { type, start, end, header ->
            when (type) {
                "trak", "mdia", "minf", "stbl" -> shiftIn(moov, start + header, end, delta)
            }
        }
    }

    private fun shiftIn(bytes: ByteArray, from: Int, to: Int, delta: Long) {
        forEachBox(bytes, from, to) { type, start, end, header ->
            when (type) {
                "mdia", "minf", "stbl" -> shiftIn(bytes, start + header, end, delta)

                "stco" -> {
                    val count = readInt(bytes, start + header + 4)
                    for (i in 0 until count) {
                        val at = start + header + 8 + i * 4
                        val value = (readInt(bytes, at).toLong() and 0xFFFFFFFFL) + delta
                        writeInt(bytes, at, value.toInt())
                    }
                }

                "co64" -> {
                    val count = readInt(bytes, start + header + 4)
                    for (i in 0 until count) {
                        val at = start + header + 8 + i * 8
                        val value = (readInt(bytes, at).toLong() shl 32) or (readInt(bytes, at + 4).toLong() and 0xFFFFFFFFL)
                        val shifted = value + delta
                        writeInt(bytes, at, (shifted ushr 32).toInt())
                        writeInt(bytes, at + 4, shifted.toInt())
                    }
                }
            }
        }
    }

    private inline fun forEachBox(bytes: ByteArray, from: Int, to: Int, block: (String, Int, Int, Int) -> Unit) {
        var offset = from
        while (offset + HEADER <= to) {
            val size = readInt(bytes, offset).toLong() and 0xFFFFFFFFL
            val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            val end = if (size == 0L) to else (offset + size).toInt().coerceAtMost(to)
            if (end <= offset) return
            block(type, offset, end, HEADER)
            offset = end
        }
    }

    private fun copy(input: RandomAccessFile, from: Long, length: Long, out: java.io.OutputStream) {
        input.seek(from)
        val buffer = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            left -= read
        }
    }

    private fun readInt(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
            (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun String.latin1() = toByteArray(Charsets.ISO_8859_1)
}
