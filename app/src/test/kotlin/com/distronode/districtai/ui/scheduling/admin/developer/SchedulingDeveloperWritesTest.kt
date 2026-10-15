package com.distronode.districtai.ui.scheduling.admin.developer

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingApiKeyCreated
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingWebhook
import com.distronode.districtai.core.model.SchedulingWebhookCreated
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.WriteState
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Creating a key and adding or editing a webhook: what each sends, the secret shown once, and the
 * one-request rule, as district-ios's create models behave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingDeveloperWritesTest {

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private var changed = 0
    private val writes = SchedulingDeveloperWrites(scope, rig.adminRepository, "ws") { changed++ }

    private val offline = ApiResult.NetworkFailure(IOException("x"))

    private fun hook(events: List<String> = listOf("booking.created"), fields: List<String>? = null) =
        SchedulingWebhook(id = "wh1", url = "https://hooks.example.com/in", events = events, fields = fields)

    private val keyDialog get() = writes.dialog.value as DeveloperDialog.CreateKey
    private val hookDialog get() = writes.dialog.value as DeveloperDialog.Webhook

    private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    @Test
    fun `a key needs a name, is minted once, is shown once and is dropped on close`() {
        rig.admin.value(
            SchedulingAdminOp.API_KEYS_CREATE,
            SchedulingApiKeyCreated(id = "k9", name = "Zapier", key = "cno_live_secret"),
        )
        // Nothing open: every action is ignored.
        writes.close()
        writes.keys.editName("x")
        writes.keys.submit()
        writes.keys.markCopied()
        assertNull(writes.dialog.value)

        writes.keys.open()
        writes.keys.submit()
        assertTrue(keyDialog.nameMissing)
        writes.keys.editName("  Zapier  ")
        assertFalse(keyDialog.nameMissing)
        writes.keys.submit()
        writes.keys.submit()
        assertEquals(WriteState.Working, keyDialog.state)
        writes.close()
        assertTrue(writes.dialog.value is DeveloperDialog.CreateKey)
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.API_KEYS_CREATE)
        assertEquals(1, sent.size)
        assertEquals(JsonPrimitive("Zapier"), sent.single().params["name"])
        val minted = keyDialog.minted!!
        assertEquals("Zapier", minted.label)
        assertEquals("cno_live_secret", minted.value)
        assertFalse(minted.toString().contains("cno_live_secret"))
        assertFalse(keyDialog.toString().contains("cno_live_secret"))
        assertEquals("", keyDialog.name)
        assertEquals(1, changed)
        // A shown key cannot be minted again from the same dialog.
        writes.keys.submit()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.API_KEYS_CREATE).size)
        writes.keys.markCopied()
        assertTrue(keyDialog.copied)
        writes.close()
        assertNull(writes.dialog.value)
    }

    @Test
    fun `a failed mint says why, and editing the name clears it`() {
        rig.admin.fail(SchedulingAdminOp.API_KEYS_CREATE, ApiResult.Forbidden("Forbidden"))
        writes.keys.open()
        writes.keys.editName("CI")
        writes.keys.submit()
        scope.advanceUntilIdle()
        val failed = keyDialog.state as WriteState.Failed
        assertEquals(R.string.scheduling_admin_failure_forbidden, failed.failure.message.resourceIdOrNull)
        assertNull(keyDialog.minted)
        assertEquals(0, changed)
        writes.keys.editName("CI 2")
        assertEquals(WriteState.Idle, keyDialog.state)
        writes.keys.editName("CI 3")
        assertEquals(WriteState.Idle, keyDialog.state)
    }

    @Test
    fun `a new webhook checks its URL and events, sends both lists in the fork's order and shows the secret`() {
        rig.admin.value(
            SchedulingAdminOp.WEBHOOKS_CREATE,
            SchedulingWebhookCreated(
                id = "wh2",
                url = "https://hooks.example.com/in",
                events = listOf("booking.created"),
                secret = "whsec_1",
            ),
        )
        writes.webhooks.openCreate()
        assertEquals(SchedulingWebhookFields.DEFAULTS, hookDialog.draft.fields)
        assertTrue(hookDialog.canSubmit)
        writes.webhooks.submit()
        assertEquals(R.string.scheduling_admin_developer_webhook_url_missing, hookDialog.urlIssue)
        assertTrue(hookDialog.eventsMissing)
        writes.webhooks.editUrl("http://hooks.example.com/in")
        assertNull(hookDialog.urlIssue)
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_CANCELLED, on = true)
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_CREATED, on = true)
        assertFalse(hookDialog.eventsMissing)
        writes.webhooks.submit()
        assertEquals(R.string.scheduling_admin_developer_webhook_url_not_https, hookDialog.urlIssue)
        writes.webhooks.editUrl(" https://hooks.example.com/in ")
        writes.webhooks.toggleField("attendee_email", on = true)
        writes.webhooks.toggleField("id", on = false)
        writes.webhooks.submit()
        writes.webhooks.submit()
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.NOTES_READY, on = true)
        assertEquals(WriteState.Working, hookDialog.state)
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_CREATE).single().params
        assertEquals(JsonPrimitive("https://hooks.example.com/in"), sent["url"])
        assertEquals(strings("booking.created", "booking.cancelled"), sent["events"])
        val fields = (sent["fields"] as JsonArray).map { (it as JsonPrimitive).content }
        val expected = SchedulingWebhookFields.DEFAULTS - "id" + "attendee_email"
        assertEquals(SchedulingWebhookFields.ALL.filter { it in expected }, fields)
        assertEquals("whsec_1", hookDialog.secret?.value)
        assertEquals("https://hooks.example.com/in", hookDialog.secret?.label)
        assertEquals(1, changed)
        writes.webhooks.submit()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_CREATE).size)
        writes.close()
        assertNull(writes.dialog.value)
    }

    @Test
    fun `a create that minted no secret says the webhook was added and cannot be added twice`() {
        rig.admin.value(
            SchedulingAdminOp.WEBHOOKS_CREATE,
            SchedulingWebhookCreated(id = "wh2", url = "https://a.example.com", events = listOf("booking.created")),
        )
        writes.webhooks.openCreate()
        writes.webhooks.editUrl("https://a.example.com")
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_CREATED, on = true)
        writes.webhooks.submit()
        scope.advanceUntilIdle()
        val done = hookDialog.state as WriteState.Done
        assertEquals(R.string.scheduling_admin_developer_webhook_added, done.message.resourceIdOrNull)
        assertNull(hookDialog.secret)
        assertFalse(hookDialog.canSubmit)
        writes.webhooks.submit()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_CREATE).size)
    }

    @Test
    fun `an edit keeps the URL, names the events it would drop, and patches events and fields only`() {
        rig.admin.value(SchedulingAdminOp.WEBHOOKS_PATCH, SchedulingNoContent(ok = true))
        val row = hook(events = listOf("booking.created", "booking.paid"), fields = listOf("id", "start_at"))
        writes.webhooks.openEdit(row)
        assertEquals(setOf(SchedulingWebhookEvent.BOOKING_CREATED), hookDialog.draft.events)
        assertEquals(setOf("id", "start_at"), hookDialog.draft.fields)
        assertEquals(listOf("booking.paid"), hookDialog.droppedEvents)
        // The URL is not checked on an edit: it is not sent.
        writes.webhooks.editUrl("not a url")
        writes.webhooks.submit()
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_PATCH).single().params
        assertEquals(JsonPrimitive("wh1"), sent["id"])
        assertNull(sent["url"])
        assertEquals(strings("booking.created"), sent["events"])
        assertEquals(strings("id", "start_at"), sent["fields"])
        val done = hookDialog.state as WriteState.Done
        assertEquals(R.string.scheduling_admin_developer_webhook_updated, done.message.resourceIdOrNull)
        assertTrue(hookDialog.canSubmit)
        assertEquals(1, changed)
        // A row with no field list opens on the default set, not on nothing.
        writes.webhooks.openEdit(hook())
        assertEquals(SchedulingWebhookFields.DEFAULTS, hookDialog.draft.fields)
        assertEquals(emptyList<String>(), hookDialog.droppedEvents)
    }

    @Test
    fun `an edit keeps an event the screen does not offer, and only the offered four can be ticked`() {
        rig.admin.value(SchedulingAdminOp.WEBHOOKS_PATCH, SchedulingNoContent(ok = true))
        val row = hook(events = listOf("notes.ready", "booking.created", "recording.completed", "booking.paid"))
        writes.webhooks.openEdit(row)
        assertEquals(
            setOf(
                SchedulingWebhookEvent.BOOKING_CREATED,
                SchedulingWebhookEvent.RECORDING_COMPLETED,
                SchedulingWebhookEvent.NOTES_READY,
            ),
            hookDialog.draft.events,
        )
        // Only the unknown name is dropped (the scheduler would refuse it); the known ones are kept.
        assertEquals(listOf("booking.paid"), hookDialog.droppedEvents)
        // A screen offers no tick for these three, and a call that names one changes nothing.
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.NOTES_READY, on = false)
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.TRANSCRIPT_READY, on = true)
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_CREATED, on = false)
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_REMINDER, on = true)
        writes.webhooks.submit()
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_PATCH).single().params
        assertEquals(strings("booking.reminder", "recording.completed", "notes.ready"), sent["events"])

        // A row holding only an event the screen does not offer saves it back as it is.
        writes.webhooks.openEdit(hook(events = listOf("transcript.ready")))
        writes.webhooks.submit()
        scope.advanceUntilIdle()
        val kept = rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_PATCH).last().params
        assertEquals(strings("transcript.ready"), kept["events"])

        // A new webhook can subscribe only to the offered four.
        writes.webhooks.openCreate()
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.RECORDING_COMPLETED, on = true)
        assertTrue(hookDialog.draft.events.isEmpty())
        assertEquals(
            listOf("booking.created", "booking.cancelled", "booking.rescheduled", "booking.reminder"),
            SchedulingWebhookEvent.OFFERED.map { it.wire },
        )
    }

    @Test
    fun `failed webhook writes say why and editing clears the failure`() {
        rig.admin.fail(SchedulingAdminOp.WEBHOOKS_PATCH, offline)
        rig.admin.fail(SchedulingAdminOp.WEBHOOKS_CREATE, offline)
        writes.webhooks.openEdit(hook())
        writes.webhooks.submit()
        scope.advanceUntilIdle()
        val failed = hookDialog.state as WriteState.Failed
        assertEquals(R.string.scheduling_admin_failure_offline, failed.failure.message.resourceIdOrNull)
        writes.webhooks.toggleField("status", on = false)
        assertEquals(WriteState.Idle, hookDialog.state)
        assertEquals(0, changed)

        writes.webhooks.openCreate()
        writes.webhooks.editUrl("https://a.example.com")
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_REMINDER, on = true)
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_REMINDER, on = false)
        writes.webhooks.submit()
        assertTrue(hookDialog.eventsMissing)
        writes.webhooks.toggleEvent(SchedulingWebhookEvent.BOOKING_REMINDER, on = true)
        writes.webhooks.submit()
        scope.advanceUntilIdle()
        assertTrue(hookDialog.state is WriteState.Failed)
        // Another dialog kind open: the webhook actions leave it alone.
        writes.keys.open()
        writes.webhooks.editUrl("x")
        writes.webhooks.submit()
        assertTrue(writes.dialog.value is DeveloperDialog.CreateKey)
    }
}
