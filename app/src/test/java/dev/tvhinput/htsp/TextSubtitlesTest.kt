package dev.tvhinput.htsp

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSubtitlesTest {

    private fun text(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return TextSubtitles.textOf(bytes, 0, bytes.size)
    }

    @Test
    fun keepsRowsDropsPadding() = assertEquals(
        "Perché non sei venuto?\n— Non potevo.",
        text("  Perché non sei venuto?   \n\n   — Non potevo.  \n   \u0000"),
    )

    @Test
    fun colourChangeKeepsItsSpace() = assertEquals(
        "stato avanzato\ncondanna <font color=\"#ffff00\">- Sì</font>",
        text("stato avanzato\ncondanna<font color=\"#ffff00\">- Sì</font>"),
    )

    @Test
    fun colourAfterColourKeepsItsSpace() = assertEquals(
        "<font color=\"#00ff00\">quello.</font> <font color=\"#ffff00\">- Non</font>",
        text("<font color=\"#00ff00\">quello.</font><font color=\"#ffff00\">- Non</font>"),
    )

    @Test
    fun rowsWithOnlyTagsAreDropped() = assertEquals(
        "<font color=\"#ffff00\">Trump</font>",
        text("<font color=\"#ffff00\"> </font>\n<font color=\"#ffff00\">Trump</font>"),
    )

    @Test
    fun blankIsEmpty() = assertEquals("", text(" \n  \n"))
}
