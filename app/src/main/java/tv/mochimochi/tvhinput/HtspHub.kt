package tv.mochimochi.tvhinput

import android.content.Context
import android.util.Log
import tv.mochimochi.tvhinput.htsp.HtspConnection
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The TV input's one HTSP connection to tvheadend, shared the way Kodi's pvr.hts does it: the guide
 * follows its async metadata ([GuideMonitor]), and playback and pre-tuning subscribe on it, so
 * the server sees a single client however many sessions and channels are open.
 *
 * Whoever needs the connection first opens it, and the guide is attached to every new one. After it
 * drops, playback reopens it at once (its retry asks for [connection]) and the guide comes back
 * with it; when nothing plays, the hub reconnects by itself after a backoff.
 */
class HtspHub(private val context: Context) {

    private val guide = GuideMonitor(context)
    private val lock = Any()
    // Written under lock; volatile so close() can reach it without waiting for a connect in progress.
    @Volatile private var connection: HtspConnection? = null
    private var config: TvhConfig? = null // what [connection] was opened with; guarded by lock
    @Volatile private var closed = false

    // Reconnects and settings checks; may block on the network, so never the main thread.
    private val scheduler = Executors.newSingleThreadScheduledExecutor(backgroundThreads("tvh-htsp"))
    private var pending: ScheduledFuture<*>? = null // scheduler thread only
    private var retryMs = RETRY_MIN_MS // scheduler thread only

    // Setup saving another server (or other credentials) moves the connection over to it.
    private val settingsWatch = Settings.observe(context) { post(::checkSettings) }

    fun start() = post(::keepConnected)

    /**
     * The shared connection, opened first if there is none. Blocking: call it off the main thread
     * (playback calls it on its loading thread).
     */
    fun connection(): HtspConnection = synchronized(lock) {
        if (closed) throw IOException("TV input stopped")
        connection?.takeIf { !it.isClosed }?.let { return it }
        val config = Settings.load(context) ?: throw IOException("Not set up")
        val c = HtspConnection.connect(config)
        connection = c
        this.config = config
        if (closed) { // close() ran during the connect and may have missed it
            c.close()
            throw IOException("TV input stopped")
        }
        try {
            guide.follow(c, TvhClient(config))
        } catch (e: IOException) {
            // Playback doesn't need the guide: carry on unless the connection itself died.
            if (c.isClosed) throw e
            Log.w(TAG, "No guide over HTSP on this connection: ${e.message}")
        }
        Log.i(TAG, "Connected to ${c.serverName} ${c.serverVersion} at ${config.host}:${config.htspPort}")
        c.whenClosed { post { onClosed() } }
        c
    }

    fun close() {
        closed = true
        settingsWatch.close()
        scheduler.shutdownNow()
        connection?.close() // ends every subscription on it too
        guide.stop()
    }

    /** Opens the connection when there is none, so the guide stays current while nothing plays. */
    private fun keepConnected() {
        if (closed || Settings.load(context) == null) return // setup saving the settings calls us again
        try {
            connection()
            retryMs = RETRY_MIN_MS
        } catch (e: IOException) {
            Log.i(TAG, "Can't connect over HTSP (retrying in ${retryMs / 1000} s): ${e.message}")
            schedule(retryMs)
            retryMs = minOf(retryMs * 2, RETRY_MAX_MS)
        }
    }

    private fun onClosed() {
        // Unless a retry is due already. Playback usually reconnects well before this.
        if (pending?.isDone != false) schedule(retryMs)
    }

    private fun checkSettings() {
        val wanted = Settings.load(context)
        val stale = synchronized(lock) {
            val c = connection?.takeIf { !it.isClosed }
            if (c != null && config != wanted) c else null
        }
        if (stale != null) {
            Log.i(TAG, "Server settings changed: reconnecting")
            stale.close() // sessions playing on it retry on the new one
        }
        retryMs = RETRY_MIN_MS
        keepConnected()
    }

    private fun schedule(delayMs: Long) {
        pending?.cancel(false)
        pending = try {
            scheduler.schedule(::keepConnected, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null // closed
        }
    }

    private fun post(block: () -> Unit) {
        try {
            scheduler.execute(block)
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    private companion object {
        const val TAG = "HtspHub"
        const val RETRY_MIN_MS = 30_000L
        const val RETRY_MAX_MS = 600_000L
    }
}
