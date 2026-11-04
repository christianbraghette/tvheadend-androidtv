package dev.tvhinput

import android.util.Log
import dev.tvhinput.htsp.HtsMsg
import dev.tvhinput.htsp.HtspConnection
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What a sync writes: tvheadend's channels, which of them are radio, and their guide. */
class GuideSnapshot(
    val channels: List<TvhChannel>,
    /** Uuids of the radio channels, or null when the source can't tell (then the ones found at playback are kept). */
    val radio: Set<String>?,
    val events: List<TvhEvent>,
)

/**
 * tvheadend's channels and guide over HTSP (`enableAsyncMetadata`). The server first sends
 * everything it has, ending with `initialSyncCompleted`, then every change for as long as the
 * connection stays open. Deletions are only ever sent live, so each new connection starts with a
 * full comparison ([Listener.onSnapshot]).
 *
 * All [Listener] calls come from the connection's reader thread, in the server's order.
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
    private val initialEvents = ArrayList<TvhEvent>()
    @Volatile private var initialDone = false
    private var readerLowered = false // reader thread only
    /** Uuids by HTSP id from the HTTP API, for servers whose HTSP doesn't send channelIdStr. */
    private var httpUuids: Map<Int, String>? = null

    /** Connects and asks for the metadata; the caller closes the returned connection to stop. */
    fun start(): HtspConnection {
        val connection = HtspConnection.connect(client.config)
        try {
            connection.metadataListener = this
            connection.request(HtsMsg.of("method" to "enableAsyncMetadata", "epg" to 1))
        } catch (e: IOException) {
            connection.close()
            throw e
        }
        return connection
    }

    override fun onMetadata(method: String, msg: HtsMsg) {
        // This connection's reader decodes the whole guide: keep it out of playback's way.
        if (!readerLowered) {
            readerLowered = true
            lowerCurrentThreadPriority()
        }
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
                val snapshot = snapshot()
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

    override fun onConnectionLost(error: IOException) = listener.onConnectionLost(error)

    private fun snapshot(): GuideSnapshot {
        val sorted = channels.values.sortedWith(compareBy({ it.major }, { it.minor }, { it.name }))
        val list = sorted.mapIndexed { index, c ->
            // Like the HTTP API sync: channels without a number are numbered by position.
            val fallback = (index + 1).toString()
            if (c.major <= 0) fallbackNumbers[c.uuid] = fallback
            toTvhChannel(c, fallback)
        }
        val radio = channels.values.filter { it.radio }.mapTo(HashSet()) { it.uuid }
        return GuideSnapshot(list, radio, ArrayList(initialEvents))
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

        /** One full dump over HTSP, without staying connected. */
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
            guide.start().use {
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
