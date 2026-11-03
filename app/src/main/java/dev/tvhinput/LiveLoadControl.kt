package dev.tvhinput

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * DefaultLoadControl with start and rebuffer thresholds that change per channel. A live stream
 * only arrives in real time, so the cushion buffered before playback starts is all the player
 * ever has: small means fast zapping, large rides out channels whose data arrives unevenly.
 * [TvhSession] sets both on every tune from what [StartBufferTuner] learned about the channel.
 *
 * Extends DefaultLoadControl rather than delegating to it: Kotlin's `by` delegation doesn't
 * forward LoadControl's Java default methods, which then throw "not implemented".
 */
@OptIn(UnstableApi::class)
class LiveLoadControl : DefaultLoadControl() {

    @Volatile
    var startBufferUs = 1_500_000L

    @Volatile
    var rebufferUs = 2_500_000L

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        parameters.bufferedDurationUs >= if (parameters.rebuffering) rebufferUs else startBufferUs
}
