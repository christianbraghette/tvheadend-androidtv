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
    private var chunk: ByteArray? = null
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
        while (current == null || chunkPos >= current.size) {
            if (Thread.interrupted()) throw InterruptedIOException()
            when (val item = queue.poll(100, TimeUnit.MILLISECONDS)) {
                null -> {
                    // tvheadend sends status messages every second while subscribed: total silence
                    // means a dead connection or stream, so fail and let the player resubscribe.
                    if (System.nanoTime() - lastMessageNs > NO_DATA_TIMEOUT_NS) {
                        throw IOException("No data from tvheadend for ${NO_DATA_TIMEOUT_NS / 1_000_000_000} s")
                    }
                    continue
                }
                is IOException -> throw item
                EndOfStream -> return C.RESULT_END_OF_INPUT
                is ByteArray -> {
                    current = item
                    chunk = item
                    chunkPos = 0
                }
            }
        }
        val n = minOf(length, current.size - chunkPos)
        System.arraycopy(current, chunkPos, buffer, offset, n)
        chunkPos += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        connection?.unsubscribe(subscriptionId)
        connection = null
        queue.clear()
        chunk = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    override fun onMessage(msg: HtsMsg, body: ByteArray) {
        lastMessageNs = System.nanoTime()
        when (msg.method) {
            "muxpkt" -> queue.put(frame(body))
            "subscriptionStart" -> {
                val signature = msg.list("streams").map { (it as? HtsMsg)?.let { s -> "${s.int("index")}:${s.str("type")}" } ?: "" }
                val previous = streamsSignature
                when {
                    previous == null -> {
                        streamsSignature = signature
                        queue.put(frame(body))
                    }
                    // Tracks can't be added to a prepared player: end the stream so the session
                    // restarts the channel with the new set of streams.
                    previous != signature -> queue.put(EndOfStream)
                }
            }
            "subscriptionStatus" -> onStatus(msg.str("subscriptionError"))
            "subscriptionStop" -> {
                msg.str("subscriptionError")?.let(onStatus)
                queue.put(IOException("Subscription stopped: ${msg.str("subscriptionError") ?: msg.str("status") ?: "unknown"}"))
            }
        }
    }

    override fun onConnectionLost(error: IOException) {
        queue.put(error)
    }

    private companion object {
        const val NO_DATA_TIMEOUT_NS = 15_000_000_000L
    }

    private fun frame(body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 4)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }
}
