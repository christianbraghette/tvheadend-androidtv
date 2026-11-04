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
 * - The first track of each language shows just the language. Further tracks of the same
 *   language get "<Language> · <what it is>": "Italiano · Dolby Digital", "Italiano · Mono".
 */
@OptIn(UnstableApi::class)
object AudioLabels {

    /** Words for the labels, from the app's resources (fixed ones in tests). */
    class Words(val mono: String, val passthrough: String)

    class Track(val language: String?, val mimeType: String?, val channelCount: Int)

    class Label(val language: String?, val description: String?)

    fun label(tracks: List<Track>, words: Words): List<Label> {
        val main = tracks.firstNotNullOfOrNull { defined(it.language) }
        val languages = tracks.map { defined(it.language) ?: main }
        val used = HashSet<String>()
        return tracks.mapIndexed { i, track ->
            val language = languages[i]
            val first = languages.indexOf(language) == i
            val description = if (first) null else {
                var text = listOfNotNull(displayName(language), kind(track, words) ?: "${i + 1}").joinToString(" · ")
                if (!used.add(text)) text += " (${i + 1})"
                text
            }
            Label(language, description)
        }
    }

    /** "Italiano · Dolby Digital 5.1 (passthrough)": the entry that sends the track undecoded to the TV/receiver. */
    fun passthroughLabel(language: String?, track: Track, words: Words): String =
        listOfNotNull(displayName(language), "${dolbyName(track)} (${words.passthrough})").joinToString(" · ")

    fun isDolby(mimeType: String?) =
        mimeType == MimeTypes.AUDIO_AC3 || mimeType == MimeTypes.AUDIO_E_AC3 || mimeType == MimeTypes.AUDIO_E_AC3_JOC

    private fun kind(track: Track, words: Words): String? = when {
        isDolby(track.mimeType) -> dolbyName(track)
        track.channelCount == 1 -> words.mono
        track.mimeType == MimeTypes.AUDIO_AAC -> "AAC"
        track.mimeType == MimeTypes.AUDIO_MPEG_L2 || track.mimeType == MimeTypes.AUDIO_MPEG -> "MPEG"
        else -> null
    }

    private fun dolbyName(track: Track): String {
        val name = if (track.mimeType == MimeTypes.AUDIO_AC3) "Dolby Digital" else "Dolby Digital Plus"
        return when (track.channelCount) {
            6 -> "$name 5.1"
            8 -> "$name 7.1"
            else -> name
        }
    }

    private fun defined(language: String?) = language?.takeIf { it.isNotBlank() && it !in UNDEFINED }

    private fun displayName(language: String?): String? = language
        ?.let { Locale.forLanguageTag(it).displayLanguage }
        ?.takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.titlecase() }

    /** ISO 639-2 codes that name no language. */
    private val UNDEFINED = setOf("und", "mul", "mis", "zxx")
}
