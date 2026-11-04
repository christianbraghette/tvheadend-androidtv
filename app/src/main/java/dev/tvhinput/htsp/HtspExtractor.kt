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
class HtspExtractor(
    private val subtitleParserFactory: SubtitleParser.Factory,
    /** Told (on the loading thread) when an MP2 stream, by format id, enters or leaves dual mono. */
    private val onDualMono: (streamId: String?, dual: Boolean) -> Unit = { _, _ -> },
) : Extractor {

    private class Track(val output: TrackOutput, val codec: String, var format: Format) {
        val video = isVideo(codec)
        val name = "Stream ${format.id} ($codec)"
        val audio = if (MimeTypes.isAudio(format.sampleMimeType)) {
            AudioFrameReader(if (codec == "MPEG4AUDIO") "AAC" else codec, name)
        } else null
        /** "MPEG4AUDIO" whose packets turned out to be LATM, not ADTS (see onPacket). */
        var latm: LatmAudio? = null
        val aspect = if (video) VideoAspectReader(codec, name) else null
        val text = if (codec == "TEXTSUB") TextSubtitles() else null
        val gaps = TimestampGaps(name)

        /** Declares [newFormat] if it differs from the current one (Media3 then adapts the decoder). */
        fun update(newFormat: Format) {
            if (newFormat === format) return
            format = newFormat
            output.format(newFormat)
        }

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
    private val cueSample = ParsableByteArray()

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
            Log.d(TAG, "From tvheadend: $stream")
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
            val video = if (track.video) " ${format.width}x${format.height} par=${format.pixelWidthHeightRatio}" else ""
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
        track.gaps.check(dts, duration)

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

        if (track.codec == "MPEG4AUDIO") {
            if (track.latm == null && LatmAudio.isLoas(body, offset, size)) {
                Log.i(TAG, "${track.name} is LATM: unpacking it to AAC")
                track.latm = LatmAudio(track.output, track.format)
            }
            track.latm?.let {
                it.consume(body, offset, size, timestamp - baseTimeUs)
                return
            }
        }
        val audio = track.audio
        if (audio != null) {
            val wasDualMono = audio.dualMono
            track.update(audio.read(track.format, body, offset, size))
            if (audio.skip < 0) return
            if (audio.dualMono != wasDualMono) onDualMono(track.format.id, audio.dualMono)
            val sampleSize = size - audio.skip
            writeSample(track, offset + audio.skip, sampleSize)
            commitSample(track, timestamp - baseTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize)
            return
        }
        val text = track.text
        if (text != null) {
            val cues = text.encode(body, offset, size, duration)
            cueSample.reset(cues)
            track.output.sampleData(cueSample, cues.size)
            commitSample(track, timestamp - baseTimeUs, C.BUFFER_FLAG_KEY_FRAME, cues.size)
            return
        }
        if (!track.video) { // DVB subtitles
            writeSample(track, offset, size)
            commitSample(track, timestamp - baseTimeUs, C.BUFFER_FLAG_KEY_FRAME, size)
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
        // After committing the previous picture, so a new picture shape applies from this keyframe on.
        if (keyFrame && !secondField && !track.formatPending) {
            track.aspect?.let { track.update(it.read(track.format, body, offset, size)) }
        }
        writeSample(track, offset, size)
        if (secondField) {
            track.pendingSize += size
            commitPending(track)
        } else {
            track.pendingSize = size
            track.pendingTimeUs = timestamp - baseTimeUs
            track.pendingDtsUs = dts
            track.pendingDurationUs = duration
            track.pendingKeyFrame = keyFrame
        }
    }

    private fun writeSample(track: Track, start: Int, size: Int) {
        sample.reset(body, start + size)
        sample.setPosition(start)
        track.output.sampleData(sample, size)
    }

    private fun commitSample(track: Track, timeUs: Long, flags: Int, size: Int) {
        track.output.sampleMetadata(timeUs, flags, size, 0, null)
    }

    private fun commitPending(track: Track) {
        if (track.pendingSize == 0) return
        val flags = if (track.pendingKeyFrame) C.BUFFER_FLAG_KEY_FRAME else 0
        commitSample(track, track.pendingTimeUs, flags, track.pendingSize)
        track.pendingSize = 0
    }

    /** Keeps the latest SPS and PPS (with a 3-byte start code, as Media3 expects) from `body[offset, offset + size)`. */
    private fun collectParameterSets(track: Track, offset: Int, size: Int) {
        StartCodes.forEachNal(body, offset, size) { start, end, type ->
            when (type) {
                NAL_SPS -> track.sps = body.copyOfRange(start, end)
                NAL_PPS -> track.pps = body.copyOfRange(start, end)
            }
            true
        }
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
            // AAC comes ADTS-framed; "MPEG4AUDIO" may be LATM instead (handled by LatmAudio).
            "AAC", "MPEG4AUDIO" -> builder.setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setInitializationData(listOf(AudioFrameReader.audioSpecificConfig(2, rateIndex, channels)))
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
            // Teletext subtitles as text (tvheadend converts them), turned into cues by TextSubtitles.
            // Each one replaces the previous: teletext shows one subtitle page at a time.
            "TEXTSUB" -> builder.setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            else -> return null // raw teletext, RDS, ... are not played
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
        } else if (codec != "DVBSUB" && codec != "TEXTSUB") {
            builder.setChannelCount(channels).setSampleRate(AudioFrameReader.SAMPLE_RATES.getOrElse(rateIndex) { 48000 })
            // DVB's ISO 639 audio_type: so the player doesn't pick an audio-description track by
            // default, and TV apps can mark it.
            when (stream.int("audio_type")) {
                1 -> builder.setRoleFlags(C.ROLE_FLAG_ALTERNATE) // clean effects
                2 -> builder.setRoleFlags(C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY) // hearing impaired
                3 -> builder.setRoleFlags(C.ROLE_FLAG_DESCRIBES_VIDEO) // visual impaired commentary
            }
        }
        return builder.build()
    }

    override fun seek(position: Long, timeUs: Long) {
        // Live only.
    }

    override fun release() {}

    private companion object {
        const val TAG = "HtspExtractor"
        const val NONE = Long.MIN_VALUE
        const val FORMAT_WAIT_US = 3_000_000L
        const val FIELD_TOLERANCE_US = 2L // 90 kHz -> µs rounding
        const val NAL_SPS = 7
        const val NAL_PPS = 8
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
