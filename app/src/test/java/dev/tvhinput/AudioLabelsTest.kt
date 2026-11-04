package dev.tvhinput

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AudioLabelsTest {

    private val words = AudioLabels.Words(passthrough = "passthrough")

    @Before
    fun englishNames() = Locale.setDefault(Locale.ENGLISH)

    private fun track(language: String?, mime: String, channels: Int) = AudioLabels.Track(language, mime, channels)

    private fun labels(vararg tracks: AudioLabels.Track) =
        AudioLabels.label(tracks.toList(), words).map { it.language to it.description }

    @Test
    fun rai1() = assertEquals(
        listOf(
            "it" to null,
            "it" to "Italian · Dolby Digital", // "und" Dolby: the channel's language, and the codec
            "it" to null, // "und" mono: the TV app's "· Mono" already tells it apart
            "en" to null,
        ),
        labels(
            track("it", MimeTypes.AUDIO_MPEG_L2, 2),
            track("und", MimeTypes.AUDIO_AC3, 2),
            track("und", MimeTypes.AUDIO_MPEG_L2, 1),
            track("en", MimeTypes.AUDIO_MPEG_L2, 2),
        ),
    )

    @Test
    fun rai2() = assertEquals(
        listOf("it" to null, "it" to null, "en" to null),
        labels(
            track("it", MimeTypes.AUDIO_AC3, 2), // the main track being Dolby needs no label
            track("und", MimeTypes.AUDIO_MPEG_L2, 1),
            track("en", MimeTypes.AUDIO_MPEG_L2, 2),
        ),
    )

    @Test
    fun singleTracksKeepJustTheLanguage() = assertEquals(
        listOf("en" to null, "it" to null),
        labels(track("en", MimeTypes.AUDIO_AAC, 2), track("it", MimeTypes.AUDIO_E_AC3, 2)),
    )

    @Test
    fun noLanguageAnywhere() = assertEquals(
        listOf(null to null, null to null),
        labels(track(null, MimeTypes.AUDIO_MPEG_L2, 2), track("und", MimeTypes.AUDIO_MPEG_L2, 1)),
    )

    @Test
    fun lookalikesGetANumber() = assertEquals(
        listOf("it" to null, "it" to "Italian · 2", "it" to null, "it" to "Italian · 4"),
        labels(
            track("it", MimeTypes.AUDIO_MPEG_L2, 2),
            track("und", MimeTypes.AUDIO_MPEG_L2, 2),
            track("und", MimeTypes.AUDIO_MPEG_L2, 1),
            track("und", MimeTypes.AUDIO_MPEG_L2, 1),
        ),
    )

    @Test
    fun passthrough() {
        assertEquals("Italian · Dolby Digital (passthrough)", AudioLabels.passthroughLabel("it", track("it", MimeTypes.AUDIO_AC3, 2), words))
        assertEquals("Italian · Dolby Digital Plus 5.1 (passthrough)", AudioLabels.passthroughLabel("it", track("it", MimeTypes.AUDIO_E_AC3, 6), words))
    }
}
