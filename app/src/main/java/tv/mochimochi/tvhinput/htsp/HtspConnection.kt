package tv.mochimochi.tvhinput.htsp

import android.util.Log
import tv.mochimochi.tvhinput.TvhConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * An authenticated HTSP connection to tvheadend. Requests are matched to replies by "seq";
 * subscription messages (subscriptionStart, muxpkt, ...) are routed to their listener.
 */
class HtspConnection private constructor(private val socket: Socket) : Closeable {

    interface SubscriptionListener {
        /**
         * Called on the reader thread with the message's method and its encoded body, which is the
         * first [length] bytes of [body]. The listener owns [body] (a [BufferPool] buffer) and
         * should [BufferPool.recycle] it once done.
         */
        fun onMessage(method: String, body: ByteArray, length: Int)

        fun onConnectionLost(error: IOException)
    }

    /** Receives the asynchronous metadata (channelAdd, eventUpdate, ...) of `enableAsyncMetadata`. */
    interface MetadataListener {
        /**
         * Called on the reader thread, in the order the server sent the messages, with the encoded
         * body like [SubscriptionListener.onMessage] (the listener owns [body]). The same thread
         * delivers the stream packets: hand the decoding off, don't do it here.
         */
        fun onMetadata(method: String, body: ByteArray, length: Int)

        fun onConnectionLost(error: IOException)
    }

    /** Set before sending `enableAsyncMetadata`: the server starts its dump right after replying. */
    @Volatile var metadataListener: MetadataListener? = null

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val seq = AtomicInteger()
    private val subscriptionIds = AtomicInteger()
    /** Replies by seq, still encoded: the requester decodes them, never the reader thread. */
    private val pending = ConcurrentHashMap<Int, CompletableFuture<ByteArray>>()
    private val subscriptions = ConcurrentHashMap<Int, SubscriptionListener>()

    private val closed = AtomicBoolean()
    private val closedSignal = CompletableFuture<Unit>()

    val isClosed: Boolean get() = closed.get()

    /** Runs [action] once the connection is closed, for whatever reason (at once if it already is). */
    fun whenClosed(action: () -> Unit) {
        closedSignal.thenRun(action)
    }

    /** When the last message arrived: a connection that went silent is checked with a ping. */
    @Volatile private var lastReceivedNs = System.nanoTime()
    /** Messages read so far, of any kind: lets a subscription tell a stalled stream from a stalled link. */
    @Volatile var messagesReceived = 0L
        private set
    private val pingInFlight = AtomicBoolean()
    private var watchdog: ScheduledFuture<*>? = null

    var serverName = ""
        private set
    var serverVersion = ""
        private set

    private fun start() {
        thread(name = "htsp-reader", isDaemon = true) { readLoop() }
        watchdog = TIMER.scheduleWithFixedDelay(::checkAlive, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * A TCP connection can die without an error (Wi-Fi drop, roaming, the server losing us):
     * reads then just wait forever and every request times out. When nothing has arrived for a
     * while, ask the server for its time; no answer means the connection is dead.
     */
    private fun checkAlive() {
        if (isClosed) return
        if (System.nanoTime() - lastReceivedNs < IDLE_BEFORE_PING_NS) return
        if (!pingInFlight.compareAndSet(false, true)) return
        thread(name = "htsp-ping", isDaemon = true) {
            try {
                request(HtsMsg.of("method" to "getSysTime"), PING_TIMEOUT_MS) // a timeout closes us
            } catch (_: IOException) {
            } finally {
                pingInFlight.set(false)
            }
        }
    }

    /** Declares the connection dead (e.g. a stream stopped arriving): everyone using it gets an error. */
    fun abort(reason: String) = fail(IOException(reason))

    /** Sends a request and waits for its reply; server-side errors are thrown as IOException. */
    fun request(msg: HtsMsg, timeoutMs: Long = 10_000): HtsMsg {
        val id = seq.incrementAndGet()
        msg["seq"] = id
        val reply = CompletableFuture<ByteArray>()
        pending[id] = reply
        try {
            send(msg)
            val bytes = reply.get(timeoutMs, TimeUnit.MILLISECONDS)
            val result = HtsMsg.deserialize(bytes, 0, bytes.size)
            result.str("error")?.let { throw IOException("HTSP ${msg.method}: $it") }
            if (result.int("noaccess") == 1) throw IOException("HTSP access denied: check the user's HTSP streaming rights")
            return result
        } catch (e: TimeoutException) {
            // tvheadend answers every request: no reply means the connection is gone, even if the
            // socket hasn't noticed. Close it so the next user reconnects instead of reusing it.
            val error = IOException("HTSP ${msg.method} timed out")
            fail(error)
            throw error
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException()
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Starts streaming a channel; the listener must be ready before the first packet arrives.
     * [weight] is tvheadend's priority (default 150 for HTSP): a lower one gives up its tuner to
     * any higher subscription that needs it.
     */
    fun subscribe(channelId: Int, listener: SubscriptionListener, weight: Int? = null): Int {
        val id = subscriptionIds.incrementAndGet()
        subscriptions[id] = listener
        try {
            val msg = HtsMsg.of(
                "method" to "subscribe", "channelId" to channelId, "subscriptionId" to id,
                // tvheadend holds a new subscription's packets until it knows all its streams, then
                // sends ~2 s at once; above its default 500 KB queue it drops B and P frames, which
                // made channel starts stutter. Live TV would rather queue than drop.
                "queueDepth" to QUEUE_DEPTH_BYTES,
            )
            if (weight != null) msg["weight"] = weight
            request(msg)
        } catch (e: IOException) {
            subscriptions.remove(id)
            throw e
        }
        return id
    }

    fun unsubscribe(subscriptionId: Int) {
        if (subscriptions.remove(subscriptionId) == null || isClosed) return
        try {
            // Fire and forget: nobody needs to wait for the reply.
            send(HtsMsg.of("method" to "unsubscribe", "subscriptionId" to subscriptionId, "seq" to seq.incrementAndGet()))
        } catch (e: IOException) {
            Log.w(TAG, "unsubscribe failed: ${e.message}")
        }
    }

    @Synchronized
    private fun send(msg: HtsMsg) {
        if (isClosed) throw IOException("HTSP connection closed")
        output.write(msg.serialize())
        output.flush()
    }

    private fun readLoop() {
        val fields = HtsFieldReader()
        try {
            while (!isClosed) {
                val length = input.readInt()
                lastReceivedNs = System.nanoTime()
                messagesReceived++ // only the reader thread writes it
                if (length < 0 || length > MAX_MESSAGE) throw IOException("Bad HTSP message length $length")
                val body = BufferPool.obtain(length)
                input.readFully(body, 0, length)
                // Only look at the routing fields: subscription messages are decoded by their consumer.
                var replyTo = NONE
                var subscriptionId = NONE
                var method: String? = null
                fields.reset(body, 0, length)
                while (fields.next()) {
                    when {
                        fields.nameIs(SEQ) -> replyTo = fields.long()
                        fields.nameIs(SUBSCRIPTION_ID) -> subscriptionId = fields.long()
                        fields.nameIs(METHOD) -> method = fields.method()
                    }
                }
                if (replyTo != NONE) {
                    // Some replies are large (a channel's guide): only copy them here, the requester decodes.
                    pending[replyTo.toInt()]?.complete(body.copyOf(length))
                    BufferPool.recycle(body)
                    continue
                }
                if (subscriptionId == NONE && method != null) {
                    val metadata = metadataListener
                    if (metadata != null) {
                        metadata.onMetadata(method, body, length)
                        continue
                    }
                }
                val listener = if (subscriptionId != NONE) subscriptions[subscriptionId.toInt()] else null
                if (listener != null && method != null) listener.onMessage(method, body, length)
                else BufferPool.recycle(body)
            }
        } catch (e: IOException) {
            fail(e)
        } catch (e: RuntimeException) {
            fail(IOException(e))
        }
    }

    private fun fail(error: IOException) {
        if (!closeSocket()) return
        Log.w(TAG, "HTSP connection lost: ${error.message}")
        pending.values.forEach { it.completeExceptionally(error) }
        subscriptions.values.forEach { it.onConnectionLost(error) }
        subscriptions.clear()
        metadataListener?.onConnectionLost(error)
    }

    override fun close() {
        closeSocket()
    }

    /** True if this call closed it. */
    private fun closeSocket(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        watchdog?.cancel(false)
        try {
            socket.close()
        } catch (_: IOException) {
        }
        closedSignal.complete(Unit)
        return true
    }

    companion object {
        private const val TAG = "HtspConnection"
        private const val HTSP_VERSION = 34
        private const val MAX_MESSAGE = 32 * 1024 * 1024
        private const val NONE = Long.MIN_VALUE
        private val SEQ = HtsFieldReader.name("seq")
        private val SUBSCRIPTION_ID = HtsFieldReader.name("subscriptionId")
        private val METHOD = HtsFieldReader.name("method")
        private const val WATCHDOG_MS = 5_000L
        private const val IDLE_BEFORE_PING_NS = 10_000_000_000L
        private const val QUEUE_DEPTH_BYTES = 4_000_000L
        private const val PING_TIMEOUT_MS = 5_000L
        private val TIMER = Executors.newSingleThreadScheduledExecutor { Thread(it, "htsp-watchdog").apply { isDaemon = true } }

        /** Opens a connection, says hello and authenticates when a username is configured. */
        fun connect(config: TvhConfig): HtspConnection {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(config.host, config.htspPort), 5000)
                socket.tcpNoDelay = true
                socket.keepAlive = true
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            val connection = HtspConnection(socket)
            connection.start()
            try {
                val hello = connection.request(
                    HtsMsg.of(
                        "method" to "hello",
                        "htspversion" to HTSP_VERSION,
                        "clientname" to "Tvheadend TV Input",
                        "clientversion" to "0.1",
                    )
                )
                connection.serverName = hello.str("servername").orEmpty()
                connection.serverVersion = hello.str("serverversion").orEmpty()
                if (config.username.isNotEmpty()) {
                    val digest = MessageDigest.getInstance("SHA-1").run {
                        update(config.password.toByteArray(Charsets.UTF_8))
                        update(hello.bin("challenge") ?: ByteArray(0))
                        digest()
                    }
                    connection.request(
                        HtsMsg.of("method" to "authenticate", "username" to config.username, "digest" to digest)
                    )
                }
            } catch (e: IOException) {
                connection.close()
                throw e
            }
            return connection
        }
    }
}
