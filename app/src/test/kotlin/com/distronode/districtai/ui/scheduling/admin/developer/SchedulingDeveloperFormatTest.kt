package com.distronode.districtai.ui.scheduling.admin.developer

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The webhook URL check, the private-host courtesy, the MCP address and the field defaults. */
class SchedulingDeveloperFormatTest {

    @Test
    fun `a webhook URL must be present, absolute, https and public`() {
        assertEquals(R.string.scheduling_admin_developer_webhook_url_missing, schedulingWebhookUrlIssue("  "))
        val notAUrl = R.string.scheduling_admin_developer_webhook_url_not_a_url
        assertEquals(notAUrl, schedulingWebhookUrlIssue("example.com"))
        assertEquals(notAUrl, schedulingWebhookUrlIssue("https://a b.example.com"))
        assertEquals(notAUrl, schedulingWebhookUrlIssue("https:///path"))
        val notHttps = R.string.scheduling_admin_developer_webhook_url_not_https
        assertEquals(notHttps, schedulingWebhookUrlIssue("http://hooks.example.com"))
        assertEquals(notHttps, schedulingWebhookUrlIssue("ftp://hooks.example.com"))
        val private = R.string.scheduling_admin_developer_webhook_url_private
        listOf(
            "https://localhost/in",
            "https://printer.local",
            "https://0.0.0.0",
            "https://[::1]:8443/in",
            "https://127.0.0.1",
            "https://10.1.2.3",
            "https://192.168.0.10",
            "https://169.254.1.1",
            "https://172.16.0.1",
            "https://172.31.255.255",
        ).forEach { assertEquals(it, private, schedulingWebhookUrlIssue(it)) }
        listOf(
            "https://hooks.example.com/in",
            "HTTPS://Hooks.Example.com",
            "https://172.2.0.1",
            "https://172.32.0.1",
            "https://173.20.0.1",
        ).forEach { assertNull(it, schedulingWebhookUrlIssue(it)) }
    }

    @Test
    fun `the private host test reads the bare spellings too`() {
        assertTrue(isPrivateWebhookHost("::1"))
        assertTrue(isPrivateWebhookHost("172.20.1.1"))
        assertFalse(isPrivateWebhookHost("172.x.1.1"))
        assertFalse(isPrivateWebhookHost("172"))
    }

    @Test
    fun `the MCP address needs a host`() {
        assertNull(schedulingMcpUrl(null))
        assertNull(schedulingMcpUrl("  "))
        assertEquals("https://acme-book.example/mcp", schedulingMcpUrl(" acme-book.example "))
    }

    @Test
    fun `a new webhook sends every field but the attendee's own, in the fork's order`() {
        val blank = SchedulingWebhookDraft.blank()
        assertEquals("", blank.url)
        assertTrue(blank.events.isEmpty())
        assertEquals(SchedulingWebhookFields.ALL.size - SchedulingWebhookFields.ATTENDEE.size, blank.fields.size)
        assertFalse("attendee_email" in blank.fields)
        assertTrue("host_email" in blank.fields)
        assertTrue(SchedulingWebhookFields.PERSONAL_DATA.containsAll(listOf("host_name", "answers")))
        val draft = blank.copy(
            events = setOf(SchedulingWebhookEvent.NOTES_READY, SchedulingWebhookEvent.BOOKING_CREATED),
            fields = setOf("end_at", "id"),
        )
        assertEquals(
            listOf(SchedulingWebhookEvent.BOOKING_CREATED, SchedulingWebhookEvent.NOTES_READY),
            draft.orderedEvents,
        )
        assertEquals(listOf("id", "end_at"), draft.orderedFields)
    }

    @Test
    fun `a revealed secret never prints its value`() {
        val secret = SchedulingRevealedSecret("Zapier", "cno_live_x")
        assertEquals("SchedulingRevealedSecret(label=Zapier)", secret.toString())
    }
}
