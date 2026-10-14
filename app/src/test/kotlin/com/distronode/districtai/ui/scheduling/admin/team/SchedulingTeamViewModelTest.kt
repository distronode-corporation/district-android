package com.distronode.districtai.ui.scheduling.admin.team

import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.model.MemberListResponse
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
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

/** The team section's reads, the join across the two rosters, and the stranded-host warning. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingTeamViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private fun model(rig: SchedulingAdminTestRig) =
        SchedulingTeamViewModel(rig.adminRepository, rig.scheduling, MembersRepository(rig.district), "ws")

    @Test
    fun `the people are District's members joined with every scheduler user, archived ones included`() =
        runTest(dispatcher) {
            val rig = SchedulingAdminTestRig(canManage = true)
            rig.district.memberListResult = ApiResult.Success(
                MemberListResponse(success = true, members = listOf(districtMember("Ana@Example.com", "agency"))),
            )
            rig.admin.value(SchedulingAdminOp.ME_GET, schedulingMe(timezone = "America/Toronto"))
            rig.admin.value(
                SchedulingAdminOp.USERS_LIST,
                listOf(teamUser("u1", "ana@example.com", "Ana Lima"), teamUser("u2", "cy@example.com", "Cy")),
            )
            rig.admin.value(SchedulingAdminOp.TEAMS_LIST, SchedulingItems(listOf(schedulingTeam())))
            val viewModel = model(rig)
            advanceUntilIdle()

            val state = viewModel.state.value
            val rows = (state.members as SectionState.Ready).value
            assertEquals(listOf("Ana Lima", "Cy"), rows.map { it.name })
            assertEquals(listOf("agency", null), rows.map { it.districtRole })
            assertEquals(listOf("Cy"), state.stranded.map { it.name })
            assertFalse(state.districtMembersFailed)
            assertEquals(SectionState.Ready(listOf(schedulingTeam())), state.teams)
            assertTrue(state.canManage)
            assertEquals("America/Toronto", state.timezone)
            val params = rig.admin.callsTo(SchedulingAdminOp.USERS_LIST).single().params
            assertEquals(JsonPrimitive(true), params["include_archived"])
        }

    @Test
    fun `a roster that could not be read accuses nobody, and each read fails on its own`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = false)
        rig.district.memberListResult = ApiResult.NetworkFailure(IOException("x"))
        rig.admin.value(SchedulingAdminOp.USERS_LIST, listOf(teamUser("u2", "cy@example.com", "Cy")))
        val viewModel = model(rig)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.districtMembersFailed)
        assertEquals(1, (state.members as SectionState.Ready).value.size)
        assertEquals(emptyList<SchedulingMemberRow>(), state.stranded)
        assertTrue(state.teams is SectionState.Failed)
        assertFalse(state.canManage)
        assertEquals("UTC", state.timezone)

        rig.admin.clear(SchedulingAdminOp.USERS_LIST)
        viewModel.load()
        assertEquals(SectionState.Loading, viewModel.state.value.members)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.members is SectionState.Failed)
        assertNull(viewModel.state.value.members.let { (it as? SectionState.Ready)?.value })
    }
}
