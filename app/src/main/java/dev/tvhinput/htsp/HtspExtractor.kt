package dev.tvhinput.htsp

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import kotlin.math.abs

/**
 * Reads the htsmsg frames produced by [HtspDataSource]: `subscriptionStart` declares the
 * elementary streams, each `muxpkt` carries one already-demuxed frame of one stream.
 */
@OptIn(UnstableApi::class)
class HtspExtractor(private val subtitleParserFactory: SubtitleParser.Factory) : Extractor {

    private class Track(val output: TrackOutput, val codec: String, var format: Format) {
        val video = isVideo(codec)
        val aac = codec == "AAC"

        // A video picture whose data is written but not yet committed, in case the next packet
        // is its second field (see onPacket).
        var pendingSize = 0
        var pendingTimeUs = 0L
        var pendingDtsUs = 0L
        var pendingDurationUs = 0L
        var pendingKeyFrame = false

        /** Presentation time of this video track's first keyframe, once seen. */
        var firstKeyFrameUs = C.TIME_UNSET

        // H.264: the format is declared at the first keyframe, with the SPS/PPS seen so far.
        var formatPending = false
        var sps: ByteArray? = null
        var pps: ByteArray? = null
    }

    private lateinit var extractorOutput: ExtractorOutput
    private val tracks = HashMap<Int, Track>()
    private var started = false
    private var baseTimeUs = C.TIME_UNSET
    private var firstPacketUs = C.TIME_UNSET
    private val header = ByteArray(4)
    private var body = ByteArray(64 * 1024)
    private val fields = HtsFieldReader()
    private val sample = ParsableByteArray()

    override fun sniff(input: ExtractorInput) = true

    override fun init(output: ExtractorOutput) {
        // DVB subtitles are turned into ready-to-draw cues here, while extracting, as Media3's own
        // extractors do (replaces the deprecated SubtitleTranscodingExtractor wrapper).
        extractorOutput = SubtitleTranscodingExtractorOutput(output, subtitleParserFactory)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!input.readFully(header, 0, 4, /* allowEndOfInput= */ true)) return Extractor.RESULT_END_OF_INPUT
        val length = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (body.size < length) body = ByteArray(length)
        input.readFully(body, 0, length)

        // Read muxpkt fields in place: no map, no boxing, and the payload is never copied.
        var method: String? = null
        var stream = -1
        var pts = NONE
        var dts = NONE
        var frameType = 0
        var duration = 0L
        var payloadOffset = -1
        var payloadLength = 0
        fields.reset(body, 0, length)
        while (fields.next()) {
            when {
                fields.nameIs(METHOD) -> method = fields.method()
                fields.nameIs(STREAM) -> stream = fields.long().toInt()
                fields.nameIs(PTS) -> pts = fields.long()
                fields.nameIs(DTS) -> dts = fields.long()
                fields.nameIs(FRAMETYPE) -> frameType = fields.long().toInt()
                fields.nameIs(DURATION) -> duration = fields.long()
                fields.nameIs(PAYLOAD) -> {
                    payloadOffset = fields.dataOffset
                    payloadLength = fields.dataLength
                }
            }
        }
        when (method) {
            "subscriptionStart" -> onStart(HtsMsg.deserialize(body, 0, length))
            "muxpkt" -> {
                val timestamp = if (pts != NONE) pts else dts
                if (timestamp != NONE && payloadOffset >= 0) {
                    onPacket(stream, timestamp, if (dts != NONE) dts else timestamp, duration, frameType, payloadOffset, payloadLength)
                }
            }
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun onStart(msg: HtsMsg) {
        // Tracks can't change once declared; tvheadend sends a new start only on stream changes.
        if (started) return
        for (stream in msg.list("streams")) {
            stream as HtsMsg
            val index = stream.int("index") ?: continue
            val codec = stream.str("type") ?: continue
            val format = formatFor(index, codec, stream) ?: continue
            val output = extractorOutput.track(index, MimeTypes.getTrackType(format.sampleMimeType))
            val track = Track(output, codec, format)
            // Like Kodi and Media3's TS extractor, configure the H.264 decoder with the stream's
            // SPS/PPS instead of leaving it to find them in the data: some TV decoders started these
            // channels only at the next IDR frame otherwise. tvheadend's own per-stream "meta" can't
            // be used: it puts it on the top-level message.
            if (codec == "H264") track.formatPending = true else output.format(format)
            tracks[index] = track
            val video = if (isVideo(codec)) {
                " ${format.width}x${format.height} aspect=${stream.int("aspect_num")}:${stream.int("aspect_den")} par=${format.pixelWidthHeightRatio}"
            } else ""
            Log.i(TAG, "Stream $index: $codec ${format.sampleMimeType} ${format.language ?: ""}$video")
        }
        extractorOutput.endTracks()
        extractorOutput.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        started = true
    }

    /** One muxpkt; its payload is `body[offset, offset + size)`. Times are in µs. */
    private fun onPacket(stream: Int, timestamp: Long, dts: Long, duration: Long, frameType: Int, offset: Int, size: Int) {
        val track = tracks[stream] ?: return
        val keyFrame = !track.video || frameType == 'I'.code

        // Packets are passed through as they arrive, except video before its first keyframe and
        // the "leading" B-frames that follow it but show before it: they reference the previous
        // GOP, which this subscription never received.
        if (firstPacketUs == C.TIME_UNSET) firstPacketUs = timestamp
        if (timestamp - firstPacketUs > FORMAT_WAIT_US) declarePendingFormats() // no keyframe: don't block audio
        if (track.formatPending) collectParameterSets(track, offset, size)
        if (track.video) {
            if (track.firstKeyFrameUs == C.TIME_UNSET) {
                if (!keyFrame) return
                track.firstKeyFrameUs = timestamp
                if (track.formatPending) declareFormat(track)
            }
            if (timestamp < track.firstKeyFrameUs) return
        }
        if (baseTimeUs == C.TIME_UNSET) baseTimeUs = timestamp

        var start = offset
        if (track.aac) {
            val skip = adtsHeaderSize(track, offset, size)
            if (skip < 0) return
            start += skip
        }
        val sampleSize = offset + size - start
        sample.reset(body, start + sampleSize)
        sample.setPosition(start)

        if (!track.video) {
            track.output.sampleData(sample, sampleSize)
            track.output.sampleMetadata(timestamp - baseTimeUs, if (keyFrame) C.BUFFER_FLAG_KEY_FRAME else 0, sampleSize, 0, null)
            return
        }

        // Field-coded interlaced video (H.264 PAFF, e.g. Italian channel 1) arrives as one packet
        // per field, half a frame apart. Fed to the decoder one field at a time, the Chromecast
        // dropped ~3 frames/s; the TS path hands it whole frames and plays smoothly. So join a
        // field to the one before it: decode times half a frame duration apart. An I field always
        // starts a new frame (unless it follows another I field), which realigns the pairing.
        val secondField = track.pendingSize > 0 && track.pendingDurationUs > 0 &&
            (!keyFrame || track.pendingKeyFrame) &&
            abs((dts - track.pendingDtsUs) * 2 - track.pendingDurationUs) <= FIELD_TOLERANCE_US
        if (!secondField) commitPending(track)
        track.output.sampleData(sample, sampleSize)
        if (secondField) {
            track.pendingSize += sampleSize
            commitPending(track)
        } else {
            track.pendingSize = sampleSize
            track.pendingTimeUs = timestamp - baseTimeUs
            track.pendingDtsUs = dts
            track.pendingDurationUs = duration
            track.pendingKeyFrame = keyFrame
        }
    }

    private fun commitPending(track: Track) {
        if (track.pendingSize == 0) return
        val flags = if (track.pendingKeyFrame) C.BUFFER_FLAG_KEY_FRAME else 0
        track.output.sampleMetadata(track.pendingTimeUs, flags, track.pendingSize, 0, null)
        track.pendingSize = 0
    }

    /** Keeps the latest SPS and PPS (with a 3-byte start code, as Media3 expects) from `body[offset, offset + size)`. */
    private fun collectParameterSets(track: Track, offset: Int, size: Int) {
        val limit = offset + size
        var i = findStartCode(offset, limit)
        while (i < limit) {
            val nal = i + 3
            val next = findStartCode(nal, limit)
            var end = next
            while (end > nal && body[end - 1].toInt() == 0) end-- // zero byte of a 4-byte start code
            if (end > nal) {
                when (body[nal].toInt() and 0x1f) {
                    7 -> track.sps = body.copyOfRange(i, end)
                    8 -> track.pps = body.copyOfRange(i, end)
                }
            }
            i = next
        }
    }

    private fun findStartCode(from: Int, limit: Int): Int {
        var i = from
        while (i + 2 < limit) {
            if (body[i].toInt() == 0 && body[i + 1].toInt() == 0 && body[i + 2].toInt() == 1) return i
            i++
        }
        return limit
    }

    /** Declares an H.264 track's format, with codec config from its SPS/PPS when both were seen. */
    private fun declareFormat(track: Track) {
        track.formatPending = false
        val sps = track.sps
        val pps = track.pps
        if (sps != null && pps != null) {
            try {
                val data = NalUnitUtil.parseSpsNalUnit(sps, 3, sps.size)
                val b = track.format.buildUpon()
                    .setCodecs(CodecSpecificDataUtil.buildAvcCodecString(
                        data.profileIdc, data.constraintsFlagsAndReservedZero2Bits, data.levelIdc,
                    ))
                    .setColorInfo(
                        ColorInfo.Builder()
                            .setColorSpace(data.colorSpace)
                            .setColorRange(data.colorRange)
                            .setColorTransfer(data.colorTransfer)
                            .setLumaBitdepth(data.bitDepthLumaMinus8 + 8)
                            .setChromaBitdepth(data.bitDepthChromaMinus8 + 8)
                            .build()
                    )
                    .setInitializationData(listOf(sps, pps))
                    .setMaxNumReorderSamples(data.maxNumReorderFrames)
                if (track.format.width <= 0 || track.format.height <= 0) b.setWidth(data.width).setHeight(data.height)
                // tvheadend's display aspect has been right for these channels; the SPS is the fallback.
                if (track.format.pixelWidthHeightRatio == 1f) b.setPixelWidthHeightRatio(data.pixelWidthHeightRatio)
                track.format = b.build()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unreadable SPS, decoder starts without codec config: ${e.message}")
            }
        } else {
            Log.i(TAG, "No SPS/PPS before the first keyframe, decoder starts without codec config")
        }
        track.output.format(track.format)
    }

    private fun declarePendingFormats() {
        for (track in tracks.values) if (track.formatPending) declareFormat(track)
    }

    /**
     * tvheadend delivers AAC (including converted LATM) as ADTS frames, while decoders want raw
     * frames plus an AudioSpecificConfig: returns how many header bytes to skip (-1 to drop the
     * frame) and keeps the config in sync with the header.
     */
    private fun adtsHeaderSize(track: Track, offset: Int, size: Int): Int {
        val b = body
        if (size < 7 || b[offset] != 0xff.toByte() || (b[offset + 1].toInt() and 0xf0) != 0xf0) return 0
        val headerSize = if (b[offset + 1].toInt() and 0x01 == 0) 9 else 7 // protection_absent == 0 adds a CRC
        if (size <= headerSize) return -1
        val objectType = ((b[offset + 2].toInt() shr 6) and 0x03) + 1
        val rateIndex = (b[offset + 2].toInt() shr 2) and 0x0f
        val channelConfig = ((b[offset + 2].toInt() and 0x01) shl 2) or ((b[offset + 3].toInt() shr 6) and 0x03)
        val current = track.format.initializationData.firstOrNull()
        val config0 = ((objectType shl 3) or (rateIndex shr 1)).toByte()
        val config1 = (((rateIndex and 0x01) shl 7) or (channelConfig shl 3)).toByte()
        if (current == null || current.size != 2 || current[0] != config0 || current[1] != config1) {
            track.format = track.format.buildUpon()
                .setInitializationData(listOf(audioSpecificConfig(objectType, rateIndex, channelConfig)))
                .setSampleRate(SAMPLE_RATES.getOrElse(rateIndex) { track.format.sampleRate })
                .setChannelCount(if (channelConfig in 1..6) channelConfig else track.format.channelCount)
                .build()
            track.output.format(track.format)
        }
        return headerSize
    }

    private fun formatFor(index: Int, codec: String, stream: HtsMsg): Format? {
        val builder = Format.Builder().setId(index).setLanguage(stream.str("language"))
        val rateIndex = stream.int("rate") ?: 3 // tvheadend sends the AAC-style sample rate index
        val channels = stream.int("channels")?.takeIf { it > 0 } ?: 2
        when (codec) {
            "H264" -> builder.setSampleMimeType(MimeTypes.VIDEO_H264)
            "HEVC" -> builder.setSampleMimeType(MimeTypes.VIDEO_H265)
            "MPEG2VIDEO" -> builder.setSampleMimeType(MimeTypes.VIDEO_MPEG2)
            "MPEG2AUDIO" -> builder.setSampleMimeType(MimeTypes.AUDIO_MPEG_L2)
            "AC3" -> builder.setSampleMimeType(MimeTypes.AUDIO_AC3)
            "EAC3" -> builder.setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            "AAC" -> builder.setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setInitializationData(listOf(audioSpecificConfig(2, rateIndex, channels)))
            "DVBSUB" -> {
                val composition = stream.int("composition_id") ?: 0
                val ancillary = stream.int("ancillary_id") ?: 0
                builder.setSampleMimeType(MimeTypes.APPLICATION_DVBSUBS).setInitializationData(
                    listOf(
                        byteArrayOf(
                            (composition shr 8).toByte(), composition.toByte(),
                            (ancillary shr 8).toByte(), ancillary.toByte(),
                        )
                    )
                )
            }
            else -> return null // teletext, RDS, ... are not played
        }
        if (isVideo(codec)) {
            val width = stream.int("width") ?: 0
            val height = stream.int("height") ?: 0
            if (width > 0) builder.setWidth(width)
            if (height > 0) builder.setHeight(height)
            // Many DVB channels use non-square pixels (e.g. 960x1080 or 720x576 shown at 16:9).
            // tvheadend reports the display aspect ratio: turn it into a pixel aspect ratio, or
            // the picture is shown squeezed (960x1080 even narrower than 4:3).
            val aspectNum = stream.int("aspect_num") ?: 0
            val aspectDen = stream.int("aspect_den") ?: 0
            if (width > 0 && height > 0 && aspectNum > 0 && aspectDen > 0) {
                builder.setPixelWidthHeightRatio((aspectNum.toFloat() * height) / (aspectDen.toFloat() * width))
            }
            // Frame duration in µs.
            stream.int("duration")?.takeIf { it > 0 }?.let { builder.setFrameRate(1_000_000f / it) }
        } else if (codec != "DVBSUB") {
            builder.setChannelCount(channels).setSampleRate(SAMPLE_RATES.getOrElse(rateIndex) { 48000 })
        }
        return builder.build()
    }

    private fun audioSpecificConfig(objectType: Int, rateIndex: Int, channelConfig: Int) = byteArrayOf(
        ((objectType shl 3) or (rateIndex shr 1)).toByte(),
        (((rateIndex and 0x01) shl 7) or (channelConfig shl 3)).toByte(),
    )

    override fun seek(position: Long, timeUs: Long) {
        // Live only.
    }

    override fun release() {}

    private companion object {
        const val TAG = "HtspExtractor"
        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        const val NONE = Long.MIN_VALUE
        const val FORMAT_WAIT_US = 3_000_000L
        const val FIELD_TOLERANCE_US = 2L // 90 kHz -> µs rounding
        val METHOD = HtsFieldReader.name("method")
        val STREAM = HtsFieldReader.name("stream")
        val PTS = HtsFieldReader.name("pts")
        val DTS = HtsFieldReader.name("dts")
        val FRAMETYPE = HtsFieldReader.name("frametype")
        val PAYLOAD = HtsFieldReader.name("payload")
        val DURATION = HtsFieldReader.name("duration")

        fun isVideo(codec: String) = codec == "H264" || codec == "HEVC" || codec == "MPEG2VIDEO"
    }
}
