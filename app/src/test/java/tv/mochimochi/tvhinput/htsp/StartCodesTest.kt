package tv.mochimochi.tvhinput.htsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartCodesTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun types(data: ByteArray, hevc: Boolean): List<Int> {
        val found = ArrayList<Int>()
        StartCodes.forEachNal(data, 0, data.size, hevc) { _, _, type -> found += type; true }
        return found
    }

    @Test
    fun h264Types() = assertEquals(
        listOf(7, 8, 5), // SPS, PPS, IDR slice
        types(bytes(0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 3), hevc = false),
    )

    @Test
    fun hevcTypes() = assertEquals(
        listOf(32, 33, 34), // VPS, SPS, PPS: type in bits 1-6 of a 2-byte header
        types(bytes(0, 0, 1, 0x40, 1, 9, 0, 0, 1, 0x42, 1, 9, 0, 0, 1, 0x44, 1, 9), hevc = true),
    )

    @Test
    fun loasSync() {
        assertTrue(LatmAudio.isLoas(bytes(0x56, 0xe2, 0x10), 0, 3))
        assertFalse(LatmAudio.isLoas(bytes(0xff, 0xf1, 0x50), 0, 3)) // ADTS
    }
}
