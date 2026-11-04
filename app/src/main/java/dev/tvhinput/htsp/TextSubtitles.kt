package dev.tvhinput.htsp

import android.text.Html
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CueEncoder

/**
 * tvheadend's "TEXTSUB" streams: teletext subtitles (page 777 & co.) that tvheadend already turned
 * into UTF-8 text, one packet per update, with the teletext colours as `<font color=...>` (they
 * tell speakers apart) and rows separated by newlines. They become Media3 cues here, so the
 * player's text renderer shows them like the DVB ones.
 *
 * Packets carry no duration and each one replaces the previous (live subtitles grow word by word);
 * an empty one clears the screen. A subtitle left without a follow-up is taken down after
 * [MAX_DURATION_US], so a missed clear can't leave a line on screen for good.
 */
@OptIn(UnstableApi::class)
internal class TextSubtitles {

    private val encoder = CueEncoder()

    /** The subtitle in `data[offset, offset + size)` as an encoded cue sample. */
    fun encode(data: ByteArray, offset: Int, size: Int, durationUs: Long): ByteArray {
        val markup = textOf(data, offset, size)
        val cues = if (markup.isEmpty()) emptyList() else listOf(
            Cue.Builder()
                .setText(Html.fromHtml(markup.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY).trimEnd())
                .build()
        )
        return encoder.encode(cues, if (durationUs > 0) durationUs else MAX_DURATION_US)
    }

    companion object {
        const val MAX_DURATION_US = 10_000_000L

        /**
         * The subtitle's rows, as tvheadend's markup, without teletext padding (trailing spaces,
         * empty rows, NULs). A colour change takes a character cell in teletext, shown as a space,
         * which tvheadend drops ("condanna<font ...>- Sì"): it is put back.
         */
        fun textOf(data: ByteArray, offset: Int, size: Int): String =
            String(data, offset, size, Charsets.UTF_8)
                .replace("\u0000", "")
                .lines()
                .map { it.replace(GLUED_TAG, "$1 <font").trim() }
                .filter { it.replace(TAG, "").isNotBlank() }
                .joinToString("\n")

        /** A colour change right after text or after the previous colour's end. */
        private val GLUED_TAG = Regex("""(\S)<font""")
        private val TAG = Regex("<[^>]*>")
    }
}
