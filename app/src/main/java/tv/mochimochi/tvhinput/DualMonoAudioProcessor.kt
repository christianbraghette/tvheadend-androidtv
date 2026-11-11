package tv.mochimochi.tvhinput

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * MP2 "dual mono" carries two programmes in one stereo stream, one per channel (e.g. the dubbed
 * and the original language). Played as stereo, both are heard at once. This plays one of them on
 * both speakers: [Mode.LEFT] (the first programme, DVB's main one) or [Mode.RIGHT]. [Mode.STEREO]
 * leaves the audio untouched; the mode can change at any time and applies from the next buffer.
 *
 * It sits in the path of every stereo stream, so the untouched case must cost nothing: one bulk
 * copy. (A byte-by-byte version starved the AudioTrack on the Chromecast: constant underruns.)
 */
@OptIn(UnstableApi::class)
class DualMonoAudioProcessor : BaseAudioProcessor() {

    enum class Mode { STEREO, LEFT, RIGHT }

    @Volatile var mode = Mode.STEREO

    private var sampleBytes = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2) return AudioFormat.NOT_SET
        sampleBytes = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // The pipeline also polls with an empty input, which is then the same (shared) empty buffer
        // as our output: nothing to do.
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        when (mode) {
            Mode.STEREO -> output.put(inputBuffer)
            Mode.LEFT -> copyOneSide(inputBuffer, output, 0)
            Mode.RIGHT -> copyOneSide(inputBuffer, output, sampleBytes)
        }
        output.flip()
    }

    /** Writes the sample at [side] (byte offset in each frame) of every frame to both channels. */
    private fun copyOneSide(input: ByteBuffer, output: ByteBuffer, side: Int) {
        val frameBytes = sampleBytes * 2
        val sample = ByteArray(sampleBytes)
        var frame = input.position()
        val end = frame + (input.remaining() / frameBytes) * frameBytes
        while (frame < end) {
            input.position(frame + side)
            input.get(sample)
            output.put(sample).put(sample)
            frame += frameBytes
        }
        input.position(input.limit())
    }
}
