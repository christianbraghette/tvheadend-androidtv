package dev.tvhinput

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * DefaultLoadControl with a start threshold that can change per channel. A live stream only
 * arrives in real time, so the cushion buffered before playback starts is all the player ever
 * has: small means fast zapping, large rides out channels whose data arrives unevenly.
 *
 * Extends DefaultLoadControl rather than delegating to it: Kotlin's `by` delegation doesn't
 * forward LoadControl's Java default methods, which then throw "not implemented".
 */
@OptIn(UnstableApi::class)
class LiveLoadControl : DefaultLoadControl() {

    @Volatile
    var startBufferUs = 2_000_000L

    @Volatile
    var rebufferUs = 3_000_000L

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        parameters.bufferedDurationUs >= if (parameters.rebuffering) rebufferUs else startBufferUs
}
