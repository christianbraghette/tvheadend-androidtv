package dev.tvhinput.htsp

import android.net.Uri
import android.util.Log
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
import kotlin.concurrent.thread

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
    /** Set on the loading thread, also read on the connection's reader thread (onMessage). */
    @Volatile private var connection: HtspConnection? = null
    private var subscriptionId = 0
    private var uri: Uri? = null
    private var opened = false
    /** One subscription message: the first [length] bytes of a [BufferPool] buffer. */
    private class Chunk(val data: ByteArray, val length: Int)

    private var chunk: Chunk? = null
    /** Read position in [chunk]: -4..-1 while emitting its length header, then the body offset. */
    private var chunkPos = 0
    // Stall detection. lastMessageNs and receivedPacket are written by the reader thread and read by
    // the loading thread; lastMessageCount and health belong to the reader thread, probedFor to the
    // loading thread. open() resets them before subscribing.
    @Volatile private var lastMessageNs = 0L
    @Volatile private var receivedPacket = false
    private var lastMessageCount = 0L
    /** [lastMessageNs] of the stall last probed, so each stall is probed once. */
    private var probedFor = 0L
    @Volatile private var health = StreamHealth("")

    /**
     * Logs tvheadend's view of the stream: the tuner's signal (signalStatus) and the
     * subscription's queue (queueStatus), both sent about once a second. Changes that matter
     * (lock lost, uncorrected blocks, dropped packets) are logged as they happen, the rest as a
     * periodic summary, so a stutter in the log can be matched with reception or server trouble.
     */
    private class StreamHealth(private val name: String) {
        private var feStatus: String? = null
        private var unc = -1L
        private var ber = -1L
        private var drops: Triple<Long, Long, Long>? = null
        private var lastSummaryNs = 0L
        private var lastSignal = ""
        private var lastQueue = ""

        fun onSignal(msg: HtsMsg) {
            val status = msg.str("feStatus")
            val snr = msg.long("feSNR")
            val signal = msg.long("feSignal")
            val newBer = msg.long("feBER") ?: 0
            val newUnc = msg.long("feUNC") ?: 0
            lastSignal = "status=$status snr=${scaled(snr, msg.long("feSNRScale"), "dB")} " +
                "signal=${scaled(signal, msg.long("feSignalScale"), "dBm")} ber=$newBer unc=$newUnc"
            when {
                status != feStatus && feStatus != null -> Log.w(TAG, "$name signal: $lastSignal (was $feStatus)")
                // feUNC is a running count: only an increase is news.
                unc >= 0 && newUnc > unc -> Log.w(TAG, "$name ${newUnc - unc} new uncorrected blocks: $lastSignal")
                ber >= 0 && newBer > 0 && ber == 0L -> Log.i(TAG, "$name bit errors: $lastSignal")
            }
            feStatus = status
            unc = newUnc
            ber = newBer
            summary()
        }

        fun onQueue(msg: HtsMsg) {
            val now = Triple(msg.long("Bdrops") ?: 0, msg.long("Pdrops") ?: 0, msg.long("Idrops") ?: 0)
            val delayMs = (msg.long("delay") ?: 0) / 1000
            lastQueue = "packets=${msg.long("packets")} bytes=${msg.long("bytes")} delay=${delayMs}ms " +
                "drops B/P/I=${now.first}/${now.second}/${now.third}"
            val before = drops
            if (before != null && now != before) Log.w(TAG, "$name tvheadend dropped packets: $lastQueue")
            drops = now
            summary()
        }

        private fun summary() {
            val now = System.nanoTime()
            if (now - lastSummaryNs < SUMMARY_INTERVAL_NS) return
            lastSummaryNs = now
            Log.d(TAG, "$name health: $lastSignal | $lastQueue")
        }

        /** tvheadend's scale: 1 = relative 0..65535, 2 = 0.001 units; otherwise unknown. */
        private fun scaled(value: Long?, scale: Long?, unit: String): String = when {
            value == null -> "?"
            scale == 2L -> "%.1f%s".format(value / 1000.0, unit)
            scale == 1L -> "${value * 100 / 65535}%"
            else -> value.toString()
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val channelId = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Invalid HTSP uri ${dataSpec.uri}")
        queue.clear()
        streamsSignature = null
        receivedPacket = false
        lastMessageCount = 0
        probedFor = 0
        health = StreamHealth("channel $channelId")
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
            // Media3 cancels a load by interrupting this thread and expects an IOException back.
            val item = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
            when (item) {
                null -> {
                    probeIfStalled()
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

    /**
     * Once per stall, ping tvheadend on the same connection: a quick answer means the link and
     * the server are fine and only this stream stopped (tuner input), no answer means the
     * connection itself (network, server) is stuck.
     */
    private fun probeIfStalled() {
        val last = lastMessageNs
        if (!receivedPacket || System.nanoTime() - last < ARRIVAL_GAP_NS || probedFor == last) return
        probedFor = last
        val c = connection ?: return
        thread(name = "htsp-probe", isDaemon = true) {
            val start = System.nanoTime()
            try {
                c.request(HtsMsg.of("method" to "getSysTime"), PROBE_TIMEOUT_MS)
                val ms = (System.nanoTime() - start) / 1_000_000
                val stillStalled = lastMessageNs == last
                Log.w(TAG, "Stream stalled; tvheadend answered a ping in $ms ms" +
                    if (stillStalled) ": connection fine, tvheadend isn't sending this stream" else "")
            } catch (e: Exception) {
                Log.w(TAG, "Stream stalled; tvheadend didn't answer a ping within $PROBE_TIMEOUT_MS ms: connection or server stuck (${e.message})")
            }
        }
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
        val now = System.nanoTime()
        // A pause in what arrives (not in the stream's timestamps) points at the network or server.
        val received = connection?.messagesReceived ?: 0
        if (receivedPacket && now - lastMessageNs > ARRIVAL_GAP_NS) {
            // Other messages on the same connection meanwhile (pings, pre-tune subscriptions) mean
            // the network was fine and tvheadend stopped sending this stream: its tuner input stalled.
            val others = received - lastMessageCount - 1
            Log.w(
                TAG,
                "Nothing received for this stream for ${(now - lastMessageNs) / 1_000_000} ms; " +
                    if (others > 0) "$others other messages arrived meanwhile: stalled in tvheadend"
                    else "nothing else arrived either: connection or server stalled",
            )
        }
        lastMessageNs = now
        lastMessageCount = received
        // muxpkt (nearly all traffic) goes to HtspExtractor undecoded; only the rare control
        // messages are decoded here.
        if (method == "muxpkt") {
            receivedPacket = true
            queue.put(Chunk(body, length))
            return
        }
        val msg = if (method.startsWith("subscription") || method in STATUS_METHODS) HtsMsg.deserialize(body, 0, length) else null
        when (method) {
            "signalStatus" -> health.onSignal(msg!!)
            "queueStatus" -> health.onQueue(msg!!)
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
        const val TAG = "HtspStream"
        const val NO_DATA_TIMEOUT_NS = 15_000_000_000L
        /** tvheadend streams continuously: a pause this long in arrivals is worth a log line. */
        const val ARRIVAL_GAP_NS = 700_000_000L
        const val SUMMARY_INTERVAL_NS = 30_000_000_000L
        const val PROBE_TIMEOUT_MS = 5_000L
        val STATUS_METHODS = setOf("signalStatus", "queueStatus")
    }
}
