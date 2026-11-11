package tv.mochimochi.tvhinput.htsp

import java.io.IOException

/**
 * Walks the top-level fields of an encoded htsmsg in place, without allocating. Used on the
 * per-packet path, where building an [HtsMsg] (a map, boxed numbers, copied payloads) for every
 * muxpkt is wasted work. Reusable: call [reset] for each message.
 */
class HtsFieldReader {

    var buf: ByteArray = EMPTY
        private set
    var type = 0
        private set
    var dataOffset = 0
        private set
    var dataLength = 0
        private set
    private var nameOffset = 0
    private var nameLength = 0
    private var position = 0
    private var end = 0

    fun reset(buf: ByteArray, offset: Int, length: Int) {
        this.buf = buf
        position = offset
        end = offset + length
    }

    /** Advances to the next field; false at the end of the message. */
    fun next(): Boolean {
        val p = position
        if (end - p <= 5) return false
        type = buf[p].toInt() and 0xff
        nameLength = buf[p + 1].toInt() and 0xff
        dataLength = ((buf[p + 2].toInt() and 0xff) shl 24) or ((buf[p + 3].toInt() and 0xff) shl 16) or
            ((buf[p + 4].toInt() and 0xff) shl 8) or (buf[p + 5].toInt() and 0xff)
        nameOffset = p + 6
        if (dataLength < 0 || end - nameOffset < nameLength + dataLength) throw IOException("Malformed htsmsg")
        dataOffset = nameOffset + nameLength
        position = dataOffset + dataLength
        return true
    }

    /** Whether the current field is called [name] (given as ASCII bytes). */
    fun nameIs(name: ByteArray): Boolean = matches(nameOffset, nameLength, name)

    private fun matches(offset: Int, length: Int, expected: ByteArray): Boolean {
        if (length != expected.size) return false
        for (i in 0 until length) if (buf[offset + i] != expected[i]) return false
        return true
    }

    /** The current field as an S64 (little-endian, as few bytes as needed). */
    fun long(): Long {
        var v = 0L
        for (i in dataLength - 1 downTo 0) v = (v shl 8) or (buf[dataOffset + i].toLong() and 0xff)
        return v
    }

    /** The current string field as a method name: known methods come back as shared constants. */
    fun method(): String {
        for (i in 0 until METHODS.size) {
            if (matches(dataOffset, dataLength, METHOD_BYTES[i])) return METHODS[i]
        }
        return String(buf, dataOffset, dataLength, Charsets.UTF_8)
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private val METHODS = arrayOf(
            "muxpkt", "subscriptionStart", "subscriptionStop", "subscriptionStatus", "queueStatus",
            "signalStatus", "subscriptionSkip", "subscriptionSpeed", "subscriptionGrace", "timeshiftStatus",
            "descrambleInfo",
        )
        private val METHOD_BYTES = Array(METHODS.size) { METHODS[it].toByteArray(Charsets.US_ASCII) }

        fun name(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
    }
}
