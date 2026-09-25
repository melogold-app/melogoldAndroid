package app.melogold.android.data.downloads

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mp4TagsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(4).putInt(8 + body.size).array() + type.toByteArray(Charsets.ISO_8859_1) + body

    private fun stco(vararg offsets: Int) = box(
        "stco",
        ByteArray(4) + ByteBuffer.allocate(4).putInt(offsets.size).array() +
            offsets.fold(ByteArray(0)) { acc, it -> acc + ByteBuffer.allocate(4).putInt(it).array() }
    )

    private fun moov(chunkOffsets: IntArray, udta: ByteArray = ByteArray(0)) = box(
        "moov",
        box("mvhd", ByteArray(20)) +
            box("trak", box("tkhd", ByteArray(12)) + box("mdia", box("minf", box("stbl", stco(*chunkOffsets))))) +
            udta
    )

    private val ftyp = box("ftyp", "M4A ".toByteArray() + ByteArray(4))
    private val payload = ByteArray(64) { it.toByte() }

    /** The chunk offsets of the file's only track, and the file itself. */
    private fun offsets(bytes: ByteArray): List<Int> {
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("stco") + 4 + 4
        val count = ByteBuffer.wrap(bytes, at, 4).int
        return (0 until count).map { ByteBuffer.wrap(bytes, at + 4 + it * 4, 4).int }
    }

    @Test
    fun `tags go into moov and the chunks of a front moov move with it`() {
        // ftyp, moov, mdat: the chunks point into mdat's payload
        val moovSize = moov(intArrayOf(0, 0)).size
        val mdatStart = ftyp.size + moovSize + 8
        val file = folder.newFile("front.m4a")
        file.writeBytes(ftyp + moov(intArrayOf(mdatStart, mdatStart + 32)) + box("mdat", payload))

        Mp4Tags.write(file, Mp4TagValues(title = "Кино", artist = "Цой", album = "Группа крови", cover = byteArrayOf(1, 2, 3)))

        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("©nam" in text && "©ART" in text && "©alb" in text && "covr" in text)
        assertTrue(String(bytes, Charsets.UTF_8).contains("Группа крови"))

        // The chunks still start where the payload bytes 0 and 32 are
        val (first, second) = offsets(bytes)
        assertEquals(payload[0], bytes[first])
        assertEquals(payload[32], bytes[second])
    }

    @Test
    fun `a moov at the end changes no chunk and an old udta goes`() {
        val mdat = box("mdat", payload)
        val file = folder.newFile("end.m4a")
        val oldUdta = box("udta", box("meta", ByteArray(4)))
        file.writeBytes(ftyp + mdat + moov(intArrayOf(ftyp.size + 8), udta = oldUdta))

        Mp4Tags.write(file, Mp4TagValues(title = "A", artist = null, album = null, cover = null))

        val bytes = file.readBytes()
        assertEquals(listOf(ftyp.size + 8), offsets(bytes))
        assertEquals(1, Regex("udta").findAll(String(bytes, Charsets.ISO_8859_1)).count())
        assertEquals(ByteArrayOutputStream().apply { write(ftyp); write(mdat) }.toByteArray().toList(), bytes.take(ftyp.size + mdat.size))
    }
}
