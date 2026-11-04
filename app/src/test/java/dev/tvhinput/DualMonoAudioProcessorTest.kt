package dev.tvhinput

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DualMonoAudioProcessorTest {

    // Two 16-bit stereo frames: L=0x0102 R=0x0304, L=0x0506 R=0x0708 (byte order is irrelevant).
    private val input = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    private fun process(mode: DualMonoAudioProcessor.Mode): ByteArray {
        val p = DualMonoAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.mode = mode
        p.queueInput(ByteBuffer.wrap(input).order(ByteOrder.nativeOrder()))
        val out = p.output
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    @Test
    fun stereoPassesThrough() = assertArrayEquals(input, process(DualMonoAudioProcessor.Mode.STEREO))

    @Test
    fun leftOnBothSpeakers() = assertArrayEquals(byteArrayOf(1, 2, 1, 2, 5, 6, 5, 6), process(DualMonoAudioProcessor.Mode.LEFT))

    @Test
    fun rightOnBothSpeakers() = assertArrayEquals(byteArrayOf(3, 4, 3, 4, 7, 8, 7, 8), process(DualMonoAudioProcessor.Mode.RIGHT))

    @Test
    fun emptyInputIsIgnored() {
        // The pipeline polls with the shared EMPTY_BUFFER, which is also a fresh processor's output.
        val p = DualMonoAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(p.output.hasRemaining())
    }

    @Test
    fun onlyStereoPcmIsHandled() {
        val p = DualMonoAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_16BIT))
        assertFalse(p.isActive)
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertTrue(p.isActive)
    }
}
