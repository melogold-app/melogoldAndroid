package app.melogold.providers.innertube.utils

import kotlin.io.encoding.Base64

/**
 * The country YouTube placed a request in, from the `visitorData` of its answer: base64 of a protobuf whose field 6
 * is a message with the ISO country code in its field 1 (`CgtRTWpH…MigKAk5M…` → `NL`). Null when it is not there.
 *
 * It is what YouTube decides by, whatever the device says: a VPN server that YouTube counts as Russian gives `RU`.
 */
fun visitorCountry(visitorData: String?): String? {
    if (visitorData.isNullOrBlank()) return null
    val text = visitorData.replace("%3D", "=").replace("%3d", "=").trimEnd('=').replace('-', '+').replace('_', '/')
    val bytes = runCatching {
        Base64.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(text)
    }.getOrNull() ?: return null
    val country = lengthDelimitedField(bytes, 0, bytes.size, field = 6)
        ?.let { (start, end) -> lengthDelimitedField(bytes, start, end, field = 1) }
        ?.let { (start, end) -> bytes.decodeToString(start, end) }
    return country?.takeIf { COUNTRY.matches(it) }
}

private val COUNTRY = Regex("^[A-Z]{2}$")

/** The byte range of the first length-delimited [field] of the protobuf message in `bytes[start, end)`. */
private fun lengthDelimitedField(bytes: ByteArray, start: Int, end: Int, field: Int): Pair<Int, Int>? {
    var index = start
    while (index < end) {
        val (key, afterKey) = varint(bytes, index, end) ?: return null
        index = afterKey
        when ((key and 7L).toInt()) {
            0 -> index = varint(bytes, index, end)?.second ?: return null
            1 -> index += 8
            2 -> {
                val (length, dataStart) = varint(bytes, index, end) ?: return null
                val dataEnd = dataStart + length.toInt()
                if (length < 0 || dataEnd > end) return null
                if ((key shr 3).toInt() == field) return dataStart to dataEnd
                index = dataEnd
            }
            5 -> index += 4
            else -> return null
        }
    }
    return null
}

/** A base-128 varint at [start]: its value and the index after it. */
private fun varint(bytes: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
    var value = 0L
    var shift = 0
    var index = start
    while (index < end && shift < 64) {
        val byte = bytes[index].toInt() and 0xff
        value = value or ((byte and 0x7f).toLong() shl shift)
        index++
        if (byte and 0x80 == 0) return value to index
        shift += 7
    }
    return null
}
