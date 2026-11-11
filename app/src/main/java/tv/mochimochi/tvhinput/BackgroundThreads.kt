package tv.mochimochi.tvhinput

import android.os.Process
import java.util.concurrent.ThreadFactory

/**
 * Guide work (decoding thousands of events, writing them to the TvProvider) runs at background
 * priority so it never takes CPU from playback: on a busy Chromecast the first full sync at normal
 * priority starved the player, which rebuffered every few seconds until the sync was done.
 */
internal fun lowerCurrentThreadPriority() = Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

/** Daemon threads named [name] that run at background priority. */
internal fun backgroundThreads(name: String) = ThreadFactory { task ->
    Thread({
        lowerCurrentThreadPriority()
        task.run()
    }, name).apply { isDaemon = true }
}
