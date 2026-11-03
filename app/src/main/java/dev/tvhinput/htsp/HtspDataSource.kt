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
class HtspDataSource(private val connections: () -> HtspConnection) :
    BaseDataSource(/* isNetwork= */ true), HtspConnection.SubscriptionListener {

    class Factory(private val connections: () -> HtspConnection) : DataSource.Factory {
        override fun createDataSource(): DataSource = HtspDataSource(connections)
    }

    private val queue = LinkedBlockingQueue<Any>()
    private var connection: HtspConnection? = null
    private var subscriptionId = 0
    private var uri: Uri? = null
    private var opened = false
    private var chunk: ByteArray? = null
    private var chunkPos = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val channelId = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Invalid HTSP uri ${dataSpec.uri}")
        queue.clear()
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
                null -> continue
                is IOException -> throw item
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
        when (msg.method) {
            "subscriptionStart", "muxpkt" -> queue.put(frame(body))
            "subscriptionStop" -> queue.put(
                IOException("Subscription stopped: ${msg.str("subscriptionError") ?: msg.str("status") ?: "unknown"}")
            )
        }
    }

    override fun onConnectionLost(error: IOException) {
        queue.put(error)
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
