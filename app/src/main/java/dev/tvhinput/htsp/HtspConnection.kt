package dev.tvhinput.htsp

import android.util.Log
import dev.tvhinput.TvhConfig
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * An authenticated HTSP connection to tvheadend. Requests are matched to replies by "seq";
 * subscription messages (subscriptionStart, muxpkt, ...) are routed to their listener.
 */
class HtspConnection private constructor(private val socket: Socket) : Closeable {

    interface SubscriptionListener {
        /** Called on the reader thread with the decoded message and its raw body. */
        fun onMessage(msg: HtsMsg, body: ByteArray)

        fun onConnectionLost(error: IOException)
    }

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val seq = AtomicInteger()
    private val subscriptionIds = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<HtsMsg>>()
    private val subscriptions = ConcurrentHashMap<Int, SubscriptionListener>()

    @Volatile
    var isClosed = false
        private set

    var serverName = ""
        private set
    var serverVersion = ""
        private set

    private fun start() {
        thread(name = "htsp-reader", isDaemon = true) { readLoop() }
    }

    /** Sends a request and waits for its reply; server-side errors are thrown as IOException. */
    fun request(msg: HtsMsg, timeoutMs: Long = 10_000): HtsMsg {
        val id = seq.incrementAndGet()
        msg["seq"] = id
        val reply = CompletableFuture<HtsMsg>()
        pending[id] = reply
        try {
            send(msg)
            val result = reply.get(timeoutMs, TimeUnit.MILLISECONDS)
            result.str("error")?.let { throw IOException("HTSP ${msg.method}: $it") }
            if (result.int("noaccess") == 1) throw IOException("HTSP access denied: check the user's HTSP streaming rights")
            return result
        } catch (e: TimeoutException) {
            throw IOException("HTSP ${msg.method} timed out")
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException()
        } finally {
            pending.remove(id)
        }
    }

    /** Starts streaming a channel; the listener must be ready before the first packet arrives. */
    fun subscribe(channelId: Int, listener: SubscriptionListener): Int {
        val id = subscriptionIds.incrementAndGet()
        subscriptions[id] = listener
        try {
            request(HtsMsg.of("method" to "subscribe", "channelId" to channelId, "subscriptionId" to id))
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
        try {
            while (!isClosed) {
                val length = input.readInt()
                if (length < 0 || length > MAX_MESSAGE) throw IOException("Bad HTSP message length $length")
                val body = ByteArray(length)
                input.readFully(body)
                val msg = HtsMsg.deserialize(body)
                val replyTo = msg.int("seq")
                if (replyTo != null) {
                    pending[replyTo]?.complete(msg)
                    continue
                }
                msg.int("subscriptionId")?.let { subscriptions[it]?.onMessage(msg, body) }
            }
        } catch (e: IOException) {
            fail(e)
        } catch (e: RuntimeException) {
            fail(IOException(e))
        }
    }

    private fun fail(error: IOException) {
        if (isClosed) return
        Log.w(TAG, "HTSP connection lost: ${error.message}")
        close()
        pending.values.forEach { it.completeExceptionally(error) }
        subscriptions.values.forEach { it.onConnectionLost(error) }
        subscriptions.clear()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val TAG = "HtspConnection"
        private const val HTSP_VERSION = 34
        private const val MAX_MESSAGE = 32 * 1024 * 1024

        /** Opens a connection, says hello and authenticates when a username is configured. */
        fun connect(config: TvhConfig): HtspConnection {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(config.host, config.htspPort), 5000)
                socket.tcpNoDelay = true
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
