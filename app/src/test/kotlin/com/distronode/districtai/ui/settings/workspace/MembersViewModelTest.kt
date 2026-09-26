package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.R
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.model.MemberListResponse
import com.distronode.districtai.core.model.RenameResponse
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.UiText
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The members screen's state machine.
 *
 * ⛔ TWO ROLE GATES OF DIFFERENT WIDTHS, AND BOTH ARE TESTED SEPARATELY. Membership writes are
 * agency-only — the narrowest guard in the API, because these rows are what every other permission
 * check is derived from — while the rename admits agency and client. A single `canMutate` would be
 * wrong in both directions: it would offer a client three buttons that 403, and it would hide a
 * rename they are entitled to.
 *
 * ⛔ AND THE TWO 409s HAVE TO REACH THE SCREEN AS THEIR OWN SENTENCES. "Already a member" is
 * actionable; "every workspace needs at least one agency member" is not a mistake at all. If either
 * regressed to the generic failure the app would still work and the operator would be left
 * guessing, which is exactly the kind of loss no other test would catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MembersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val roster = listOf(
        WorkspaceMember("founder@example.com", "agency", "2026-08-15T14:30:00.000Z"),
        WorkspaceMember("operator@example.com", "client", "2026-08-15T15:00:00.000Z"),
        WorkspaceMember("auditor@example.com", "viewer", "2026-08-16T09:15:00.000Z"),
    )

    private fun api() = TestDistrictApi().apply {
        memberListResult = ApiResult.Success(MemberListResponse(success = true, members = roster))
    }

    private fun viewModel(
        api: TestDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.AGENCY,
    ) = MembersViewModel(MembersRepository(api), "ws-1", role)

    private fun conflict(code: String) =
        ApiResult.HttpFailure(HTTP_CONFLICT, "the server's own sentence", code)

    private fun failureResource(state: SaveState): Int? =
        ((state as? SaveState.Failed)?.failure?.message as? UiText.Resource)?.id

    // ── Loading and role gating ──────────────────────────────────────────────

    @Test
    fun `the roster loads on init, oldest first, for every role`() = runTest {
        val vm = viewModel(api(), WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertEquals(roster, (vm.state.value.list as MembersListState.Ready).members)
        // ⛔ A VIEWER SEES THE LIST. The route admits all three roles on the read, because a viewer
        // who cannot see who else is here cannot tell who to ask for help.
        assertFalse(vm.state.value.canManage)
        assertFalse(vm.state.value.canRename)
    }

    @Test
    fun `a client may rename but may not touch membership`() = runTest {
        // ⛔ THE ASYMMETRY THIS SCREEN EXISTS AROUND. `workspace/rename` admits agency and client;
        // the membership writes admit agency alone. Deriving both from `WorkspaceRole.canMutate`
        // would offer a client three buttons that 403.
        val vm = viewModel(api(), WorkspaceRole.CLIENT)
        advanceUntilIdle()

        assertFalse(vm.state.value.canManage)
        assertTrue(vm.state.value.canRename)
    }

    @Test
    fun `an unparseable role gets neither gate`() = runTest {
        // ⛔ FAILS CLOSED. `fromWire` answers null for anything unrecognised, and a corrupted route
        // segment must resolve to no privileges rather than to a permissive default.
        val vm = viewModel(api(), null)
        advanceUntilIdle()

        assertFalse(vm.state.value.canManage)
        assertFalse(vm.state.value.canRename)
    }

    @Test
    fun `a failed read shows a retryable failure rather than an empty roster`() = runTest {
        val api = TestDistrictApi().apply {
            memberListResult = ApiResult.NetworkFailure(IOException("offline"))
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        val list = vm.state.value.list as MembersListState.Failed
        assertTrue(list.failure.retryable)
        assertTrue(vm.state.value.members.isEmpty())
        // ⛔ AND THE RENAME IS WITHHELD. "We could not read who is in this workspace" is not a
        // state in which to offer to rename it — the load-first rule, applied to the one control
        // whose field cannot be prefilled.
        assertFalse(vm.state.value.canRenameNow)
    }

    // ── Adding ──────────────────────────────────────────────────────────────

    @Test
    fun `a successful add clears the draft, re-reads the roster, and normalises the address`() =
        runTest {
            val api = api()
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.editEmail("  New.Person@Example.Com ")
            vm.editRole(WorkspaceRole.VIEWER)
            vm.addMember()
            advanceUntilIdle()

            assertEquals(SaveState.Saved, vm.state.value.addSave)
            assertEquals("", vm.state.value.draftEmail)
            // ⚠️ Lowercased and trimmed the way the route does, so what is sent matches what will
            // be stored and what the membership lookups can match.
            assertEquals("new.person@example.com", api.memberAdds[0].email)
            assertEquals("viewer", api.memberAdds[0].role)
            // ⛔ RE-READ, NOT PATCHED. The roster is ordered `createdAt asc` server-side and the
            // echoed row is one row of it; appending locally would show an order the server does
            // not agree with.
            assertEquals(2, api.memberListRequests.size)
        }

    @Test
    fun `a malformed address is rejected without a round trip`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editEmail("not-an-address")
        vm.addMember()
        advanceUntilIdle()

        assertTrue(vm.state.value.addRejected)
        assertEquals(SaveState.Idle, vm.state.value.addSave)
        assertTrue("nothing may be sent", api.memberAdds.isEmpty())
        // ⚠️ And the next keystroke retires the message rather than leaving it under a corrected
        // address.
        vm.editEmail("fixed@example.com")
        assertFalse(vm.state.value.addRejected)
    }

    @Test
    fun `a duplicate says Already a member and keeps the address on screen`() = runTest {
        // ⛔ OUR OWN RESOURCE, NOT THE SERVER'S SENTENCE — and the draft SURVIVES, because seeing
        // which address was already there is the whole point of the message.
        val api = api().apply { addMemberResult = conflict("member_exists") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editEmail("founder@example.com")
        vm.addMember()
        advanceUntilIdle()

        assertEquals(R.string.members_error_duplicate, failureResource(vm.state.value.addSave))
        assertEquals("founder@example.com", vm.state.value.draftEmail)
        assertFalse(
            "retrying produces the identical refusal",
            (vm.state.value.addSave as SaveState.Failed).failure.retryable,
        )
    }

    @Test
    fun `a viewer cannot add even if the control is somehow invoked`() = runTest {
        val api = api()
        val vm = viewModel(api, WorkspaceRole.VIEWER)
        advanceUntilIdle()

        vm.editEmail("someone@example.com")
        vm.addMember()
        advanceUntilIdle()

        assertTrue(api.memberAdds.isEmpty())
        assertEquals(SaveState.Idle, vm.state.value.addSave)
    }

    // ── Role changes and removal ─────────────────────────────────────────────

    @Test
    fun `a role change sends the wire spelling and re-reads`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.changeRole("operator@example.com", WorkspaceRole.AGENCY)
        advanceUntilIdle()

        assertEquals(SaveState.Saved, vm.state.value.roleSave)
        // ⛔ LOWERCASE. The route validates against `agency|client|viewer` after lowercasing, so
        // `AGENCY` would be a 400 rather than a coerced value.
        assertEquals("agency", api.memberRoleChanges[0].role)
        assertEquals("operator@example.com", api.memberRoleChanges[0].email)
        assertEquals(2, api.memberListRequests.size)
    }

    @Test
    fun `demoting the last agency member gets its own sentence, not a validation error`() =
        runTest {
            val api = api().apply { changeRoleResult = conflict("last_agency_member") }
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.changeRole("founder@example.com", WorkspaceRole.VIEWER)
            advanceUntilIdle()

            assertEquals(
                R.string.members_error_last_agency,
                failureResource(vm.state.value.roleSave),
            )
            // ⛔ AND THE ROSTER IS STILL RE-READ AFTER THE REFUSAL. This failure usually means the
            // operator's copy of who holds `agency` is out of date; leaving the stale list on
            // screen would invite the same refusal again.
            assertEquals(2, api.memberListRequests.size)
        }

    @Test
    fun `removing the last agency member gets the same sentence from the other route`() = runTest {
        val api = api().apply { removeMemberResult = conflict("last_agency_member") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.removeMember("founder@example.com")
        advanceUntilIdle()

        assertEquals(R.string.members_error_last_agency, failureResource(vm.state.value.removeSave))
    }

    @Test
    fun `a successful removal re-reads the roster rather than dropping the row`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.removeMember("auditor@example.com")
        advanceUntilIdle()

        assertEquals(SaveState.Saved, vm.state.value.removeSave)
        assertEquals(listOf("ws-1" to "auditor@example.com"), api.memberRemovals)
        assertEquals(2, api.memberListRequests.size)
    }

    @Test
    fun `a rate-limited write keeps the server's wait-and-retry wording`() = runTest {
        // ⚠️ All three writes share ONE 20/min budget keyed on the WORKSPACE, so this is reachable
        // by an ordinary bulk tidy-up. It must not read as either 409.
        val api = api().apply {
            removeMemberResult = ApiResult.RateLimited("Too many membership changes.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.removeMember("auditor@example.com")
        advanceUntilIdle()

        val failed = vm.state.value.removeSave as SaveState.Failed
        assertEquals(UiText.Literal("Too many membership changes."), failed.failure.message)
    }

    @Test
    fun `a second write is refused while one is in flight`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.removeMember("auditor@example.com")
        // ⚠️ NOT advanced: the first call is still suspended, so `busy` is true.
        vm.changeRole("operator@example.com", WorkspaceRole.AGENCY)
        advanceUntilIdle()

        assertTrue("the second write must not have been sent", api.memberRoleChanges.isEmpty())
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    @Test
    fun `rename adopts the server's echo, clears the draft, and does not re-read the roster`() =
        runTest {
            val api = api().apply {
                renameResult = ApiResult.Success(
                    RenameResponse(success = true, name = "Trimmed Name"),
                )
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.editName("  Trimmed Name  ")
            vm.rename()
            advanceUntilIdle()

            assertEquals(SaveState.Saved, vm.state.value.renameSave)
            // ⛔ THE SERVER'S VALUE, NOT THE TYPED ONE. It is the only name this client can vouch
            // for, and it is already trimmed.
            assertEquals("Trimmed Name", vm.state.value.storedName)
            assertEquals("", vm.state.value.renameDraft)
            // ⚠️ The roster is untouched: renaming changes nobody's membership, and a re-read would
            // only give a failing list read a chance to replace a successful rename notice.
            assertEquals(1, api.memberListRequests.size)
        }

    @Test
    fun `a blank or over-long name is not sent`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        // ⛔ TRIMMED, THEN MEASURED — the route's own order. " " is an empty name, which is what an
        // operator gets by tapping the spacebar.
        vm.editName("   ")
        vm.rename()
        advanceUntilIdle()
        assertTrue(api.renameRequests.isEmpty())

        vm.editName("x".repeat(OVER_MAX_NAME))
        vm.rename()
        advanceUntilIdle()
        assertTrue(api.renameRequests.isEmpty())
    }

    @Test
    fun `a failed rename keeps the typed name`() = runTest {
        // ⚠️ Losing a typed name because the save failed would be two losses for one fault.
        val api = api().apply {
            renameResult = ApiResult.NetworkFailure(IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editName("New Name")
        vm.rename()
        advanceUntilIdle()

        assertTrue(vm.state.value.renameSave is SaveState.Failed)
        assertEquals("New Name", vm.state.value.renameDraft)
        assertNull("nothing may be claimed as stored", vm.state.value.storedName)
    }

    @Test
    fun `a viewer cannot rename`() = runTest {
        val api = api()
        val vm = viewModel(api, WorkspaceRole.VIEWER)
        advanceUntilIdle()

        vm.editName("New Name")
        vm.rename()
        advanceUntilIdle()

        assertTrue(api.renameRequests.isEmpty())
    }

    // ── The two gates, as predicates ─────────────────────────────────────────

    @Test
    fun `canAdd and canRenameNow each require every one of their conditions`() {
        // ⛔ ASSERTED ON THE STATE DIRECTLY BECAUSE EACH PREDICATE IS AN AND OF FOUR THINGS, and a
        // dropped clause is invisible from the outside: `canAdd` losing its role check would only
        // show up as a 403 in production, and `canRenameNow` losing its `loaded` check would only
        // show up as a rename accepted against a workspace whose roster could not be read.
        val ready = MembersUiState(
            list = MembersListState.Ready(roster),
            canManage = true,
            canRename = true,
            draftEmail = "someone@example.com",
            renameDraft = "A Name",
        )

        assertTrue(ready.canAdd)
        assertTrue(ready.canRenameNow)

        assertFalse("the role gate", ready.copy(canManage = false).canAdd)
        assertFalse("a malformed address", ready.copy(draftEmail = "nope").canAdd)
        assertFalse("a write in flight", ready.copy(addSave = SaveState.Saving).canAdd)

        assertFalse("the wider role gate", ready.copy(canRename = false).canRenameNow)
        assertFalse("a blank name", ready.copy(renameDraft = "   ").canRenameNow)
        assertFalse("an over-long name", ready.copy(renameDraft = "x".repeat(OVER_MAX_NAME)).canRenameNow)
        assertFalse(
            "an unread roster",
            ready.copy(list = MembersListState.Loading).canRenameNow,
        )
        assertFalse("a write in flight", ready.copy(renameSave = SaveState.Saving).canRenameNow)

        // ⚠️ A name of exactly the maximum is ALLOWED — the boundary is inclusive, matching the
        // route's `length in 1..120` after trimming.
        assertTrue(ready.copy(renameDraft = "x".repeat(OVER_MAX_NAME - 1)).canRenameNow)
    }

    private companion object {
        const val HTTP_CONFLICT = 409

        /** One past the route's `MAX_NAME_LENGTH` of 120. */
        const val OVER_MAX_NAME = 121
    }

    @Test
    fun `nothing else can be written while an add is in flight`() = runTest {
        // ⚠️ ONE MEMBERSHIP WRITE AT A TIME. Each one re-reads the roster, and two re-reads racing
        // would decide the list on screen by whichever answered last.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editEmail("new@example.com")
        vm.addMember()
        vm.addMember()
        vm.changeRole("operator@example.com", WorkspaceRole.AGENCY)
        vm.removeMember("auditor@example.com")
        advanceUntilIdle()

        assertEquals(1, api.memberAdds.size)
        assertTrue(api.memberRoleChanges.isEmpty())
        assertTrue(api.memberRemovals.isEmpty())
    }

    @Test
    fun `a client that calls a membership write directly is refused without a request`() = runTest {
        // ⛔ THE WRITES ARE AGENCY-ONLY SERVER-SIDE, and the screen draws no control for a client.
        // This is the ViewModel holding the same line when something calls it anyway.
        val api = api()
        val vm = viewModel(api, role = WorkspaceRole.CLIENT)
        advanceUntilIdle()

        vm.editEmail("new@example.com")
        vm.addMember()
        vm.changeRole("operator@example.com", WorkspaceRole.AGENCY)
        vm.removeMember("auditor@example.com")
        advanceUntilIdle()

        assertTrue(api.memberAdds.isEmpty())
        assertTrue(api.memberRoleChanges.isEmpty())
        assertTrue(api.memberRemovals.isEmpty())
        assertEquals(roster, vm.state.value.members)
    }

    @Test
    fun `the factory builds a ViewModel that reads the roster and carries both gates`() = runTest {
        val api = api()
        val vm = MembersViewModel
            .factory(MembersRepository(api), "ws-1", WorkspaceRole.CLIENT)
            .create(MembersViewModel::class.java)
        advanceUntilIdle()

        assertEquals(listOf("ws-1"), api.memberListRequests)
        assertEquals(roster, vm.state.value.members)
        assertFalse(vm.state.value.canManage)
        assertTrue(vm.state.value.canRename)
    }
}
