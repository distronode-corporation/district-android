package com.distronode.districtai.ui.scheduling.admin.eventtypes

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.core.model.SchedulingTestEmailResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.schedulingEventType
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four event-type write flows against the fake scheduler, one request per press. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingEventTypeWritesTest {

    private val offline = ApiResult.NetworkFailure(IOException("down"))
    private val rig = SchedulingAdminTestRig()
    private var changed = 0
    private var deleted = 0

    private fun TestScope.writes() =
        SchedulingEventTypeWrites(this, rig.adminRepository, "ws", { changed++ }, { deleted++ })

    private fun params(op: SchedulingAdminOp, index: Int = 0): JsonObject = rig.admin.callsTo(op)[index].params

    private val SchedulingEventTypeWrites.open: EventTypeDialog? get() = dialog.value

    @Test
    fun `the dialog host closes only what is not working and ignores a late answer for another dialog`() {
        val host = EventTypeDialogHost()
        host.close()
        assertNull(host.current)
        host.show(EventTypeDialog.Actions(fullEventType(), state = WriteState.Working))
        host.close()
        assertTrue(host.current is EventTypeDialog.Actions)
        host.update(EventTypeDialog.Hosts::class, "intro") { null }
        host.update(EventTypeDialog.Actions::class, "other") { null }
        assertTrue(host.current is EventTypeDialog.Actions)
        host.update(EventTypeDialog.Actions::class, "intro") { it.copy(state = WriteState.Idle) }
        host.close()
        assertNull(host.current)
        host.update(EventTypeDialog.Actions::class, "intro") { it }
        assertNull(host.current)
    }

    @Test
    fun `a create refuses an empty name, then sends four keys under a slug unique on screen`() = runTest {
        val writes = writes()
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_CREATE, schedulingEventType(slug = "intro-2"))
        writes.editor.save()
        writes.editor.edit { it }
        writes.editor.openCreate(listOf("intro"))
        writes.editor.save()
        val refused = writes.open as EventTypeDialog.Editor
        assertEquals(R.string.scheduling_admin_event_types_name_missing, refused.validation)
        writes.editor.edit { it.copy(name = "Intro") }
        writes.editor.save()
        writes.editor.save()
        writes.editor.edit { it.copy(name = "ignored while working") }
        writes.close()
        assertEquals(WriteState.Working, writes.open?.state)
        advanceUntilIdle()
        assertNull(writes.open)
        assertEquals(1, changed)
        val sent = params(SchedulingAdminOp.EVENT_TYPES_CREATE)
        assertEquals(setOf("slug", "name", "duration_minutes", "location_type"), sent.keys)
        assertEquals(JsonPrimitive("intro-2"), sent["slug"])
        assertEquals(JsonPrimitive("Intro"), sent["name"])
        assertEquals(JsonPrimitive("livekit"), sent["location_type"])
    }

    @Test
    fun `an edit patches every field, and a failure stays until the form changes`() = runTest {
        val writes = writes()
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_PATCH,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(fullEventType()),
        )
        writes.editor.openEdit(fullEventType())
        writes.editor.edit { it.copy(interval = "0") }
        writes.editor.save()
        assertEquals(
            R.string.scheduling_admin_event_types_at_least_one_minute,
            (writes.open as EventTypeDialog.Editor).validation,
        )
        writes.editor.edit { it.copy(interval = "15") }
        writes.editor.save()
        advanceUntilIdle()
        val failed = writes.open as EventTypeDialog.Editor
        assertEquals(
            R.string.scheduling_admin_failure_offline,
            (failed.state as WriteState.Failed).failure.message.resourceIdOrNull,
        )
        assertEquals("in_person", failed.storedLocation)
        writes.editor.edit { it.copy(locationValue = "") }
        assertEquals(WriteState.Idle, writes.open?.state)
        writes.editor.save()
        advanceUntilIdle()
        assertNull(writes.open)
        val sent = params(SchedulingAdminOp.EVENT_TYPES_PATCH, index = 1)
        assertEquals(JsonPrimitive("intro"), sent["slug"])
        assertEquals(JsonPrimitive(""), sent["location_value"])
        assertEquals(JsonPrimitive(false), sent["is_public"])
        assertNull(sent["archived"])
        assertEquals(1, changed)
    }

    @Test
    fun `actions turn off and on, archive and restore, each with its sentence`() = runTest {
        val writes = writes()
        val row = fullEventType()
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_PATCH,
            FakeSchedulingAnswer.Value(row.copy(isActive = false)),
            FakeSchedulingAnswer.Value(row.copy(isActive = true)),
            FakeSchedulingAnswer.Value(row.copy(archived = true)),
            FakeSchedulingAnswer.Value(row.copy(archived = false)),
            FakeSchedulingAnswer.Failed(offline),
        )
        writes.actions.open(row)
        val sentences = listOf(
            writes.actions::toggleActive to R.string.scheduling_admin_event_types_turned_off,
            writes.actions::toggleActive to R.string.scheduling_admin_event_types_turned_on,
            writes.actions::toggleArchived to R.string.scheduling_admin_event_types_archived_done,
            writes.actions::toggleArchived to R.string.scheduling_admin_event_types_restored,
        )
        sentences.forEach { (press, sentence) ->
            press()
            press()
            advanceUntilIdle()
            assertEquals(WriteState.Done(UiText.Resource(sentence)), writes.open?.state)
        }
        assertEquals(4, rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_PATCH).size)
        assertEquals(JsonPrimitive(false), params(SchedulingAdminOp.EVENT_TYPES_PATCH)["is_active"])
        assertEquals(JsonPrimitive(true), params(SchedulingAdminOp.EVENT_TYPES_PATCH, index = 2)["archived"])
        assertEquals(4, changed)
        writes.actions.toggleActive()
        advanceUntilIdle()
        assertTrue(writes.open?.state is WriteState.Failed)
        assertEquals(4, changed)
    }

    @Test
    fun `a test email names the address the scheduler chose, or says it was not sent`() = runTest {
        val writes = writes()
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_TEST_EMAIL,
            FakeSchedulingAnswer.Value(SchedulingTestEmailResult(sent = true, to = "ana@example.com")),
            FakeSchedulingAnswer.Value(SchedulingTestEmailResult(sent = false, to = "")),
            FakeSchedulingAnswer.Failed(offline),
        )
        writes.actions.open(fullEventType())
        writes.actions.sendTestEmail("reminder")
        writes.actions.sendTestEmail("reminder")
        advanceUntilIdle()
        val sent = UiText.Resource(R.string.scheduling_admin_event_types_test_email_sent, "ana@example.com")
        assertEquals(WriteState.Done(sent), writes.open?.state)
        assertEquals(JsonPrimitive("reminder"), params(SchedulingAdminOp.EVENT_TYPES_TEST_EMAIL)["type"])
        writes.actions.sendTestEmail("confirmation")
        advanceUntilIdle()
        val notSent = UiText.Resource(R.string.scheduling_admin_event_types_test_email_not_sent)
        assertEquals(WriteState.Done(notSent), writes.open?.state)
        writes.actions.sendTestEmail("confirmation")
        advanceUntilIdle()
        assertTrue(writes.open?.state is WriteState.Failed)
    }

    @Test
    fun `delete asks first, a refusal stays on screen, and a delete that landed closes and goes back`() = runTest {
        val writes = writes()
        listOf(
            writes.actions::toggleActive,
            writes.actions::toggleArchived,
            writes.actions::delete,
            { writes.actions.askDelete(true) },
            { writes.actions.sendTestEmail("reminder") },
        ).forEach { it() }
        assertNull(writes.open)
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_DELETE,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(SchedulingNoContent(ok = true)),
        )
        writes.actions.open(fullEventType())
        writes.actions.askDelete(true)
        assertTrue((writes.open as EventTypeDialog.Actions).confirmingDelete)
        writes.actions.askDelete(false)
        assertFalse((writes.open as EventTypeDialog.Actions).confirmingDelete)
        writes.actions.delete()
        advanceUntilIdle()
        assertTrue(writes.open?.state is WriteState.Failed)
        assertEquals(0, deleted)
        writes.actions.delete()
        advanceUntilIdle()
        assertNull(writes.open)
        assertEquals(1, deleted)
    }

    @Test
    fun `hosts read the list and the directory, then save the whole list and the routing`() = runTest {
        val writes = writes()
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_HOSTS_GET, SchedulingItems(listOf(schedulingHost())))
        rig.admin.value(
            SchedulingAdminOp.USERS_LIST,
            listOf(schedulerUser("u1"), schedulerUser("u2", "Bo"), schedulerUser("u3", archived = true)),
        )
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT, SchedulingItems(listOf(schedulingHost("u2"))))
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_PATCH, fullEventType())
        writes.hosts.open(fullEventType())
        writes.hosts.save()
        advanceUntilIdle()
        val loaded = writes.open as EventTypeDialog.Hosts
        assertEquals("round_robin", loaded.routingMode)
        assertEquals("priority", loaded.rotationStrategy)
        assertEquals(listOf("u2"), loaded.candidates.map { it.id })
        assertTrue(loaded.canAddHosts)
        writes.hosts.add("nobody")
        writes.hosts.add("u2")
        writes.hosts.editRow("u2") { it.copy(priority = "x") }
        writes.hosts.save()
        assertEquals(
            R.string.scheduling_admin_event_types_host_priority_invalid,
            (writes.open as EventTypeDialog.Hosts).validation,
        )
        writes.hosts.editRow("u2") { it.copy(priority = "1", role = "rotation") }
        writes.hosts.setRouting("collective")
        writes.hosts.setRotation("soonest")
        writes.hosts.remove("u1")
        writes.hosts.save()
        writes.hosts.save()
        writes.hosts.setRouting("fixed")
        advanceUntilIdle()
        assertNull(writes.open)
        assertEquals(1, changed)
        val hosts = params(SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT)["hosts"] as JsonArray
        assertEquals(
            JsonObject(
                mapOf(
                    "user_id" to JsonPrimitive("u2"),
                    "role" to JsonPrimitive("rotation"),
                    "priority" to JsonPrimitive(1),
                ),
            ),
            hosts.single(),
        )
        val routing = params(SchedulingAdminOp.EVENT_TYPES_PATCH)
        assertEquals(JsonPrimitive("collective"), routing["routing_mode"])
        assertEquals(JsonPrimitive("soonest"), routing["rr_strategy"])
    }

    @Test
    fun `hosts default the routing, refuse an empty list, and report each failure`() = runTest {
        val writes = writes()
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_HOSTS_GET,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(SchedulingItems(listOf(schedulingHost()))),
        )
        rig.admin.fail(SchedulingAdminOp.USERS_LIST, offline)
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(SchedulingItems(listOf(schedulingHost()))),
        )
        rig.admin.fail(SchedulingAdminOp.EVENT_TYPES_PATCH, offline)
        writes.hosts.load()
        writes.hosts.open(schedulingEventType())
        advanceUntilIdle()
        val failed = writes.open as EventTypeDialog.Hosts
        assertEquals("fixed", failed.routingMode)
        assertEquals("even", failed.rotationStrategy)
        assertTrue(failed.load is SectionState.Failed)
        writes.hosts.load()
        advanceUntilIdle()
        val loaded = writes.open as EventTypeDialog.Hosts
        assertFalse(loaded.canAddHosts)
        assertEquals(emptyList<Any>(), loaded.candidates)
        writes.hosts.save()
        advanceUntilIdle()
        assertTrue(writes.open?.state is WriteState.Failed)
        assertEquals(0, rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_PATCH).size)
        writes.hosts.save()
        advanceUntilIdle()
        assertTrue(writes.open?.state is WriteState.Failed)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_PATCH).size)
        writes.hosts.remove("u1")
        writes.hosts.save()
        assertEquals(
            R.string.scheduling_admin_event_types_hosts_need_one,
            (writes.open as EventTypeDialog.Hosts).validation,
        )
        assertEquals(0, changed)
        writes.close()
        writes.hosts.save()
        writes.hosts.remove("u1")
        assertNull(writes.open)
    }

    @Test
    fun `questions read in position order, add at the end, edit, and delete after asking`() = runTest {
        val writes = writes()
        val first = schedulingQuestion(id = "q1", position = 0)
        val second = schedulingQuestion(id = "q2", label = "Topic", position = 1)
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST, SchedulingItems(listOf(second, first)))
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE, first)
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_PATCH, first)
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE, SchedulingNoContent(ok = true))
        writes.questions.open("intro")
        advanceUntilIdle()
        assertEquals(listOf("q1", "q2"), (writes.open as EventTypeDialog.Questions).questions.map { it.id })

        writes.questions.submit()
        writes.questions.editForm { it }
        writes.questions.beginEdit(null)
        assertEquals("2", (writes.open as EventTypeDialog.Questions).editing?.position)
        writes.questions.submit()
        assertEquals(
            R.string.scheduling_admin_event_types_question_label_missing,
            (writes.open as EventTypeDialog.Questions).validation,
        )
        writes.questions.editForm { it.copy(label = "Budget", type = "select", optionDraft = "Low").withOption() }
        writes.questions.submit()
        writes.questions.submit()
        advanceUntilIdle()
        val added = writes.open as EventTypeDialog.Questions
        assertNull(added.editing)
        assertEquals(
            WriteState.Done(UiText.Resource(R.string.scheduling_admin_event_types_question_added)),
            added.state,
        )
        val created = params(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE)
        assertEquals(JsonPrimitive(2), created["position"])
        assertEquals(JsonArray(listOf(JsonPrimitive("Low"))), created["options"])
        assertEquals(1, changed)

        writes.questions.beginEdit(second)
        writes.questions.editForm { it.copy(label = " ") }
        writes.questions.submit()
        assertEquals(
            R.string.scheduling_admin_event_types_question_label_missing,
            (writes.open as EventTypeDialog.Questions).validation,
        )
        writes.questions.editForm { it.copy(label = "Topic", position = "0") }
        writes.questions.submit()
        advanceUntilIdle()
        assertEquals(
            WriteState.Done(UiText.Resource(R.string.scheduling_admin_event_types_question_saved)),
            writes.open?.state,
        )
        assertEquals(JsonPrimitive("q2"), params(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_PATCH)["id"])

        writes.questions.delete()
        writes.questions.confirmDelete(first)
        writes.questions.confirmDelete(null)
        writes.questions.delete()
        assertEquals(0, rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE).size)
        writes.questions.confirmDelete(first)
        writes.questions.delete()
        advanceUntilIdle()
        assertEquals(
            WriteState.Done(UiText.Resource(R.string.scheduling_admin_event_types_question_deleted)),
            writes.open?.state,
        )
        assertEquals(JsonPrimitive("q1"), params(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE)["id"])
        assertEquals(3, changed)
    }

    @Test
    fun `a question write that fails stays in its form, and a failed re-read keeps the list`() = runTest {
        val writes = writes()
        val first = schedulingQuestion()
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(SchedulingItems(listOf(first))),
            FakeSchedulingAnswer.Failed(offline),
        )
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_QUESTIONS_PATCH,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(first),
        )
        rig.admin.fail(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE, offline)
        listOf(
            writes.questions::load,
            writes.questions::cancelEdit,
            writes.questions::submit,
            writes.questions::delete,
            { writes.questions.beginEdit(null) },
            { writes.questions.confirmDelete(first) },
            { writes.questions.editForm { it } },
        ).forEach { it() }
        assertNull(writes.open)
        writes.questions.open("intro")
        advanceUntilIdle()
        assertTrue((writes.open as EventTypeDialog.Questions).load is SectionState.Failed)
        writes.questions.load()
        advanceUntilIdle()
        writes.questions.beginEdit(first)
        writes.questions.submit()
        advanceUntilIdle()
        val failed = writes.open as EventTypeDialog.Questions
        assertTrue(failed.state is WriteState.Failed)
        assertEquals("q1", failed.editing?.id)
        writes.questions.editForm { it.copy(required = true) }
        assertEquals(WriteState.Idle, writes.open?.state)
        writes.questions.cancelEdit()
        assertNull((writes.open as EventTypeDialog.Questions).editing)
        writes.questions.beginEdit(first)
        writes.questions.submit()
        advanceUntilIdle()
        val stale = writes.open as EventTypeDialog.Questions
        assertTrue(stale.load is SectionState.Failed)
        assertEquals(listOf<SchedulingQuestion>(first), stale.questions)
        assertEquals(0, changed)
        writes.questions.confirmDelete(first)
        writes.questions.delete()
        advanceUntilIdle()
        assertTrue(writes.open?.state is WriteState.Failed)
    }
}
