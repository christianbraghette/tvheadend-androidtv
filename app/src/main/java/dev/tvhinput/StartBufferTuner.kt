package dev.tvhinput

import android.content.SharedPreferences
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Learns, per channel, how much data to buffer before playback starts.
 *
 * A live stream only arrives in real time, so the buffer present when playback starts is the only
 * cushion against data arriving late. While a channel plays, the session records how far that
 * cushion fell (the buffer at start minus the lowest buffer seen): that dip, plus a safety margin,
 * is what the channel really needs. The start buffer then:
 * - drops slowly toward that need after clean viewing, so most channels end up zapping fast;
 * - rises at once when a channel came close to empty or actually stalled.
 */
class StartBufferTuner(private val prefs: SharedPreferences, private val limits: Limits) {

    data class Limits(val defaultUs: Long, val minUs: Long, val maxUs: Long)

    fun startBufferUs(uuid: String): Long =
        prefs.getLong(uuid, limits.defaultUs).coerceIn(floorUs(uuid), limits.maxUs)

    /**
     * Playback stalled: grow the cushion right away, and never go back down to a size that
     * stalled. Not every stall is missing data: with a small buffer the video decoder can swallow
     * every queued frame before its first output and briefly report "not ready". Either way the
     * channel needs more.
     */
    fun onStall(uuid: String, usedUs: Long) {
        val next = min(limits.maxUs, max(usedUs * 3 / 2, usedUs + STALL_STEP_US))
        Log.i(TAG, "Channel $uuid stalled with ${usedUs / 1000} ms: start buffer now ${next / 1000} ms")
        prefs.edit()
            .putLong(uuid, next)
            .putLong(uuid + FLOOR_SUFFIX, min(limits.maxUs, max(floorUs(uuid), usedUs + STEP_DOWN_US)))
            .apply()
    }

    private fun floorUs(uuid: String): Long = max(limits.minUs, prefs.getLong(uuid + FLOOR_SUFFIX, limits.minUs))

    /** A stretch of playback without stalls, with the buffer at start and the lowest seen since. */
    fun onWatched(uuid: String, usedUs: Long, bufferedAtStartUs: Long, minBufferedUs: Long, watchedMs: Long) {
        val dipUs = max(0L, bufferedAtStartUs - minBufferedUs)
        val neededUs = dipUs + SAFETY_MARGIN_US
        val next = when {
            neededUs > usedUs -> neededUs // a close call: raise it fully
            else -> max(neededUs, usedUs - STEP_DOWN_US) // plenty left: come down a step at a time
        }.coerceIn(floorUs(uuid), limits.maxUs)
        Log.i(
            TAG,
            "Channel $uuid watched ${watchedMs / 1000} s: start ${bufferedAtStartUs / 1000} ms, " +
                "lowest ${minBufferedUs / 1000} ms, start buffer ${usedUs / 1000} -> ${next / 1000} ms",
        )
        store(uuid, next)
    }

    private fun store(uuid: String, valueUs: Long) {
        prefs.edit().putLong(uuid, valueUs).apply()
    }

    private companion object {
        const val TAG = "StartBufferTuner"
        const val SAFETY_MARGIN_US = 400_000L
        const val STEP_DOWN_US = 200_000L
        const val STALL_STEP_US = 500_000L
        const val FLOOR_SUFFIX = ".floor"
    }
}
