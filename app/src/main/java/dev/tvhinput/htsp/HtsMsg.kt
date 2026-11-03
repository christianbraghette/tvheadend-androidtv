package dev.tvhinput.htsp

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A tvheadend "htsmsg": named fields of type long, string, binary, boolean, map or list,
 * serialized in HTSP's binary format (see tvheadend src/htsmsg_binary.c).
 */
class HtsMsg(private val fields: MutableMap<String, Any> = LinkedHashMap()) {

    operator fun set(name: String, value: Any) {
        fields[name] = if (value is Int) value.toLong() else value
    }

    operator fun get(name: String): Any? = fields[name]

    val method: String? get() = str("method")

    fun str(name: String): String? = fields[name] as? String
    fun long(name: String): Long? = fields[name] as? Long
    fun int(name: String): Int? = long(name)?.toInt()
    fun bin(name: String): ByteArray? = fields[name] as? ByteArray

    @Suppress("UNCHECKED_CAST")
    fun list(name: String): List<Any> = fields[name] as? List<Any> ?: emptyList()

    /** The message as sent on the wire: a 4-byte big-endian length followed by the fields. */
    fun serialize(): ByteArray {
        val body = ByteArrayOutputStream()
        writeFields(body, fields.entries.map { it.key to it.value })
        val bytes = body.toByteArray()
        return ByteArrayOutputStream(bytes.size + 4).apply {
            writeInt(this, bytes.size)
            write(bytes)
        }.toByteArray()
    }

    override fun toString(): String = fields.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
        "$k=" + if (v is ByteArray) "<${v.size} bytes>" else v.toString()
    }

    companion object {
        private const val MAP = 1
        private const val S64 = 2
        private const val STR = 3
        private const val BIN = 4
        private const val LIST = 5
        private const val BOOL = 7

        /** Decodes a message body (without the 4-byte length prefix). */
        fun deserialize(buf: ByteArray, offset: Int = 0, length: Int = buf.size): HtsMsg {
            val msg = HtsMsg()
            readFields(buf, offset, length) { name, value -> msg.fields[name] = value }
            return msg
        }

        fun of(vararg pairs: Pair<String, Any>) = HtsMsg().apply { pairs.forEach { (k, v) -> this[k] = v } }

        private fun readFields(buf: ByteArray, offset: Int, length: Int, sink: (String, Any) -> Unit) {
            var p = offset
            val end = offset + length
            while (end - p > 5) {
                val type = buf[p].toInt() and 0xff
                val nameLen = buf[p + 1].toInt() and 0xff
                val dataLen = ((buf[p + 2].toInt() and 0xff) shl 24) or ((buf[p + 3].toInt() and 0xff) shl 16) or
                    ((buf[p + 4].toInt() and 0xff) shl 8) or (buf[p + 5].toInt() and 0xff)
                p += 6
                if (dataLen < 0 || end - p < nameLen + dataLen) throw IOException("Malformed htsmsg")
                val name = String(buf, p, nameLen, Charsets.UTF_8)
                p += nameLen
                val value: Any = when (type) {
                    STR -> String(buf, p, dataLen, Charsets.UTF_8)
                    S64 -> {
                        // Little-endian, as few bytes as needed (none for zero).
                        var v = 0L
                        for (i in dataLen - 1 downTo 0) v = (v shl 8) or (buf[p + i].toLong() and 0xff)
                        v
                    }
                    BOOL -> dataLen == 1 && buf[p].toInt() != 0
                    MAP -> deserialize(buf, p, dataLen)
                    LIST -> ArrayList<Any>().also { list -> readFields(buf, p, dataLen) { _, v -> list += v } }
                    else -> buf.copyOfRange(p, p + dataLen) // BIN, UUID and anything unknown
                }
                sink(name, value)
                p += dataLen
            }
        }

        private fun writeFields(out: ByteArrayOutputStream, fields: List<Pair<String, Any>>) {
            for ((name, value) in fields) {
                val (type, data) = when (value) {
                    is String -> STR to value.toByteArray(Charsets.UTF_8)
                    is Long -> S64 to encodeS64(value)
                    is Int -> S64 to encodeS64(value.toLong())
                    is Boolean -> BOOL to byteArrayOf(if (value) 1 else 0)
                    is ByteArray -> BIN to value
                    is HtsMsg -> MAP to ByteArrayOutputStream().also {
                        writeFields(it, value.fields.entries.map { e -> e.key to e.value })
                    }.toByteArray()
                    is List<*> -> LIST to ByteArrayOutputStream().also {
                        writeFields(it, value.filterNotNull().map { v -> "" to v })
                    }.toByteArray()
                    else -> throw IllegalArgumentException("Unsupported htsmsg value: ${value.javaClass}")
                }
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                out.write(type)
                out.write(nameBytes.size)
                writeInt(out, data.size)
                out.write(nameBytes)
                out.write(data)
            }
        }

        private fun encodeS64(value: Long): ByteArray {
            val bytes = ByteArrayOutputStream(8)
            var v = value
            while (v != 0L) {
                bytes.write((v and 0xff).toInt())
                v = v ushr 8
            }
            return bytes.toByteArray()
        }

        private fun writeInt(out: ByteArrayOutputStream, v: Int) {
            out.write(v ushr 24 and 0xff)
            out.write(v ushr 16 and 0xff)
            out.write(v ushr 8 and 0xff)
            out.write(v and 0xff)
        }
    }
}
