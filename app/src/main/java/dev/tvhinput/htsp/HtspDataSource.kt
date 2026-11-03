package dev.tvhinput.htsp

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays `htsp://channel/<channelId>` by subscribing on a shared [HtspConnection]. The bytes it
 * returns are the subscription's raw htsmsg frames, which [HtspExtractor] turns into samples.
 */
@OptIn(UnstableApi::class)
class HtspDataSource(
    private val connections: () -> HtspConnection,
    private val onStatus: (subscriptionError: String?) -> Unit,
) : BaseDataSource(/* isNetwork= */ true), HtspConnection.SubscriptionListener {

    /**
     * [onStatus] receives tvheadend's subscription problems ("noFreeAdapter", "scrambled", ...) as
     * they happen, and null once the subscription is fine again. It is called on the reader thread.
     */
    class Factory(
        private val connections: () -> HtspConnection,
        private val onStatus: (String?) -> Unit,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = HtspDataSource(connections, onStatus)
    }

    private object EndOfStream

    @Volatile private var streamsSignature: List<String>? = null

    private val queue = LinkedBlockingQueue<Any>()
    private var connection: HtspConnection? = null
    private var subscriptionId = 0
    private var uri: Uri? = null
    private var opened = false
    /** One subscription message: the first [length] bytes of a [BufferPool] buffer. */
    private class Chunk(val data: ByteArray, val length: Int)

    private var chunk: Chunk? = null
    /** Read position in [chunk]: -4..-1 while emitting its length header, then the body offset. */
    private var chunkPos = 0
    @Volatile private var lastMessageNs = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val channelId = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Invalid HTSP uri ${dataSpec.uri}")
        queue.clear()
        streamsSignature = null
        lastMessageNs = System.nanoTime() // tuning can take a few seconds before the first message
        val c = connections()
        connection = c
        subscriptionId = c.subscribe(channelId, this)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var current = chunk
        while (current == null) {
            if (Thread.interrupted()) throw InterruptedIOException()
            when (val item = queue.poll(100, TimeUnit.MILLISECONDS)) {
                null -> {
                    // tvheadend sends status messages every second while subscribed: total silence
                    // means a dead connection or stream, so fail and let the player resubscribe.
                    if (System.nanoTime() - lastMessageNs > NO_DATA_TIMEOUT_NS) {
                        val reason = "No data from tvheadend for ${NO_DATA_TIMEOUT_NS / 1_000_000_000} s"
                        // Don't reuse a connection that may be dead: the retry opens a fresh one.
                        connection?.abort(reason)
                        throw IOException(reason)
                    }
                    continue
                }
                is IOException -> throw item
                EndOfStream -> return C.RESULT_END_OF_INPUT
                is Chunk -> {
                    current = item
                    chunk = item
                    chunkPos = -4
                }
            }
        }
        val n: Int
        if (chunkPos < 0) {
            // The 4-byte big-endian length that HtspExtractor expects before each message.
            n = minOf(length, -chunkPos)
            for (i in 0 until n) buffer[offset + i] = (current.length ushr (8 * (-chunkPos - 1 - i))).toByte()
        } else {
            n = minOf(length, current.length - chunkPos)
            System.arraycopy(current.data, chunkPos, buffer, offset, n)
        }
        chunkPos += n
        if (chunkPos == current.length) {
            BufferPool.recycle(current.data)
            chunk = null
        }
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        connection?.unsubscribe(subscriptionId)
        connection = null
        chunk?.let { BufferPool.recycle(it.data) }
        chunk = null
        while (true) {
            val item = queue.poll() ?: break
            if (item is Chunk) BufferPool.recycle(item.data)
        }
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    override fun onMessage(method: String, body: ByteArray, length: Int) {
        lastMessageNs = System.nanoTime()
        // muxpkt (nearly all traffic) goes to HtspExtractor undecoded; only the rare control
        // messages are decoded here.
        if (method == "muxpkt") {
            queue.put(Chunk(body, length))
            return
        }
        val msg = if (method.startsWith("subscription")) HtsMsg.deserialize(body, 0, length) else null
        when (method) {
            "subscriptionStart" -> {
                val signature = msg!!.list("streams").map { (it as? HtsMsg)?.let { s -> "${s.int("index")}:${s.str("type")}" } ?: "" }
                val previous = streamsSignature
                when {
                    previous == null -> {
                        streamsSignature = signature
                        queue.put(Chunk(body, length))
                        return // the queue owns the buffer now
                    }
                    // Tracks can't be added to a prepared player: end the stream so the session
                    // restarts the channel with the new set of streams.
                    previous != signature -> queue.put(EndOfStream)
                }
            }
            "subscriptionStatus" -> onStatus(msg!!.str("subscriptionError"))
            "subscriptionStop" -> {
                msg!!.str("subscriptionError")?.let(onStatus)
                queue.put(IOException("Subscription stopped: ${msg.str("subscriptionError") ?: msg.str("status") ?: "unknown"}"))
            }
        }
        BufferPool.recycle(body)
    }

    override fun onConnectionLost(error: IOException) {
        queue.put(error)
    }

    private companion object {
        const val NO_DATA_TIMEOUT_NS = 15_000_000_000L
    }
}
