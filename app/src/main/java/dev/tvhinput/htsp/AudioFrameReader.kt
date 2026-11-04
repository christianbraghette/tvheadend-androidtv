package dev.tvhinput.htsp

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.MpegAudioUtil

/**
 * Reads the frame headers of one audio stream. tvheadend describes a stream once, when the
 * subscription starts; the frames say what it really is now:
 * - AAC comes ADTS-framed (LATM too): the header is stripped and turned into the decoder config.
 * - AC3/E-AC3: the real bitrate (see [readDolby]) and the channel layout.
 * - MP2: the channel layout and "dual mono".
 *
 * A layout change (stereo <-> 5.1 when a film starts, MP2 stereo -> mono) yields a new format,
 * which makes Media3 restart the decoder: the FFmpeg one keeps the layout it started with and
 * would turn the new one into noise.
 */
@OptIn(UnstableApi::class)
internal class AudioFrameReader(private val codec: String, private val name: String) {

    /** Header bytes to strip from the frame just read (ADTS), or -1 to drop the frame. */
    var skip = 0
        private set

    /** MP2 in "dual channel" mode: two mono programmes, one per channel. */
    var dualMono = false
        private set

    private var bitratePending = codec == "AC3" || codec == "EAC3"
    private var layoutKnown = false
    private val bits = ParsableBitArray()
    private val mpegHeader = MpegAudioUtil.Header()

    /** The format of the frame at `data[offset, offset + size)`: [format] itself unless its header changes it. */
    fun read(format: Format, data: ByteArray, offset: Int, size: Int): Format {
        skip = 0
        return when (codec) {
            "AAC" -> readAdts(format, data, offset, size)
            "AC3", "EAC3" -> readDolby(format, data, offset, size)
            "MPEG2AUDIO" -> readMpeg(format, data, offset, size)
            else -> format
        }
    }

    private fun readAdts(format: Format, b: ByteArray, offset: Int, size: Int): Format {
        if (size < 7 || b[offset] != 0xff.toByte() || (b[offset + 1].toInt() and 0xf0) != 0xf0) return format
        val headerSize = if (b[offset + 1].toInt() and 0x01 == 0) 9 else 7 // protection_absent == 0 adds a CRC
        if (size <= headerSize) {
            skip = -1
            return format
        }
        skip = headerSize
        val objectType = ((b[offset + 2].toInt() shr 6) and 0x03) + 1
        val rateIndex = (b[offset + 2].toInt() shr 2) and 0x0f
        val channelConfig = ((b[offset + 2].toInt() and 0x01) shl 2) or ((b[offset + 3].toInt() shr 6) and 0x03)
        val config = audioSpecificConfig(objectType, rateIndex, channelConfig)
        if (format.initializationData.firstOrNull()?.contentEquals(config) == true) return format
        return format.buildUpon()
            .setInitializationData(listOf(config))
            .setSampleRate(SAMPLE_RATES.getOrElse(rateIndex) { format.sampleRate })
            .setChannelCount(if (channelConfig in 1..6) channelConfig else format.channelCount)
            .build()
    }

    /**
     * Besides the layout, declares the real bitrate from the first syncframe. Without it Media3
     * sizes a passthrough AudioTrack for the codec's maximum rate (192 kB for E-AC3, ~11 s of a
     * typical 140 kbit/s DVB-T stream), and TVs that only start a direct output once that buffer
     * is full stayed silent on a frozen first frame for that long after every zap.
     */
    private fun readDolby(format: Format, data: ByteArray, offset: Int, size: Int): Format {
        if (size < 8 || data[offset] != 0x0b.toByte() || data[offset + 1] != 0x77.toByte()) return format
        val info = try {
            bits.reset(data)
            bits.setPosition(offset * 8)
            Ac3Util.parseAc3SyncframeInfo(bits)
        } catch (e: RuntimeException) {
            if (bitratePending) Log.w(TAG, "$name: unparseable syncframe", e)
            bitratePending = false
            return format
        }
        var result = format
        if (bitratePending && info.bitrate > 0 && info.frameSize > 0) {
            bitratePending = false
            // A packet can hold several syncframes (e.g. E-AC3 dependent substreams): scale to all of it.
            val bitrate = (info.bitrate.toLong() * size / info.frameSize).toInt()
            Log.i(TAG, "$name: ${bitrate / 1000} kbit/s")
            result = result.buildUpon().setAverageBitrate(bitrate).build()
        }
        return withLayout(result, info.channelCount, info.sampleRate)
    }

    private fun readMpeg(format: Format, data: ByteArray, offset: Int, size: Int): Format {
        if (size < 4) return format
        val header = ((data[offset].toInt() and 0xff) shl 24) or ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or (data[offset + 3].toInt() and 0xff)
        if (!mpegHeader.setForHeaderData(header)) return format
        // Channel mode 2 = dual channel: two independent mono programmes, not left/right of one.
        val dual = (header ushr 6) and 0x03 == 2
        if (dual != dualMono) {
            dualMono = dual
            Log.i(TAG, "$name ${if (dual) "is" else "is no longer"} dual mono")
        }
        return withLayout(format, mpegHeader.channels, mpegHeader.sampleRate)
    }

    private fun withLayout(format: Format, channels: Int, sampleRate: Int): Format {
        if (channels <= 0 || sampleRate <= 0) return format
        val first = !layoutKnown // the first header only corrects tvheadend's description
        layoutKnown = true
        if (channels == format.channelCount && sampleRate == format.sampleRate) return format
        Log.i(
            TAG,
            "$name ${if (first) "is" else "changed to"} $channels ch $sampleRate Hz" +
                if (first) "" else " (was ${format.channelCount} ch ${format.sampleRate} Hz)",
        )
        return format.buildUpon().setChannelCount(channels).setSampleRate(sampleRate).build()
    }

    companion object {
        private const val TAG = "HtspExtractor"

        /** AAC sample rates by index, which tvheadend also uses for every audio stream's "rate". */
        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        fun audioSpecificConfig(objectType: Int, rateIndex: Int, channelConfig: Int) = byteArrayOf(
            ((objectType shl 3) or (rateIndex shr 1)).toByte(),
            (((rateIndex and 0x01) shl 7) or (channelConfig shl 3)).toByte(),
        )
    }
}
