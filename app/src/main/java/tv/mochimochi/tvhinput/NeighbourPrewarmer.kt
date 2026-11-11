package tv.mochimochi.tvhinput

import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import tv.mochimochi.tvhinput.htsp.BufferPool
import tv.mochimochi.tvhinput.htsp.HtsMsg
import tv.mochimochi.tvhinput.htsp.HtspConnection
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the channels next to the current one (by number) tuned in tvheadend, so zapping to them
 * skips the retune, which is most of the zap time. Only channels on a different multiplex than
 * the current one are worth it: channels on the same multiplex are already tuned.
 *
 * The subscriptions use a very low weight, so tvheadend hands their tuners to any real viewer or
 * recording that needs one, and all their streams are switched off once they start: the tuner
 * stays locked but nothing is sent over the network.
 */
class NeighbourPrewarmer(
    private val context: Context,
    private val client: TvhClient,
    private val connections: () -> HtspConnection,
) {
    private class Prewarm(val uuid: String, val connection: HtspConnection, val subscriptionId: Int)

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "htsp-prewarm").apply { isDaemon = true } }
    private val active = HashMap<String, Prewarm>() // executor thread only
    private var idleDrop: ScheduledFuture<*>? = null // executor thread only
    @Volatile private var released = false

    /**
     * The current channel is playing: (re)plan which neighbours to keep tuned. Zapping comes in
     * bursts, so once the viewer stays on a channel the pre-tunes are dropped again: they would
     * otherwise hold tuners for hours and block tvheadend's own low-priority work (EPG grabbing,
     * multiplex scans) on them.
     */
    fun update(currentUuid: String) {
        if (released) return
        post {
            idleDrop?.cancel(false)
            plan(currentUuid)
            idleDrop = try {
                executor.schedule({
                    if (active.isNotEmpty()) Log.i(TAG, "No zapping for ${IDLE_DROP_MS / 1000} s: dropping pre-tunes")
                    dropAll()
                }, IDLE_DROP_MS, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                null // released meanwhile: dropAll is queued already
            }
        }
    }

    fun release() {
        released = true
        post(::dropAll)
        executor.shutdown()
    }

    /**
     * Runs [block] on the executor, or not at all once released. Also used from the HTSP reader
     * thread, where a RejectedExecutionException would be taken for a dead connection and close
     * the session's own stream.
     */
    private fun post(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Ends one pre-tune; the next zap plans again (and may try it again). */
    private fun giveUp(uuid: String, subscriptionId: Int) {
        val prewarm = active[uuid]?.takeIf { it.subscriptionId == subscriptionId } ?: return
        active.remove(uuid)
        prewarm.connection.unsubscribe(subscriptionId)
    }

    private fun dropAll() {
        idleDrop?.cancel(false)
        active.values.forEach { it.connection.unsubscribe(it.subscriptionId) }
        active.clear()
    }

    private fun plan(currentUuid: String) {
        try {
            val connection = connections()
            active.values.removeAll { it.connection !== connection || connection.isClosed }
            val channels = channelOrder()
            val index = channels.indexOf(currentUuid)
            if (index < 0) {
                Log.i(TAG, "Current channel $currentUuid not among our ${channels.size} channels")
                return
            }
            val currentMux = muxOf(connection, currentUuid)
            val wanted = LinkedHashMap<String, String>() // uuid -> mux
            for (neighbour in listOf(channels.getOrNull(index + 1), channels.getOrNull(index - 1))) {
                if (neighbour == null) continue
                val mux = muxOf(connection, neighbour)
                val skip = when {
                    mux == null -> "unknown multiplex"
                    mux == currentMux -> "same multiplex"
                    mux in wanted.values -> "multiplex already pre-tuned"
                    else -> null
                }
                if (skip != null) {
                    Log.i(TAG, "Not pre-tuning ${names[neighbour] ?: neighbour}: $skip ($mux)")
                    continue
                }
                wanted[neighbour] = mux!!
            }
            // Stop the ones no longer needed (including the channel now playing for real: its own
            // subscription keeps the multiplex tuned).
            active.keys.filter { it !in wanted }.forEach { uuid ->
                active.remove(uuid)?.let { connection.unsubscribe(it.subscriptionId) }
            }
            for ((uuid, mux) in wanted) {
                if (uuid in active || released) continue
                val listener = Listener(connection, uuid)
                val id = connection.subscribe(client.htspChannelId(uuid), listener, PREWARM_WEIGHT)
                listener.subscriptionId = id
                active[uuid] = Prewarm(uuid, connection, id)
                Log.i(TAG, "Pre-tuning ${names[uuid] ?: uuid} on $mux")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Pre-tuning failed: ${e.message}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pre-tuning failed", e)
        }
    }

    /** Our channels in number order, which is how TV apps step through them. */
    private fun channelOrder(): List<String> {
        val rows = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            TvContract.buildChannelsUriForInput(TvhInputService.inputId(context)),
            arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uuid = c.getString(0) ?: continue
                rows += uuid to (c.getString(1) ?: "")
                c.getString(2)?.let { names[uuid] = it }
            }
        }
        return rows.sortedWith(compareBy({ it.second.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.second })).map { it.first }
    }

    /**
     * The multiplex a channel is on. HTSP names a service "network/multiplex/service": everything
     * before the last part identifies the multiplex.
     */
    private fun muxOf(connection: HtspConnection, uuid: String): String? {
        muxes[uuid]?.let { return it }
        val info = connection.request(HtsMsg.of("method" to "getChannel", "channelId" to client.htspChannelId(uuid)))
        val service = info.list("services").firstNotNullOfOrNull { (it as? HtsMsg)?.str("name") }
        Log.d(TAG, "${names[uuid] ?: uuid}: service $service")
        if (service == null) return null
        val mux = service.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: return null
        muxes[uuid] = mux
        return mux
    }

    /** Throws away everything a pre-tune subscription sends, after switching its streams off. */
    private inner class Listener(private val connection: HtspConnection, private val uuid: String) :
        HtspConnection.SubscriptionListener {
        @Volatile var subscriptionId = 0
        private var packets = 0
        private var noTuner = 0
        private var lastError: String? = null

        override fun onMessage(method: String, body: ByteArray, length: Int) {
            when (method) {
                "subscriptionStart" -> {
                    val streams = HtsMsg.deserialize(body, 0, length).list("streams")
                        .mapNotNull { (it as? HtsMsg)?.long("index") }
                    post { disableStreams(streams) }
                }
                "muxpkt" -> if (++packets == PACKETS_TO_LOG) {
                    Log.w(TAG, "${names[uuid] ?: uuid} still streaming after $packets packets: filter not supported?")
                }
                "subscriptionStatus", "subscriptionStop" -> {
                    val error = HtsMsg.deserialize(body, 0, length).str("subscriptionError")
                    if (error == "noFreeAdapter") {
                        // tvheadend retries every 2 s; a tuner often frees up right after a zap,
                        // but don't keep a pending request around beyond that.
                        if (++noTuner == MAX_NO_TUNER) {
                            Log.i(TAG, "No free tuner for ${names[uuid] ?: uuid}: not pre-tuning it")
                            val id = subscriptionId
                            post { giveUp(uuid, id) }
                        }
                    } else if (error != null && error != lastError) {
                        Log.i(TAG, "Pre-tune of ${names[uuid] ?: uuid}: $error")
                    }
                    lastError = error
                }
            }
            BufferPool.recycle(body)
        }

        override fun onConnectionLost(error: IOException) {}

        private fun disableStreams(indexes: List<Long>) {
            if (indexes.isEmpty() || connection.isClosed) return
            try {
                connection.request(
                    // Default timeout: a timed-out request now closes the whole connection.
                    HtsMsg.of("method" to "subscriptionFilterStream", "subscriptionId" to subscriptionId, "disable" to indexes),
                )
                Log.i(TAG, "Pre-tune of ${names[uuid] ?: uuid} started; streams off")
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't switch off streams of ${names[uuid] ?: uuid}: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "NeighbourPrewarmer"
        /** Far below HTSP viewing (150) and recordings (300+): never takes a tuner from anyone. */
        const val PREWARM_WEIGHT = 10
        const val PACKETS_TO_LOG = 500
        const val IDLE_DROP_MS = 120_000L
        const val MAX_NO_TUNER = 3

        // Channel facts don't change during a session; share them across sessions.
        val muxes = ConcurrentHashMap<String, String>()
        val names = ConcurrentHashMap<String, String>()
    }
}
