package dev.tvhinput.htsp

/** Annex B start codes (00 00 01) in H.264 / MPEG-2 video packets. */
internal object StartCodes {

    /** Position of the first start code in `data[from, limit)`, or [limit] if there is none. */
    fun find(data: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i + 2 < limit) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
            i++
        }
        return limit
    }

    /** End of the unit whose payload starts at [payload] and whose next start code is at [next]: without the zero byte of a 4-byte start code. */
    fun unitEnd(data: ByteArray, payload: Int, next: Int): Int {
        var end = next
        while (end > payload && data[end - 1].toInt() == 0) end--
        return end
    }

    /**
     * Calls [block] with each H.264 NAL unit in `data[offset, offset + size)`: its start (at the
     * 3-byte start code), its end and its type. Stops early when [block] returns false.
     */
    inline fun forEachH264Nal(data: ByteArray, offset: Int, size: Int, block: (start: Int, end: Int, type: Int) -> Boolean) {
        val limit = offset + size
        var i = find(data, offset, limit)
        while (i < limit) {
            val payload = i + 3
            val next = find(data, payload, limit)
            val end = unitEnd(data, payload, next)
            if (end > payload && !block(i, end, data[payload].toInt() and 0x1f)) return
            i = next
        }
    }
}
