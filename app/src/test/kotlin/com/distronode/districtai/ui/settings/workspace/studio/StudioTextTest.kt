package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The text this client builds from the server's templates: the unsaved-edit meter and the
 * "Based on" line, in English and in French.
 *
 * ⛔ THE FRENCH TEMPLATES BELOW ARE THE SPEC'S, copied as the server sends them (U+00A0 before the
 * unit and as the grouping value); nothing here translates.
 */
class StudioTextTest {

    private val en = VoiceStudioFixture.studio.labels
    private val fr = en.copy(
        meterAbout = "Environ {ms} ms",
        meterAtLeast = "Au moins {ms} ms",
        meterNone = "Pas encore mesuré",
        meterPartial = "Certaines étapes ne sont pas encore mesurées, donc le délai réel est plus long.",
        numberGrouping = " ",
        basedOnOne = "Basé sur {recipe}, 1 modification.",
        basedOnMany = "Basé sur {recipe}, {n} modifications.",
    )

    @Test
    fun `the read carries the templates and the grouping value`() {
        assertEquals("About {ms} ms", en.meterAbout)
        assertEquals("At least {ms} ms", en.meterAtLeast)
        assertEquals(",", en.numberGrouping)
        assertEquals("Based on {recipe}, {n} changes.", en.basedOnMany)
    }

    @Test
    fun `digits are grouped by threes from the right with the server's separator`() {
        val cases = listOf(7.0, 970.0, 1234.0, 12345.0, 1234567.0)
        assertEquals(
            listOf("7", "970", "1,234", "12,345", "1,234,567"),
            cases.map { StudioText.grouped(it, en.numberGrouping) },
        )
        assertEquals(
            listOf("7", "970", "1 234", "12 345", "1 234 567"),
            cases.map { StudioText.grouped(it, fr.numberGrouping) },
        )
    }

    @Test
    fun `the unsaved meter reads about, at least or not measured, in the portal locale`() {
        assertEquals("About 970 ms", StudioText.meter(MeterHeadline.Local(970.0, atLeast = false), en))
        assertEquals("At least 1,234 ms", StudioText.meter(MeterHeadline.Local(1234.0, atLeast = true), en))
        assertEquals("About 12,345 ms", StudioText.meter(MeterHeadline.Local(12345.0, atLeast = false), en))
        assertEquals(en.meterNone, StudioText.meter(MeterHeadline.None, en))

        assertEquals("Environ 970 ms", StudioText.meter(MeterHeadline.Local(970.0, atLeast = false), fr))
        assertEquals("Au moins 1 234 ms", StudioText.meter(MeterHeadline.Local(1234.0, atLeast = true), fr))
        assertEquals("Environ 12 345 ms", StudioText.meter(MeterHeadline.Local(12345.0, atLeast = false), fr))
        assertEquals("Pas encore mesuré", StudioText.meter(MeterHeadline.None, fr))

        assertEquals("Server words", StudioText.meter(MeterHeadline.Server("Server words"), fr))
    }

    @Test
    fun `the rule reproduces every meter sentence the read carries`() {
        val studio = VoiceStudioFixture.studio
        val meters = studio.recipes.map { it.timeToFirstWord }.map { Triple(it.ms, it.atLeast, it.text) } +
            Triple(studio.latency.ms, studio.latency.atLeast, studio.latency.text)
        meters.forEach { (ms, atLeast, text) ->
            val headline = ms?.let { MeterHeadline.Local(it, atLeast) } ?: MeterHeadline.None
            assertEquals(text, StudioText.meter(headline, en))
        }
    }

    @Test
    fun `a bare median takes the unit that follows the placeholder in meterAbout`() {
        assertEquals("1,234\u00a0ms", StudioText.millis(1234.0, en))
        assertEquals("12\u00a0345\u00a0ms", StudioText.millis(12345.0, fr))
        assertEquals("970 msec", StudioText.millis(970.0, en.copy(meterAbout = "About {ms} msec")))
    }

    @Test
    fun `based on counts one, many or nothing, with literal placeholders`() {
        assertNull(StudioText.basedOn(en, "Fastest", 0))
        assertEquals("Based on Fastest, 1 change.", StudioText.basedOn(en, "Fastest", 1))
        assertEquals("Based on Fastest, 1234 changes.", StudioText.basedOn(en, "Fastest", 1234))
        assertEquals("Basé sur Le plus rapide, 1 modification.", StudioText.basedOn(fr, "Le plus rapide", 1))
        assertEquals("Basé sur Le plus rapide, 2 modifications.", StudioText.basedOn(fr, "Le plus rapide", 2))
        // A recipe name is inserted as text, never read as a placeholder or a pattern.
        assertEquals("Based on {n} \$1, 3 changes.", StudioText.basedOn(en, "{n} \$1", 3))
    }
}
