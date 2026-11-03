package dev.tvhinput.htsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HtsFieldReaderTest {

    @Test
    fun readsMuxpktFieldsInPlace() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val wire = HtsMsg.of(
            "method" to "muxpkt",
            "subscriptionId" to 3,
            "stream" to 1,
            "pts" to 0x1_0000_0000L,
            "frametype" to 'I'.code,
            "payload" to payload,
        ).serialize()

        val fields = HtsFieldReader()
        fields.reset(wire, 4, wire.size - 4)
        val seen = mutableMapOf<String, Any>()
        while (fields.next()) {
            when {
                fields.nameIs(HtsFieldReader.name("method")) -> seen["method"] = fields.method()
                fields.nameIs(HtsFieldReader.name("payload")) ->
                    seen["payload"] = wire.copyOfRange(fields.dataOffset, fields.dataOffset + fields.dataLength)
                fields.nameIs(HtsFieldReader.name("pts")) -> seen["pts"] = fields.long()
                fields.nameIs(HtsFieldReader.name("frametype")) -> seen["frametype"] = fields.long()
            }
        }
        assertSame("known methods are shared constants", "muxpkt", seen["method"])
        assertEquals(0x1_0000_0000L, seen["pts"])
        assertEquals('I'.code.toLong(), seen["frametype"])
        assertArrayEquals(payload, seen["payload"] as ByteArray)
    }

    @Test
    fun handlesUnknownMethodsAndEmptyMessages() {
        val wire = HtsMsg.of("method" to "somethingNew").serialize()
        val fields = HtsFieldReader()
        fields.reset(wire, 4, wire.size - 4)
        assertTrue(fields.next())
        assertEquals("somethingNew", fields.method())
        assertFalse(fields.next())

        fields.reset(ByteArray(0), 0, 0)
        assertFalse(fields.next())
    }

    @Test
    fun poolReusesPowerOfTwoBuffers() {
        val a = BufferPool.obtain(5000)
        assertEquals(8192, a.size)
        BufferPool.recycle(a)
        assertSame(a, BufferPool.obtain(6000))
        assertEquals(4096, BufferPool.obtain(0).size)

        // Too big to pool, and arrays the pool didn't make are ignored.
        val big = BufferPool.obtain(3 shl 20)
        assertEquals(3 shl 20, big.size)
        BufferPool.recycle(big)
        BufferPool.recycle(ByteArray(5000))
        assertNotSame(big, BufferPool.obtain(3 shl 20))
    }
}
