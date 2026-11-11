package tv.mochimochi.tvhinput

import android.content.Context
import android.media.tv.TvTrackInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi

/**
 * The tracks as the TV app sees them ([TvTrackInfo]) and back. Ids are "<group>:<track>" of the
 * player's tracks, plus two kinds of extra audio entries for the same track:
 * - `:dolby`  the track sent undecoded to the TV/receiver (Dolby passthrough), when it accepts it;
 * - `:second` the second programme of an MP2 dual-mono track (the plain entry plays the first).
 * Audio entries are named by [AudioLabels].
 */
@OptIn(UnstableApi::class)
class TrackMenu(private val context: Context, private val audioOutput: AudioOutput) {

    enum class Variant(val suffix: String) { PLAIN(""), PASSTHROUGH(":dolby"), DUAL_MONO_SECOND(":second") }

    class Choice(val group: Tracks.Group, val trackIndex: Int, val variant: Variant)

    class Menu(val tracks: List<TvTrackInfo>, val selected: Map<Int, String>)

    /** The picture as the decoder outputs it: beats the size and aspect declared at tune-in. */
    var videoSize: VideoSize? = null

    /** Format ids of the audio streams currently in MP2 dual mono. */
    val dualMonoStreams = HashSet<String>()

    /** Whether the second programme of a dual-mono track was picked. */
    var dualMonoSecond = false

    private val words = AudioLabels.Words(passthrough = context.getString(R.string.audio_passthrough))

    fun build(tracks: Tracks): Menu {
        val infos = ArrayList<TvTrackInfo>()
        val selected = HashMap<Int, String>()
        val audioLabels = AudioLabels.label(
            audioFormats(tracks).map { AudioLabels.Track(it.language, it.sampleMimeType, it.channelCount) }, words,
        )
        var audioIndex = 0
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = tvType(group.type) ?: return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val id = "$groupIndex:$i"
                val isSelected = group.isTrackSelected(i)
                when (type) {
                    TvTrackInfo.TYPE_AUDIO -> {
                        val label = audioLabels[audioIndex++]
                        infos += audioInfo(id, f, label.language, label.description)
                        if (f.id in dualMonoStreams) {
                            val second = id + Variant.DUAL_MONO_SECOND.suffix
                            infos += audioInfo(second, f, null, context.getString(R.string.audio_dual_mono_second), channels = 1)
                            if (isSelected && dualMonoSecond) selected[type] = second
                        }
                        if (audioOutput.canPassthrough(f)) {
                            val dolby = id + Variant.PASSTHROUGH.suffix
                            val track = AudioLabels.Track(label.language, f.sampleMimeType, f.channelCount)
                            infos += audioInfo(dolby, f, label.language, AudioLabels.passthroughLabel(label.language, track, words))
                            if (isSelected && audioOutput.passthrough) selected[type] = dolby
                        }
                    }
                    TvTrackInfo.TYPE_VIDEO -> infos += videoInfo(id, f, videoSize?.takeIf { isSelected })
                    else -> infos += TvTrackInfo.Builder(type, id).apply {
                        f.language?.let { setLanguage(it) }
                        f.label?.let { setDescription(it) }
                    }.build()
                }
                if (isSelected && type !in selected) selected[type] = id
            }
        }
        return Menu(infos, selected)
    }

    /** The track the TV app picked by [trackId] (one of ours, of [trackType]), or null if it isn't valid. */
    fun resolve(tracks: Tracks, trackType: Int, trackId: String): Choice? {
        val variant = Variant.entries.lastOrNull { it.suffix.isNotEmpty() && trackId.endsWith(it.suffix) } ?: Variant.PLAIN
        val parts = trackId.removeSuffix(variant.suffix).split(':').mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return null
        val (groupIndex, trackIndex) = parts
        val group = tracks.groups.getOrNull(groupIndex) ?: return null
        if (group.type != trackType || trackIndex !in 0 until group.length) return null
        val format = group.getTrackFormat(trackIndex)
        val valid = when (variant) {
            Variant.PLAIN -> true
            Variant.PASSTHROUGH -> audioOutput.canPassthrough(format)
            Variant.DUAL_MONO_SECOND -> format.id in dualMonoStreams
        }
        return if (valid) Choice(group, trackIndex, variant) else null
    }

    /** What the dual-mono processor should do for the audio track selected in [tracks]. */
    fun dualMonoMode(tracks: Tracks): DualMonoAudioProcessor.Mode {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
        val format = group?.let { g -> (0 until g.length).firstOrNull(g::isTrackSelected)?.let(g::getTrackFormat) }
        return when {
            format?.id !in dualMonoStreams -> DualMonoAudioProcessor.Mode.STEREO
            dualMonoSecond -> DualMonoAudioProcessor.Mode.RIGHT
            else -> DualMonoAudioProcessor.Mode.LEFT
        }
    }

    /** Clears what belongs to one playback (a new stream reports its dual-mono state again). */
    fun reset() {
        videoSize = null
        dualMonoStreams.clear()
    }

    private fun audioFormats(tracks: Tracks): List<Format> = tracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { g -> (0 until g.length).filter(g::isTrackSupported).map(g::getTrackFormat) }

    private fun audioInfo(id: String, f: Format, language: String?, description: String?, channels: Int = f.channelCount) =
        TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, id).apply {
            language?.let { setLanguage(it) }
            description?.let { setDescription(it) }
            if (channels > 0) setAudioChannelCount(channels)
            if (f.sampleRate > 0) setAudioSampleRate(f.sampleRate)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setAudioDescription(f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0)
                setHardOfHearing(f.roleFlags and C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY != 0)
            }
        }.build()

    private fun videoInfo(id: String, f: Format, size: VideoSize?) =
        TvTrackInfo.Builder(TvTrackInfo.TYPE_VIDEO, id).apply {
            val width = size?.width ?: f.width
            val height = size?.height ?: f.height
            val par = size?.pixelWidthHeightRatio ?: f.pixelWidthHeightRatio
            if (width > 0) setVideoWidth(width)
            if (height > 0) setVideoHeight(height)
            if (f.frameRate > 0) setVideoFrameRate(f.frameRate)
            if (par > 0) setVideoPixelAspectRatio(par)
        }.build()

    companion object {
        fun tvType(trackType: Int): Int? = when (trackType) {
            C.TRACK_TYPE_AUDIO -> TvTrackInfo.TYPE_AUDIO
            C.TRACK_TYPE_VIDEO -> TvTrackInfo.TYPE_VIDEO
            C.TRACK_TYPE_TEXT -> TvTrackInfo.TYPE_SUBTITLE
            else -> null
        }

        fun trackType(tvType: Int): Int? = when (tvType) {
            TvTrackInfo.TYPE_AUDIO -> C.TRACK_TYPE_AUDIO
            TvTrackInfo.TYPE_VIDEO -> C.TRACK_TYPE_VIDEO
            TvTrackInfo.TYPE_SUBTITLE -> C.TRACK_TYPE_TEXT
            else -> null
        }
    }
}
