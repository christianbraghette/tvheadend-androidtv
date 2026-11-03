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

/**
 * Reads the htsmsg frames produced by [HtspDataSource]: `subscriptionStart` declares the
 * elementary streams, each `muxpkt` carries one already-demuxed frame of one stream.
 */
@OptIn(UnstableApi::class)
class HtspExtractor : Extractor {

    private class Track(val output: TrackOutput, val codec: String, var format: Format)

    private lateinit var extractorOutput: ExtractorOutput
    private val tracks = HashMap<Int, Track>()
    private var started = false
    private var baseTimeUs = C.TIME_UNSET
    private var firstSeenUs = C.TIME_UNSET
    private var hasVideo = false
    private var droppedBeforeStart = 0
    private val header = ByteArray(4)
    private var body = ByteArray(64 * 1024)

    override fun sniff(input: ExtractorInput) = true

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!input.readFully(header, 0, 4, /* allowEndOfInput= */ true)) return Extractor.RESULT_END_OF_INPUT
        val length = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (body.size < length) body = ByteArray(length)
        input.readFully(body, 0, length)
        val msg = HtsMsg.deserialize(body, 0, length)
        when (msg.method) {
            "subscriptionStart" -> onStart(msg)
            "muxpkt" -> onPacket(msg)
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
            Log.i(TAG, "Stream $index: $codec ${format.sampleMimeType} ${format.language ?: ""}")
        }
        extractorOutput.endTracks()
        extractorOutput.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        started = true
    }

    private fun onPacket(msg: HtsMsg) {
        val track = tracks[msg.int("stream") ?: return] ?: return
        var payload = msg.bin("payload") ?: return
        val timestamp = msg.long("pts") ?: msg.long("dts") ?: return
        val video = isVideo(track.codec)
        val keyFrame = !video || msg.int("frametype") == 'I'.code

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

        if (track.codec == "AAC") payload = stripAdts(track, payload) ?: return

        val flags = if (keyFrame) C.BUFFER_FLAG_KEY_FRAME else 0
        track.output.sampleData(ParsableByteArray(payload), payload.size)
        track.output.sampleMetadata(timestamp - baseTimeUs, flags, payload.size, 0, null)
    }

    /**
     * tvheadend delivers AAC (including converted LATM) as ADTS frames, while decoders want raw
     * frames plus an AudioSpecificConfig: strip the header and keep the config in sync with it.
     */
    private fun stripAdts(track: Track, frame: ByteArray): ByteArray? {
        if (frame.size < 7 || frame[0] != 0xff.toByte() || (frame[1].toInt() and 0xf0) != 0xf0) return frame
        val headerSize = if (frame[1].toInt() and 0x01 == 0) 9 else 7 // protection_absent == 0 adds a CRC
        if (frame.size <= headerSize) return null
        val objectType = ((frame[2].toInt() shr 6) and 0x03) + 1
        val rateIndex = (frame[2].toInt() shr 2) and 0x0f
        val channelConfig = ((frame[2].toInt() and 0x01) shl 2) or ((frame[3].toInt() shr 6) and 0x03)
        val config = audioSpecificConfig(objectType, rateIndex, channelConfig)
        if (!track.format.initializationData.firstOrNull().contentEquals(config)) {
            track.format = track.format.buildUpon()
                .setInitializationData(listOf(config))
                .setSampleRate(SAMPLE_RATES.getOrElse(rateIndex) { track.format.sampleRate })
                .setChannelCount(if (channelConfig in 1..6) channelConfig else track.format.channelCount)
                .build()
            track.output.format(track.format)
        }
        return frame.copyOfRange(headerSize, frame.size)
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
            stream.int("width")?.let { builder.setWidth(it) }
            stream.int("height")?.let { builder.setHeight(it) }
            // Frame duration in µs.
            stream.int("duration")?.takeIf { it > 0 }?.let { builder.setFrameRate(1_000_000f / it) }
        } else if (codec != "DVBSUB") {
            builder.setChannelCount(channels).setSampleRate(SAMPLE_RATES.getOrElse(rateIndex) { 48000 })
        }
        return builder.build()
    }

    private fun isVideo(codec: String) = codec == "H264" || codec == "HEVC" || codec == "MPEG2VIDEO"

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
    }
}
