package tv.mochimochi.tvhinput.htsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HtsMsgTest {

    @Test
    fun roundTripsAllFieldTypes() {
        val msg = HtsMsg.of(
            "method" to "subscribe",
            "zero" to 0,
            "small" to 42,
            "big" to 0x1234_5678_9abcL,
            "negative" to -1L,
            "flag" to true,
            "digest" to byteArrayOf(1, 2, 3),
            "nested" to HtsMsg.of("index" to 1, "type" to "H264"),
            "list" to listOf(HtsMsg.of("index" to 2), "text", 7L),
        )
        val wire = msg.serialize()
        val length = ((wire[0].toInt() and 0xff) shl 24) or ((wire[1].toInt() and 0xff) shl 16) or
            ((wire[2].toInt() and 0xff) shl 8) or (wire[3].toInt() and 0xff)
        assertEquals(wire.size - 4, length)

        val decoded = HtsMsg.deserialize(wire, 4, length)
        assertEquals("subscribe", decoded.method)
        assertEquals(0L, decoded.long("zero"))
        assertEquals(42, decoded.int("small"))
        assertEquals(0x1234_5678_9abcL, decoded.long("big"))
        assertEquals(-1L, decoded.long("negative"))
        assertEquals(true, decoded["flag"])
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.bin("digest"))
        assertEquals("H264", (decoded["nested"] as HtsMsg).str("type"))
        val list = decoded.list("list")
        assertEquals(2, (list[0] as HtsMsg).int("index"))
        assertEquals("text", list[1])
        assertEquals(7L, list[2])
    }

    @Test
    fun encodesIntegersLikeTvheadend() {
        // S64 is little-endian with as few bytes as needed; zero has no data bytes.
        val wire = HtsMsg.of("v" to 0x0102).serialize()
        // length(4) type(1) namelen(1) datalen(4) name(1) data(2)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 9, 2, 1, 0, 0, 0, 2, 'v'.code.toByte(), 0x02, 0x01),
            wire,
        )
        val zero = HtsMsg.of("v" to 0).serialize()
        assertEquals(0, zero[9].toInt())
    }
}
