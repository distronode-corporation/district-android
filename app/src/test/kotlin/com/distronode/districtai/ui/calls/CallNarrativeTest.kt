package com.distronode.districtai.ui.calls

import com.distronode.districtai.l10n.WIRE_PLACEHOLDERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a call's `aiSummary` is carrying. The same cases as district-core-swift's `CallSentinelTests`.
 */
class CallNarrativeTest {

    @Test
    fun `every summary placeholder the service writes reads as no summary`() {
        listOf(
            "No summary available.",
            "AI Voice session active...",
            "AI Outbound Voice session active...",
            "Voice session completed.",
            "No answer, no conversation took place.",
            "direct:softphone",
        ).forEach { assertEquals(it, CallNarrative.Absent, callNarrative(it)) }
    }

    @Test
    fun `both spellings of the no-answer sentence read as no summary`() {
        assertEquals(CallNarrative.Absent, callNarrative("No answer, no conversation took place."))
        // ⚠️ The earlier spelling, still held by rows stored before the server changed it; it arrives
        // through the vocabulary's `aliases`, not a literal in the app.
        assertEquals(CallNarrative.Absent, callNarrative(NO_ANSWER_DASH))
    }

    @Test
    fun `a placeholder with padding, and a blank field, read as no summary`() {
        assertEquals(CallNarrative.Absent, callNarrative("  No summary available.\n"))
        assertEquals(CallNarrative.Absent, callNarrative(""))
        assertEquals(CallNarrative.Absent, callNarrative("   "))
    }

    @Test
    fun `the summariser's failure sentences read as failed, not absent`() {
        assertEquals(CallNarrative.Failed, callNarrative("Error processing audio with AI."))
        assertEquals(CallNarrative.Failed, callNarrative("AI analysis failed to execute."))
    }

    @Test
    fun `a real summary passes through, trimmed`() {
        assertEquals(CallNarrative.Summary("Booked an appointment."), callNarrative(" Booked an appointment. "))
    }

    @Test
    fun `the match is exact, so a summary that merely mentions a placeholder is kept`() {
        val text = "Caller said: No summary available. Then booked Friday."
        assertEquals(CallNarrative.Summary(text), callNarrative(text))
    }

    @Test
    fun `every summary placeholder but the dial marker is a vocabulary placeholder or one of its spellings`() {
        val current = SUMMARY_PLACEHOLDERS.filter { it in WIRE_PLACEHOLDERS }
        assertEquals(5, current.size)
        assertTrue("direct:softphone" in SUMMARY_PLACEHOLDERS)
        assertTrue(NO_ANSWER_DASH in SUMMARY_PLACEHOLDERS)
        assertEquals(7, SUMMARY_PLACEHOLDERS.size)
    }
}

private const val NO_ANSWER_DASH = "No answer \u2014 no conversation took place."
