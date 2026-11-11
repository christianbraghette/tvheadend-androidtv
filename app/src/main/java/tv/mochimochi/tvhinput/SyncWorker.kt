package tv.mochimochi.tvhinput

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Periodically refreshes channels and EPG from tvheadend. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // Its own background-priority thread, not Dispatchers.IO: the sync may run while a channel plays.
    override suspend fun doWork(): Result = Executors.newSingleThreadExecutor(backgroundThreads("tvh-sync"))
        .asCoroutineDispatcher().use { dispatcher -> withContext(dispatcher) { sync() } }

    private fun sync(): Result {
        val config = Settings.load(applicationContext) ?: return Result.success()
        val live = GuideMonitor.active?.takeIf { GuideMonitor.isLive }
        if (live != null) {
            // The running TV input follows every change on its connection: resync on that one
            // instead of opening a second, which also fills in any change that failed to be written.
            val done = try {
                live.resync().get(RESYNC_TIMEOUT_MIN, TimeUnit.MINUTES)
            } catch (e: Exception) {
                Log.w(TAG, "Live guide resync didn't finish: ${e.message}")
                false
            }
            if (done) {
                Log.i(TAG, "Guide resynced on the TV input's connection")
                return Result.success()
            }
            // It may have just dropped: the reconnect resyncs everything anyway, so only retry
            // if nothing follows the guide any more.
            if (GuideMonitor.isLive) return Result.success()
        }
        try {
            val programs = TvProviderSync(applicationContext, TvhClient(config)).syncAll()
            Log.i(TAG, "Sync finished, $programs programs")
            return Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Sync failed, will retry", e)
            return Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            return Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "tvh-epg-sync"
        private const val RESYNC_TIMEOUT_MIN = 10L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
