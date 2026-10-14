package com.distronode.districtai.ui.scheduling.admin.eventtypes

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAccess
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingEventType
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingEventTypesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val offline = ApiResult.NetworkFailure(IOException("down"))

    @Test
    fun `the list reads on demand with the public host, and create opens over the rows in hand`() =
        runTest(dispatcher) {
            val rig = SchedulingAdminTestRig(canManage = true)
            rig.admin.value(SchedulingAdminOp.EVENT_TYPES_LIST, SchedulingItems(listOf(schedulingEventType())))
            val viewModel = SchedulingEventTypesViewModel(rig.adminRepository, rig.scheduling, "ws")
            viewModel.create()
            assertNull(viewModel.writes.dialog.value)
            advanceUntilIdle()
            assertEquals(SectionState.Loading, viewModel.state.value.list)
            assertTrue(rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_LIST).isEmpty())
            viewModel.load()
            viewModel.load()
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals("intro", state.list.valueOrNullForTest()?.single()?.slug)
            assertEquals(SchedulingAccess(canManage = true, publicHost = "acme-book.example"), state.access)
            viewModel.create()
            assertEquals(listOf("intro"), (viewModel.writes.dialog.value as EventTypeDialog.Editor).takenSlugs)
        }

    @Test
    fun `a failed list is a failure, and a failed status reads as closed`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = true)
        rig.district.schedulingApi.schedulingStatusResult = offline
        rig.admin.fail(SchedulingAdminOp.EVENT_TYPES_LIST, offline)
        val viewModel = SchedulingEventTypesViewModel(rig.adminRepository, rig.scheduling, "ws")
        viewModel.load()
        advanceUntilIdle()
        val failed = viewModel.state.value.list as SectionState.Failed
        assertEquals(R.string.scheduling_admin_failure_offline, failed.failure.message.resourceIdOrNull)
        assertFalse(viewModel.state.value.access.canManage)
    }

    @Test
    fun `one event type reads its row, hosts and questions, each failing on its own`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = true)
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_GET, fullEventType())
        rig.admin.fail(SchedulingAdminOp.EVENT_TYPES_HOSTS_GET, offline)
        rig.admin.value(
            SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST,
            SchedulingItems(listOf(schedulingQuestion(id = "b", position = 2), schedulingQuestion(id = "a"))),
        )
        val viewModel = SchedulingEventTypeViewModel(rig.adminRepository, rig.scheduling, "ws", "intro")
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals("Intro call", state.eventType.valueOrNullForTest()?.name)
        assertTrue(state.hosts is SectionState.Failed)
        assertEquals(listOf("a", "b"), state.questions.valueOrNullForTest()?.map { it.id })
        assertTrue(state.canManage)
        assertEquals(
            JsonPrimitive("intro"),
            rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_GET).single().params["slug"],
        )
        assertEquals("intro", viewModel.slug)
    }

    @Test
    fun `a write re-reads the event type, and a delete marks the screen to go back`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_GET, fullEventType())
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_PATCH, fullEventType())
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_DELETE, SchedulingNoContent(ok = true))
        val viewModel = SchedulingEventTypeViewModel(rig.adminRepository, rig.scheduling, "ws", "intro")
        advanceUntilIdle()
        viewModel.writes.actions.open(fullEventType())
        viewModel.writes.actions.toggleArchived()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_GET).size)
        assertFalse(viewModel.state.value.deleted)
        viewModel.writes.actions.delete()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.deleted)
        assertNull(viewModel.writes.dialog.value)
    }
}

private fun <T> SectionState<T>.valueOrNullForTest(): T? = (this as? SectionState.Ready)?.value
