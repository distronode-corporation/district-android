package com.distronode.districtai.ui.report

import com.distronode.districtai.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the support desk receives, pinned.
 *
 * ⛔ THE PRIVACY RULE IS THE POINT: an address-keyed thread is keyed by the other party's phone
 * number or email address, and no line of the message may carry it.
 */
class ContentReportTest {

    private val words = ReportWords(subject = "S", preamble = "P", noNote = "N") { reference ->
        when (reference) {
            is ReportReference.Call -> "Call: ${reference.callId}"
            is ReportReference.Contact -> "Contact: ${reference.contactId}"
            ReportReference.Conversation -> "Conversation"
            ReportReference.HqAnswer -> "District HQ answer"
        }
    }

    @Test
    fun `a call is named by id, with the note trimmed`() {
        assertEquals(
            "P\nCall: call-1\n\nwrong name",
            ContentReport.message(ReportTarget.Call("call-1"), "  wrong name \n", words)
        )
    }

    @Test
    fun `a blank note says none was added`() {
        assertEquals("P\nDistrict HQ answer\n\nN", ContentReport.message(ReportTarget.HqAnswer, "   ", words))
    }

    @Test
    fun `a contact's thread names the contact, and an address's thread names nothing but the kind`() {
        assertEquals(
            "P\nConversation\nContact: c1\n\nN",
            ContentReport.message(ReportTarget.Conversation("contact:c1", "Ada"), "", words),
        )
        val address = ContentReport.message(ReportTarget.Conversation("addr:+14165550142", "+14165550142"), "", words)
        assertEquals("P\nConversation\n\nN", address)
    }

    @Test
    fun `the note is cut to the limit`() {
        val message = ContentReport.message(ReportTarget.HqAnswer, "x".repeat(ContentReport.NOTE_LIMIT + 50), words)
        assertEquals("P\nDistrict HQ answer\n\n" + "x".repeat(ContentReport.NOTE_LIMIT), message)
    }

    @Test
    fun `the heading follows the target`() {
        assertEquals(R.string.report_headline_call, ContentReport.headline(ReportTarget.Call("c")))
        assertEquals(R.string.report_headline_hq, ContentReport.headline(ReportTarget.HqAnswer))
        assertEquals(
            R.string.report_headline_conversation,
            ContentReport.headline(ReportTarget.Conversation("k", null))
        )
        assertEquals(R.string.report_headline_conversation, ContentReport.headline(ReportTarget.Conversation("k", " ")))
        assertEquals(
            R.string.report_headline_conversation_with,
            ContentReport.headline(ReportTarget.Conversation("k", "Ada"))
        )
    }
}
