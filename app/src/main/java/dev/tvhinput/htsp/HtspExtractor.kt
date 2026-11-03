package dev.tvhinput.htsp

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput

/**
 * Reads the htsmsg frames produced by [HtspDataSource]: `subscriptionStart` declares the
 * elementary streams, each `muxpkt` carries one already-demuxed frame of one stream.
 */
@OptIn(UnstableApi::class)
class HtspExtractor(private val subtitleParserFactory: SubtitleParser.Factory) : Extractor {

    private class Track(val output: TrackOutput, val codec: String, var format: Format) {
        val video = isVideo(codec)
        val aac = codec == "AAC"
    }

    private lateinit var extractorOutput: ExtractorOutput
    private val tracks = HashMap<Int, Track>()
    private var started = false
    private var baseTimeUs = C.TIME_UNSET
    private var firstSeenUs = C.TIME_UNSET
    private var hasVideo = false
    private var droppedBeforeStart = 0
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
                    onPacket(stream, timestamp, frameType, payloadOffset, payloadLength)
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
            output.format(format)
            tracks[index] = Track(output, codec, format)
            if (isVideo(codec)) hasVideo = true
            val video = if (isVideo(codec)) {
                " ${format.width}x${format.height} aspect=${stream.int("aspect_num")}:${stream.int("aspect_den")} par=${format.pixelWidthHeightRatio}"
            } else ""
            Log.i(TAG, "Stream $index: $codec ${format.sampleMimeType} ${format.language ?: ""}$video")
        }
        extractorOutput.endTracks()
        extractorOutput.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        started = true
    }

    /** One muxpkt; its payload is `body[offset, offset + size)`. */
    private fun onPacket(stream: Int, timestamp: Long, frameType: Int, offset: Int, size: Int) {
        val track = tracks[stream] ?: return
        val video = track.video
        val keyFrame = !video || frameType == 'I'.code

        // A subscription starts mid-GOP: audio would play over a black screen until the next video
        // keyframe. Start the timeline at that keyframe and drop everything before it instead.
        if (baseTimeUs == C.TIME_UNSET) {
            if (firstSeenUs == C.TIME_UNSET) firstSeenUs = timestamp
            // ...but don't wait forever: a scrambled or broken video stream must not mute the audio.
            val waitedTooLong = timestamp - firstSeenUs > MAX_KEYFRAME_WAIT_US
            if (hasVideo && !(video && keyFrame) && !waitedTooLong) {
                droppedBeforeStart++
                return
            }
            baseTimeUs = timestamp
            Log.i(TAG, "Starting at first ${if (video) "keyframe" else "packet"}, dropped $droppedBeforeStart packets")
        }
        if (timestamp < baseTimeUs) return

        var start = offset
        if (track.aac) {
            val skip = adtsHeaderSize(track, offset, size)
            if (skip < 0) return
            start += skip
        }
        val sampleSize = offset + size - start

        val flags = if (keyFrame) C.BUFFER_FLAG_KEY_FRAME else 0
        sample.reset(body, start + sampleSize)
        sample.setPosition(start)
        track.output.sampleData(sample, sampleSize)
        track.output.sampleMetadata(timestamp - baseTimeUs, flags, sampleSize, 0, null)
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
        const val MAX_KEYFRAME_WAIT_US = 5_000_000L
        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        const val NONE = Long.MIN_VALUE
        val METHOD = HtsFieldReader.name("method")
        val STREAM = HtsFieldReader.name("stream")
        val PTS = HtsFieldReader.name("pts")
        val DTS = HtsFieldReader.name("dts")
        val FRAMETYPE = HtsFieldReader.name("frametype")
        val PAYLOAD = HtsFieldReader.name("payload")

        fun isVideo(codec: String) = codec == "H264" || codec == "HEVC" || codec == "MPEG2VIDEO"
    }
}
