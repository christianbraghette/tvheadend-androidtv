package tv.mochimochi.tvhinput

import android.util.Log
import tv.mochimochi.tvhinput.htsp.BufferPool
import tv.mochimochi.tvhinput.htsp.HtsMsg
import tv.mochimochi.tvhinput.htsp.HtspConnection
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What a sync writes: tvheadend's channels, which of them are radio, and their guide. */
class GuideSnapshot(
    val channels: List<TvhChannel>,
    /** Uuids of the radio channels, or null when the source can't tell (then the ones found at playback are kept). */
    val radio: Set<String>?,
    val events: List<TvhEvent>,
    /**
     * Set when [events] leaves out what already ended (a resync gets programmes from the one on
     * air onwards): stored programmes that ended before this time are kept, not deleted.
     */
    val keepEndedBefore: Long? = null,
)

/**
 * tvheadend's channels and guide over HTSP (`enableAsyncMetadata`). The server first sends
 * everything it has, ending with `initialSyncCompleted`, then every change for as long as the
 * connection stays open. Deletions are only ever sent live, so each new connection starts with a
 * full comparison ([Listener.onSnapshot]).
 *
 * The connection's reader thread may also be delivering a channel's stream, so it only copies the
 * messages: they are decoded on a background thread, which makes all [Listener] calls, in the
 * server's order.
 */
class HtspGuide(private val client: TvhClient, private val listener: Listener) : HtspConnection.MetadataListener {

    interface Listener {
        fun onSnapshot(snapshot: GuideSnapshot)
        fun onChannel(channel: TvhChannel, radio: Boolean)
        fun onChannelDeleted(uuid: String)
        fun onEvent(event: TvhEvent)
        fun onEventDeleted(eventId: Long)
        fun onConnectionLost(error: IOException)
    }

    private data class Channel(val uuid: String, val major: Long, val minor: Long, val name: String, val icon: String?, val radio: Boolean)

    private val channels = HashMap<Int, Channel>() // HTSP channel id -> channel
    /** Numbers given to channels without one in the initial dump, kept for their live updates. */
    private val fallbackNumbers = HashMap<String, String>()
    // All state below belongs to the DECODER thread.
    private val initialEvents = ArrayList<TvhEvent>()
    private var initialDone = false
    /** Uuids by HTSP id from the HTTP API, for servers whose HTSP doesn't send channelIdStr. */
    private var httpUuids: Map<Int, String>? = null
    @Volatile private var connection: HtspConnection? = null

    /**
     * Asks [connection], a fresh one, for the metadata. It stays this guide's until it closes;
     * [Listener.onConnectionLost] tells when that happens.
     */
    fun attach(connection: HtspConnection) {
        this.connection = connection
        connection.metadataListener = this
        connection.request(HtsMsg.of("method" to "enableAsyncMetadata", "epg" to 1))
    }

    /**
     * Fetches the whole guide again on the same connection and passes it to [Listener.onSnapshot],
     * which puts right whatever a live change failed to write. Channel by channel (`getEvents`),
     * so no reply is huge. The live changes that arrive meanwhile wait and are applied after it,
     * in order. Completes with false if it couldn't be done (the stored guide is then untouched).
     *
     * `getEvents` needs HTSP streaming rights, which the live guide doesn't: without them the
     * events come from the HTTP API instead.
     */
    fun resync(): CompletableFuture<Boolean> {
        val done = CompletableFuture<Boolean>()
        try {
            post {
                try {
                    done.complete(doResync())
                } catch (e: IOException) {
                    Log.w(TAG, "Guide resync failed: ${e.message}")
                    done.complete(false)
                } finally {
                    done.complete(false) // a RuntimeException, logged by post
                }
            }
        } catch (_: RejectedExecutionException) {
            done.complete(false)
        }
        return done
    }

    private fun doResync(): Boolean {
        // Before the initial snapshot there's nothing to resync: that snapshot is on its way.
        if (!initialDone) return false
        val c = connection?.takeIf { !it.isClosed } ?: return false
        val since = System.currentTimeMillis()
        val events = try {
            eventsOverHtsp(c)
        } catch (e: HtspRefusedException) {
            if (c.isClosed) throw e
            Log.i(TAG, "No guide over getEvents (${e.message}): using the HTTP API")
            client.events()
        }
        val snapshot = snapshot(events, keepEndedBefore = since)
        Log.i(TAG, "Resync: ${snapshot.channels.size} channels, ${snapshot.events.size} events from now on")
        listener.onSnapshot(snapshot)
        return true
    }

    private class HtspRefusedException(message: String) : IOException(message)

    private fun eventsOverHtsp(c: HtspConnection): List<TvhEvent> {
        val events = ArrayList<TvhEvent>()
        for (id in channels.keys.toList()) {
            val reply = try {
                c.request(HtsMsg.of("method" to "getEvents", "channelId" to id), GET_EVENTS_TIMEOUT_MS)
            } catch (e: IOException) {
                // tvheadend's error replies (no rights, channel gone) leave the connection open.
                if (!c.isClosed) throw HtspRefusedException(e.message.orEmpty())
                throw e
            }
            // Always present in a successful reply, empty for a channel without guide.
            val list = reply["events"] as? List<*> ?: throw IOException("getEvents reply without events")
            list.mapNotNullTo(events) { (it as? HtsMsg)?.let(::parseEvent) }
        }
        return events
    }

    override fun onMetadata(method: String, body: ByteArray, length: Int) {
        // A copy of the exact size: pooled buffers are larger, and a whole guide may be queued here.
        val copy = body.copyOf(length)
        BufferPool.recycle(body)
        post { handle(method, HtsMsg.deserialize(copy, 0, copy.size)) }
    }

    // After the metadata already queued, so a listener sees everything that arrived first.
    override fun onConnectionLost(error: IOException) = post { listener.onConnectionLost(error) }

    private fun post(block: () -> Unit) = DECODER.execute {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't process guide data", e)
        }
    }

    private fun handle(method: String, msg: HtsMsg) {
        when (method) {
            "channelAdd", "channelUpdate" -> {
                val id = msg.int("channelId") ?: return
                val previous = channels[id]
                val channel = parseChannel(id, msg, previous) ?: return
                channels[id] = channel
                // tvheadend resends channels on every now/next change: only pass on real changes.
                if (initialDone && channel != previous) listener.onChannel(toTvhChannel(channel, fallbackNumbers[channel.uuid].orEmpty()), channel.radio)
            }
            "channelDelete" -> {
                val channel = msg.int("channelId")?.let { channels.remove(it) } ?: return
                if (initialDone) listener.onChannelDeleted(channel.uuid)
            }
            "eventAdd", "eventUpdate" -> {
                val event = parseEvent(msg) ?: return
                if (initialDone) listener.onEvent(event) else initialEvents += event
            }
            "eventDelete" -> if (initialDone) msg.long("eventId")?.let(listener::onEventDeleted)
            "initialSyncCompleted" -> {
                initialDone = true
                val snapshot = snapshot(ArrayList(initialEvents))
                initialEvents.clear()
                initialEvents.trimToSize()
                Log.i(
                    TAG,
                    "Initial sync: ${snapshot.channels.size} channels (${snapshot.channels.count { it.icon != null }} with a logo), " +
                        "${snapshot.events.size} events (${snapshot.events.count { it.image != null }} with an image)",
                )
                listener.onSnapshot(snapshot)
            }
        }
    }

    private fun snapshot(events: List<TvhEvent>, keepEndedBefore: Long? = null): GuideSnapshot {
        val sorted = channels.values.sortedWith(compareBy({ it.major }, { it.minor }, { it.name }))
        val list = sorted.mapIndexed { index, c ->
            // Like the HTTP API sync: channels without a number are numbered by position.
            val fallback = (index + 1).toString()
            if (c.major <= 0) fallbackNumbers[c.uuid] = fallback
            toTvhChannel(c, fallback)
        }
        val radio = channels.values.filter { it.radio }.mapTo(HashSet()) { it.uuid }
        return GuideSnapshot(list, radio, events, keepEndedBefore)
    }

    private fun toTvhChannel(c: Channel, fallbackNumber: String) = TvhChannel(
        uuid = c.uuid,
        name = c.name,
        number = when {
            c.major <= 0 -> fallbackNumber
            c.minor > 0 -> "${c.major}.${c.minor}"
            else -> c.major.toString()
        },
        icon = c.icon,
    )

    /**
     * A channelAdd carries the whole channel, a channelUpdate only the fields that changed (often
     * just the now/next event ids): fields it leaves out keep their [previous] value.
     */
    private fun parseChannel(id: Int, msg: HtsMsg, previous: Channel?): Channel? {
        val uuid = msg.str("channelIdStr") ?: previous?.uuid ?: uuidFromHttp(id) ?: return null
        val services = msg["services"] as? List<*>
        val content = services?.mapNotNull { (it as? HtsMsg)?.int("content") }
        val icon = msg.str("channelIcon")
        return Channel(
            uuid = uuid,
            major = msg.long("channelNumber") ?: previous?.major ?: 0,
            minor = msg.long("channelNumberMinor") ?: previous?.minor ?: 0,
            name = msg.str("channelName") ?: previous?.name.orEmpty(),
            icon = if (icon != null) icon.takeIf { it.isNotBlank() } else previous?.icon,
            radio = if (content != null) content.isNotEmpty() && content.all { it == SERVICE_CONTENT_RADIO }
                else previous?.radio ?: false,
        )
    }

    private fun uuidFromHttp(id: Int): String? {
        val map = httpUuids ?: try {
            client.channels().associate { client.htspChannelId(it.uuid) to it.uuid }
        } catch (e: IOException) {
            Log.w(TAG, "No channel uuids from HTSP or HTTP: ${e.message}")
            emptyMap()
        }.also { httpUuids = it }
        return map[id]
    }

    private fun parseEvent(msg: HtsMsg): TvhEvent? {
        val eventId = msg.long("eventId") ?: return null
        val channel = msg.int("channelId")?.let { channels[it] } ?: return null
        val start = msg.long("start") ?: return null
        val stop = msg.long("stop") ?: return null
        return TvhEvent(
            eventId = eventId,
            channelUuid = channel.uuid,
            startMs = start * 1000,
            stopMs = stop * 1000,
            title = msg.str("title").orEmpty(),
            subtitle = msg.text("subtitle"),
            summary = msg.text("summary"),
            description = msg.text("description"),
            // HTSP sends only the first genre, as the full DVB content code like the HTTP API.
            genres = listOfNotNull(msg.int("contentType")?.takeIf { it > 0 }),
            image = msg.text("image"),
            season = msg.int("seasonNumber")?.takeIf { it > 0 },
            episode = msg.int("episodeNumber")?.takeIf { it > 0 },
            ageRating = msg.int("ageRating")?.takeIf { it > 0 },
        )
    }

    private fun HtsMsg.text(name: String) = str(name)?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "HtspGuide"
        private const val SERVICE_CONTENT_RADIO = 2
        private const val DUMP_TIMEOUT_S = 180L
        /** Generous: on the shared connection a timed-out request closes it, playback included. */
        private const val GET_EVENTS_TIMEOUT_MS = 30_000L

        /**
         * Decodes every guide's messages, in arrival order, at background priority: on a busy
         * Chromecast decoding a whole guide at normal priority starves playback.
         */
        private val DECODER = Executors.newSingleThreadExecutor(backgroundThreads("tvh-guide-decode"))

        /**
         * One full dump over a connection of its own, closed afterwards: for syncs while the TV
         * input isn't running (setup, the periodic worker), when there is no shared connection.
         */
        fun fetch(client: TvhClient): GuideSnapshot {
            val result = CompletableFuture<GuideSnapshot>()
            val guide = HtspGuide(client, object : Listener {
                override fun onSnapshot(snapshot: GuideSnapshot) { result.complete(snapshot) }
                override fun onChannel(channel: TvhChannel, radio: Boolean) {}
                override fun onChannelDeleted(uuid: String) {}
                override fun onEvent(event: TvhEvent) {}
                override fun onEventDeleted(eventId: Long) {}
                override fun onConnectionLost(error: IOException) { result.completeExceptionally(error) }
            })
            HtspConnection.connect(client.config).use { connection ->
                guide.attach(connection)
                return try {
                    result.get(DUMP_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    throw IOException("HTSP guide dump timed out")
                } catch (e: ExecutionException) {
                    throw e.cause as? IOException ?: IOException(e.cause)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException()
                }
            }
        }
    }
}
