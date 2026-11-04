package dev.tvhinput.htsp

import android.util.Log
import androidx.media3.common.C

/**
 * Each packet of a stream should start where the previous one ended (decode time + duration). A
 * jump means packets were lost upstream (reception, tvheadend): logged at most once a second, with
 * everything missed since, so bad reception doesn't flood the log.
 */
internal class TimestampGaps(private val name: String) {

    private var expectedUs = C.TIME_UNSET
    private var gaps = 0
    private var missingUs = 0L
    private var lastLogNs = 0L

    fun check(dtsUs: Long, durationUs: Long) {
        val expected = expectedUs
        expectedUs = if (durationUs > 0) dtsUs + durationUs else C.TIME_UNSET
        if (expected != C.TIME_UNSET) {
            val gapUs = dtsUs - expected
            // Video fields are half a frame apart, so only jumps well beyond a frame count.
            if (gapUs >= maxOf(MIN_GAP_US, durationUs * 2) || gapUs <= -MIN_GAP_US) {
                gaps++
                missingUs += gapUs
            }
        }
        if (gaps == 0) return
        val now = System.nanoTime()
        if (now - lastLogNs < LOG_INTERVAL_NS) return // reported with the next line
        lastLogNs = now
        Log.w(TAG, "$name: $gaps timestamp jump(s), ${missingUs / 1000} ms in total")
        gaps = 0
        missingUs = 0
    }

    private companion object {
        const val TAG = "HtspExtractor"
        const val MIN_GAP_US = 100_000L
        const val LOG_INTERVAL_NS = 1_000_000_000L
    }
}
