package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.data.ConversationAssignmentRepository
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.model.ConversationAssignment
import com.distronode.districtai.core.model.ConversationAssignmentsResponse
import com.distronode.districtai.core.model.MemberListResponse
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeConversationAssignmentApi
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.UiText
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A thread's owner and the picker (app parity 3), as core-rust's `parity.rs` holds them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadOwnerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val assignments = FakeConversationAssignmentApi().apply {
        assignmentsResult = ApiResult.Success(
            ConversationAssignmentsResponse(
                success = true,
                assignments = listOf(
                    ConversationAssignment("contact:c1", "viewer@example.com"),
                    ConversationAssignment("contact:c2", "other@example.com"),
                ),
            ),
        )
    }

    private val district = FakeDistrictApi().apply {
        memberListResult = ApiResult.Success(
            MemberListResponse(
                success = true,
                members = listOf(
                    WorkspaceMember("agency@example.com", "agency"),
                    WorkspaceMember("client@example.com", "CLIENT"),
                    WorkspaceMember("viewer@example.com", "Viewer"),
                    WorkspaceMember("norole@example.com", null),
                    WorkspaceMember("", "client"),
                ),
            ),
        )
    }

    private fun viewModel(role: WorkspaceRole? = WorkspaceRole.AGENCY, threadKey: String = "contact:c1") =
        ThreadOwnerViewModel(
            assignments = ConversationAssignmentRepository(assignments),
            membersRepository = MembersRepository(district),
            workspaceId = "ws-1",
            threadKey = threadKey,
            role = role,
        )

    @Test
    fun `the picker offers no viewer, but a viewer who already owns the thread keeps showing`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loaded)
        assertTrue(state.canAssign)
        assertEquals("viewer@example.com", state.owner)
        assertEquals(
            listOf("agency@example.com", "client@example.com", "norole@example.com", "viewer@example.com"),
            state.members,
        )
    }

    @Test
    fun `a viewer sees the owner and the members are never read`() = runTest(dispatcher) {
        val vm = viewModel(role = WorkspaceRole.VIEWER, threadKey = "contact:c2")
        advanceUntilIdle()

        assertEquals("other@example.com", vm.state.value.owner)
        assertFalse(vm.state.value.canAssign)
        assertTrue(district.memberListRequests.isEmpty())
        vm.assign("agency@example.com")
        advanceUntilIdle()
        assertTrue("a viewer cannot assign", assignments.writes.isEmpty())
    }

    @Test
    fun `an assignment goes once, updates the owner and tells the Inbox`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.assign("client@example.com")
        vm.assign("agency@example.com")
        assertTrue(vm.state.value.assigning)
        advanceUntilIdle()

        assertEquals(
            listOf(FakeConversationAssignmentApi.Write("ws-1", "contact:c1", "client@example.com")),
            assignments.writes,
        )
        val state = vm.state.value
        assertEquals("client@example.com", state.owner)
        assertFalse(state.assigning)
        assertEquals(1, state.assignments)
        assertEquals(
            "the viewer leaves the picker once they no longer own it",
            listOf("agency@example.com", "client@example.com", "norole@example.com"),
            state.members,
        )
    }

    @Test
    fun `unassigning sends null, and picking the current owner sends nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.assign("viewer@example.com")
        advanceUntilIdle()
        assertTrue(assignments.writes.isEmpty())

        vm.assign(null)
        advanceUntilIdle()
        assertEquals(listOf(FakeConversationAssignmentApi.Write("ws-1", "contact:c1", null)), assignments.writes)
        assertNull(vm.state.value.owner)
    }

    @Test
    fun `a refusal keeps the owner and shows the server's sentence, until dismissed`() = runTest(dispatcher) {
        assignments.assignResult = ApiResult.HttpFailure(
            status = 400,
            message = "Viewers cannot be assigned conversations.",
            code = "assignee_is_viewer",
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.assign("agency@example.com")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("viewer@example.com", state.owner)
        assertEquals(0, state.assignments)
        assertEquals(
            UiText.Server("Viewers cannot be assigned conversations.", "assignee_is_viewer"),
            state.failure?.message,
        )
        vm.dismissFailure()
        assertNull(vm.state.value.failure)
    }

    @Test
    fun `an unreadable owner shows nothing and cannot be changed`() = runTest(dispatcher) {
        assignments.assignmentsResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.loaded)
        vm.assign("agency@example.com")
        advanceUntilIdle()
        assertTrue(assignments.writes.isEmpty())
    }

    @Test
    fun `unreadable members leave the owner and Unassigned`() = runTest(dispatcher) {
        district.memberListResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel(threadKey = "contact:none")
        advanceUntilIdle()

        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.owner)
        assertEquals(emptyList<String>(), vm.state.value.members)
    }

    @Test
    fun `the factory builds the view model it describes`() = runTest(dispatcher) {
        val vm = ThreadOwnerViewModel.Factory(
            assignments = ConversationAssignmentRepository(assignments),
            members = MembersRepository(district),
            workspaceId = "ws-1",
            threadKey = "contact:c2",
            role = WorkspaceRole.CLIENT,
        ).create(ThreadOwnerViewModel::class.java)
        advanceUntilIdle()

        assertEquals("other@example.com", vm.state.value.owner)
        assertEquals(listOf("ws-1"), district.memberListRequests)
    }
}
