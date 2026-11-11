package tv.mochimochi.tvhinput

import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import tv.mochimochi.tvhinput.htsp.HtspConnection
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Keeps the TvProvider channels and guide in step with tvheadend while the TV input runs, i.e.
 * while a TV app uses it, on the shared HTSP connection ([HtspHub]): a full sync each time it
 * connects (deletions are only sent live), then every change as tvheadend reports it. The
 * periodic [SyncWorker] covers the time in between.
 */
class GuideMonitor(private val context: Context) {

    // Provider writes run here, in the order tvheadend sent the changes, off the reader thread.
    private val executor = Executors.newSingleThreadExecutor(backgroundThreads("tvh-guide-apply"))
    /** The applier of the connection followed now; older ones drop what they still had queued. */
    @Volatile private var current: Applier? = null

    /** Follows the guide on [connection], a fresh one; throws if tvheadend refuses to send it. */
    fun follow(connection: HtspConnection, client: TvhClient) {
        val applier = Applier(TvProviderSync(context, client))
        current = applier
        HtspGuide(client, applier).attach(connection)
        Log.i(TAG, "Following guide changes over HTSP")
    }

    fun stop() {
        current = null
        isLive = false
        executor.shutdown()
    }

    /** Applies one connection's snapshot and changes; all state is confined to [executor]. */
    private inner class Applier(private val sync: TvProviderSync) : HtspGuide.Listener {
        private val resolver = context.contentResolver
        private val channelRows = HashMap<String, Long>() // uuid -> TvProvider channel id
        /** What each channel row was last written with: tvheadend resends channels on every now/next change. */
        private val written = HashMap<String, Pair<TvhChannel, Boolean>>()
        private val programs = HashMap<Long, TvProviderSync.ProgramRow>() // event id -> row

        override fun onSnapshot(snapshot: GuideSnapshot) = post {
            val ids = sync.syncChannels(snapshot)
            sync.syncPrograms(snapshot.events, ids)
            channelRows.clear()
            channelRows.putAll(ids)
            written.clear()
            val radio = snapshot.radio.orEmpty()
            for (channel in snapshot.channels) written[channel.uuid] = channel to (channel.uuid in radio)
            programs.clear()
            for (id in ids.values) programs.putAll(sync.existingPrograms(id))
            isLive = true
        }

        override fun onChannel(channel: TvhChannel, radio: Boolean) = post {
            if (written[channel.uuid] == channel to radio) return@post
            val values = sync.channelValues(channel, radio)
            val row = channelRows[channel.uuid]
            val id = if (row != null && resolver.update(TvContract.buildChannelUri(row), values, null, null) > 0) {
                row
            } else {
                resolver.insert(TvContract.Channels.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: return@post
            }
            Log.i(TAG, "Channel ${channel.number} ${channel.name} ${if (row == null) "added" else "updated"}")
            channelRows[channel.uuid] = id
            written[channel.uuid] = channel to radio
            val known = RadioChannels.load(context)
            if ((channel.uuid in known) != radio) {
                RadioChannels.replace(context, if (radio) known + channel.uuid else known - channel.uuid)
            }
            sync.syncLogo(channel, id)
        }

        override fun onChannelDeleted(uuid: String) = post {
            val row = channelRows.remove(uuid) ?: return@post
            written.remove(uuid)
            Log.i(TAG, "Channel $uuid removed")
            sync.deleteChannel(uuid, row) // TvProvider drops its programs with it
        }

        override fun onEvent(event: TvhEvent) = post {
            val channelId = channelRows[event.channelUuid] ?: return@post
            val values = sync.programValues(channelId, event)
            val fingerprint = TvProviderSync.fingerprintOf(values)
            val row = programs[event.eventId]
            if (row != null) {
                if (row.fingerprint == fingerprint) return@post
                if (resolver.update(TvContract.buildProgramUri(row.id), values, null, null) > 0) {
                    programs[event.eventId] = TvProviderSync.ProgramRow(row.id, fingerprint)
                    return@post
                }
            }
            val id = resolver.insert(TvContract.Programs.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: return@post
            programs[event.eventId] = TvProviderSync.ProgramRow(id, fingerprint)
        }

        override fun onEventDeleted(eventId: Long) = post {
            val row = programs.remove(eventId) ?: return@post
            resolver.delete(TvContract.buildProgramUri(row.id), null, null)
        }

        override fun onConnectionLost(error: IOException) = post {
            Log.w(TAG, "Guide connection lost: ${error.message}")
            isLive = false
        }

        private fun post(block: () -> Unit) {
            try {
                executor.execute {
                    // Replaced by a newer connection (or a new server): its snapshot redoes everything.
                    if (current !== this) return@execute
                    try {
                        block()
                    } catch (e: Exception) {
                        // A provider hiccup must not stop the updates; the next connect resyncs everything.
                        Log.w(TAG, "Couldn't apply a guide change", e)
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Stopped.
            }
        }
    }

    companion object {
        private const val TAG = "GuideMonitor"

        /**
         * Whether a running TV input keeps the guide current right now: then a periodic sync has
         * nothing to add and would only open a second connection to tvheadend.
         */
        @Volatile var isLive = false
            private set
    }
}
