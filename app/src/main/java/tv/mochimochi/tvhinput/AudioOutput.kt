package tv.mochimochi.tvhinput

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Where the sound goes and in what form.
 *
 * Decoded (PCM) by default: AC3/E-AC3 are decoded here (FFmpeg) like MP2. Sent as a bitstream,
 * the TV or receiver muted while switching to Dolby, so the sound started audibly after the
 * picture even though the player's audio clock was already running. [passthrough] (chosen in the
 * TV app's audio menu, remembered) sends Dolby undecoded, for surround on a receiver.
 *
 * Every PCM stream also goes through [dualMono], which can play one programme of an MP2
 * dual-mono stream on both speakers.
 */
@OptIn(UnstableApi::class)
class AudioOutput(private val context: Context) {

    private val prefs = context.getSharedPreferences("tvh_audio", Context.MODE_PRIVATE)

    /** Read on the playback thread when the audio renderers are asked what they can play. */
    @Volatile var passthrough = prefs.getBoolean(PREF_PASSTHROUGH, false)
        set(value) {
            if (value == field) return
            field = value
            prefs.edit { putBoolean(PREF_PASSTHROUGH, value) }
        }

    val dualMono = DualMonoAudioProcessor()

    /** Whether [format] is Dolby audio the HDMI output accepts undecoded. */
    fun canPassthrough(format: Format): Boolean {
        val encoding = when (format.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> C.ENCODING_AC3
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> C.ENCODING_E_AC3
            else -> return false
        }
        return try {
            AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null).supportsEncoding(encoding)
        } catch (e: RuntimeException) {
            false
        }
    }

    /** The player's audio sink: refuses undecoded audio unless [passthrough] is on. */
    fun buildSink(enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(dualMono))
            .build()
        return object : ForwardingAudioSink(sink) {
            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType != MimeTypes.AUDIO_RAW && !passthrough) AudioSink.SINK_FORMAT_UNSUPPORTED
                else super.getFormatSupport(format)

            override fun supportsFormat(format: Format) =
                getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    private companion object {
        const val PREF_PASSTHROUGH = "dolby_passthrough"
    }
}
