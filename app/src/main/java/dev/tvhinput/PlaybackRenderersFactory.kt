package dev.tvhinput

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The player's renderers, with three changes to Media3's defaults:
 * - No FFmpeg video renderer: it is Media3's ExperimentalFfmpegVideoRenderer ("not yet
 *   functional") and would be software decoding anyway, too slow for broadcast HD. FFmpeg stays for
 *   audio (MP2/AC3/E-AC3). Video the device's decoders (hardware, or Android's software ones via
 *   decoder fallback) can't play is reported instead (see TvhSession).
 * - A new video decoder whenever the video format changes, i.e. on every zap, instead of flushing
 *   and reusing it: a reused Amlogic decoder got stuck in a "resolution changing" state after the
 *   first zap and logged ~40 errors/s for as long as the channel played. Only a change of picture
 *   aspect alone (4:3 <-> 16:9 mid-programme) keeps the running decoder.
 * - The audio sink comes from [AudioOutput].
 */
@OptIn(UnstableApi::class)
class PlaybackRenderersFactory(
    context: Context,
    private val audioOutput: AudioOutput,
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        out.removeAll { it is ExperimentalFfmpegVideoRenderer }
        val index = out.indexOfFirst { it is MediaCodecVideoRenderer }
        if (index < 0) return
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out[index] = object : MediaCodecVideoRenderer(builder) {
            override fun canReuseCodec(codecInfo: MediaCodecInfo, oldFormat: Format, newFormat: Format): DecoderReuseEvaluation {
                val aspectOnly = oldFormat.buildUpon().setPixelWidthHeightRatio(newFormat.pixelWidthHeightRatio).build() == newFormat
                return DecoderReuseEvaluation(
                    codecInfo.name, oldFormat, newFormat,
                    if (aspectOnly) DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION else DecoderReuseEvaluation.REUSE_RESULT_NO,
                    if (aspectOnly) 0 else DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE,
                )
            }
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink = audioOutput.buildSink(enableFloatOutput, enableAudioOutputPlaybackParams)
}
