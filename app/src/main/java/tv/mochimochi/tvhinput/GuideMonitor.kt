package tv.mochimochi.tvhinput

import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import tv.mochimochi.tvhinput.htsp.HtspConnection
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the TvProvider channels and guide in step with tvheadend while the TV input runs, i.e.
 * while a TV app uses it, on the shared HTSP connection ([HtspHub]): a full sync each time it
 * connects (deletions are only sent live), then every change as tvheadend reports it.
 *
 * A change that fails to be written (a TvProvider hiccup) would otherwise stay missing until the
 * next connect, so the guide is fetched again on the same connection ([HtspGuide.resync]) a
 * minute after a failure, and by the periodic [SyncWorker] every few hours.
 */
class GuideMonitor(private val context: Context) {

    // Provider writes run here, in the order tvheadend sent the changes, off the reader thread.
    private val executor = Executors.newSingleThreadScheduledExecutor(backgroundThreads("tvh-guide-apply"))
    /** The applier of the connection followed now; older ones drop what they still had queued. */
    @Volatile private var current: Applier? = null
    @Volatile private var guide: HtspGuide? = null
    private var resyncPending: ScheduledFuture<*>? = null // executor thread only

    /** Follows the guide on [connection], a fresh one; throws if tvheadend refuses to send it. */
    fun follow(connection: HtspConnection, client: TvhClient) {
        val applier = Applier(TvProviderSync(context, client))
        current = applier
        val guide = HtspGuide(client, applier)
        this.guide = guide
        guide.attach(connection)
        Log.i(TAG, "Following guide changes over HTSP")
        active = this
    }

    /** Fetches the guide again on the followed connection; completes with false if it couldn't. */
    fun resync(): CompletableFuture<Boolean> =
        guide?.resync() ?: CompletableFuture.completedFuture(false)

    fun stop() {
        current = null
        guide = null
        isLive = false
        if (active === this) active = null
        executor.shutdownNow()
    }

    /** After a failed write: one resync a little later, however many writes failed meanwhile. */
    private fun scheduleResync() {
        if (resyncPending?.isDone == false) return
        Log.i(TAG, "Resyncing the guide in ${RESYNC_AFTER_FAILURE_MS / 1000} s to fill in what failed")
        resyncPending = try {
            executor.schedule({ resync() }, RESYNC_AFTER_FAILURE_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null // stopped
        }
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
            sync.syncPrograms(snapshot.events, ids, snapshot.keepEndedBefore)
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
                    programs[event.eventId] = TvProviderSync.ProgramRow(row.id, fingerprint, event.stopMs)
                    return@post
                }
            }
            val id = resolver.insert(TvContract.Programs.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: return@post
            programs[event.eventId] = TvProviderSync.ProgramRow(id, fingerprint, event.stopMs)
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
                        // A provider hiccup must not stop the updates; a resync fills in what's missing.
                        Log.w(TAG, "Couldn't apply a guide change", e)
                        scheduleResync()
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Stopped.
            }
        }
    }

    companion object {
        private const val TAG = "GuideMonitor"
        private const val RESYNC_AFTER_FAILURE_MS = 60_000L

        /**
         * Whether a running TV input keeps the guide current right now: then a periodic sync goes
         * through [active] on its connection instead of opening a second one to tvheadend.
         */
        @Volatile var isLive = false
            private set

        // Weak: the monitor holds the TV input service; stop() clears it anyway.
        @Volatile private var activeRef: WeakReference<GuideMonitor>? = null

        /** The running TV input's monitor, if any. */
        var active: GuideMonitor?
            get() = activeRef?.get()
            private set(value) {
                activeRef = value?.let(::WeakReference)
            }
    }
}
