package tv.mochimochi.tvhinput.htsp

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.LatmReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * AAC in LATM/LOAS framing (tvheadend's "MPEG4AUDIO", as DVB-T2 channels send it), which decoders
 * can't take as is. Media3's own LATM parser from its TS extractor unpacks it into plain AAC
 * frames plus their config and writes them to the stream's [output].
 */
@OptIn(UnstableApi::class)
internal class LatmAudio(private val output: TrackOutput, format: Format) {

    private val reader = LatmReader(format.language, format.roleFlags, MimeTypes.VIDEO_MP2T)
    private val packet = ParsableByteArray()

    init {
        // The parser asks for its track through an ExtractorOutput: hand it the one we declared.
        val single = object : ExtractorOutput {
            override fun track(id: Int, type: Int) = output
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        reader.createTracks(single, TsPayloadReader.TrackIdGenerator(format.id?.toIntOrNull() ?: 0, 1))
    }

    /** One packet of the stream, `data[offset, offset + size)`, presented at [timeUs]. */
    fun consume(data: ByteArray, offset: Int, size: Int, timeUs: Long) {
        packet.reset(data, offset + size)
        packet.setPosition(offset)
        reader.packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)
        reader.consume(packet)
        reader.packetFinished(/* isEndOfInput= */ false)
    }

    companion object {
        /** Whether the packet starts with a LOAS sync word (0x2B7, 11 bits): LATM rather than ADTS. */
        fun isLoas(data: ByteArray, offset: Int, size: Int) =
            size >= 3 && data[offset] == 0x56.toByte() && (data[offset + 1].toInt() and 0xe0) == 0xe0
    }
}
