package dev.tvhinput

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * How the audio tracks of a channel are named in the TV app's menu, which shows a track's
 * description if it has one and its language otherwise.
 *
 * - Broadcasters leave some tracks without a language ("und": Rai's Dolby and audio-description
 *   tracks). Those get the channel's main language, i.e. that of its first tagged track.
 * - TV apps show the channel layout themselves (MochiMochi: "italiano · Mono"), not the codec.
 *   So a further track of a language that already has one gets a description only when that
 *   alone wouldn't tell them apart: "italiano · Dolby Digital" for Dolby, a number otherwise.
 */
@OptIn(UnstableApi::class)
object AudioLabels {

    /** Words for the labels, from the app's resources (fixed ones in tests). */
    class Words(val passthrough: String)

    class Track(val language: String?, val mimeType: String?, val channelCount: Int)

    class Label(val language: String?, val description: String?)

    fun label(tracks: List<Track>, words: Words): List<Label> {
        val main = tracks.firstNotNullOfOrNull { defined(it.language) }
        val languages = tracks.map { defined(it.language) ?: main }
        // What the TV app shows for an entry without a description: language and layout.
        val shown = HashSet<Pair<String?, Int>>()
        val used = HashSet<String>()
        return tracks.mapIndexed { i, track ->
            val language = languages[i]
            val first = languages.indexOf(language) == i
            val dolby = isDolby(track.mimeType)
            val description = when {
                first && !dolby -> null.also { shown += language to track.channelCount }
                dolby && !first -> listOfNotNull(displayName(language), dolbyName(track)).joinToString(" · ")
                dolby -> null.also { shown += language to track.channelCount } // the channel's main track
                shown.add(language to track.channelCount) -> null // the layout tells it apart
                else -> listOfNotNull(displayName(language), "${i + 1}").joinToString(" · ")
            }?.let { if (used.add(it)) it else "$it (${i + 1})" }
            Label(language, description)
        }
    }

    /** "Italiano · Dolby Digital 5.1 (passthrough)": the entry that sends the track undecoded to the TV/receiver. */
    fun passthroughLabel(language: String?, track: Track, words: Words): String =
        listOfNotNull(displayName(language), "${dolbyName(track)} (${words.passthrough})").joinToString(" · ")

    fun isDolby(mimeType: String?) =
        mimeType == MimeTypes.AUDIO_AC3 || mimeType == MimeTypes.AUDIO_E_AC3 || mimeType == MimeTypes.AUDIO_E_AC3_JOC

    private fun dolbyName(track: Track): String {
        val name = if (track.mimeType == MimeTypes.AUDIO_AC3) "Dolby Digital" else "Dolby Digital Plus"
        return when (track.channelCount) {
            6 -> "$name 5.1"
            8 -> "$name 7.1"
            else -> name
        }
    }

    private fun defined(language: String?) = language?.takeIf { it.isNotBlank() && it !in UNDEFINED }

    /** In the device's language and spelling, like the TV app's own entries ("italiano" in Italian). */
    private fun displayName(language: String?): String? = language
        ?.let { Locale.forLanguageTag(it).displayLanguage }
        ?.takeIf { it.isNotBlank() }

    /** ISO 639-2 codes that name no language. */
    private val UNDEFINED = setOf("und", "mul", "mis", "zxx")
}
