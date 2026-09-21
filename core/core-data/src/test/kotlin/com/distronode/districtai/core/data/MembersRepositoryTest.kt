package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CODE_LAST_AGENCY_MEMBER
import com.distronode.districtai.core.model.CODE_MEMBER_EXISTS
import com.distronode.districtai.core.model.MemberListResponse
import com.distronode.districtai.core.model.MemberMutationResponse
import com.distronode.districtai.core.model.RenameResponse
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The membership data layer.
 *
 * ⛔ WHAT THIS FILE PROTECTS IS THE TRANSLATION OF TWO MACHINE-READABLE REFUSALS INTO SOMETHING A
 * SCREEN CAN BRANCH ON WITHOUT READING ENGLISH. `member_exists` and `last_agency_member` mean
 * opposite things — one is a mistake the operator can fix, the other is not a mistake at all — and
 * the only stable way to tell them apart is the `code` the server sends alongside a sentence it is
 * free to reword. If that mapping regresses, the app falls back to the operator-facing sentence
 * and both specific messages are silently gone.
 *
 * ⛔ AND THE SECOND THING IT PROTECTS IS THE ENVELOPE CHECK ON A WRITE. Every field of
 * `MemberMutationResponse` defaults, so a 200 carrying `{}` decodes into "done, with no row" —
 * which is byte-identical to a successful REMOVAL. Without the check, a structurally empty
 * response would report a write that never happened.
 */
class MembersRepositoryTest {

    private val roster = listOf(
        WorkspaceMember("founder@example.com", "agency", "2026-08-15T14:30:00.000Z"),
        WorkspaceMember("operator@example.com", "client", "2026-08-15T15:00:00.000Z"),
        WorkspaceMember("auditor@example.com", "viewer", "2026-08-16T09:15:00.000Z"),
    )

    private fun api() = FakeDistrictApi().apply {
        memberListResult = ApiResult.Success(MemberListResponse(success = true, members = roster))
    }

    private fun conflict(code: String) = ApiResult.HttpFailure(
        status = HTTP_CONFLICT,
        message = "the server's own operator-facing sentence",
        code = code,
    )

    // ── The roster ───────────────────────────────────────────────────────────

    @Test
    fun `members returns the roster and sends the workspace it was asked for`() = runTest {
        val api = api()

        val result = MembersRepository(api).members("ws-1")

        assertEquals(roster, (result as ApiResult.Success).value)
        assertEquals(listOf("ws-1"), api.memberListRequests)
    }

    @Test
    fun `a structurally empty 200 is reported as contract drift rather than an empty roster`() =
        runTest {
            // ⛔ A WORKSPACE WITH NOBODY IN IT CANNOT EXIST — the caller had to be a member to pass
            // the guard — so "the roster is empty" is never the right reading of a `{}` body. Every
            // field defaults, so without the envelope check this decodes cleanly and confidently
            // lies. Same failure the workspace list already learned the expensive way.
            val api = FakeDistrictApi().apply {
                memberListResult = ApiResult.Success(MemberListResponse())
            }

            val result = MembersRepository(api).members("ws-1")

            assertTrue(result is ApiResult.DecodeFailure)
        }

    @Test
    fun `a failed read is returned unchanged`() = runTest {
        val failure = ApiResult.NetworkFailure(IOException("offline"))
        val api = FakeDistrictApi().apply { memberListResult = failure }

        assertEquals(failure, MembersRepository(api).members("ws-1"))
    }

    // ── The two refusals ─────────────────────────────────────────────────────

    @Test
    fun `a duplicate 409 becomes Duplicate rather than a generic failure`() = runTest {
        // ⛔ MATCHED ON THE CODE, NEVER ON THE MESSAGE. The sentence here is deliberately not the
        // server's real copy: if this passed by substring-matching English, changing the fixture's
        // wording would break it — and that is exactly the coupling this mapping exists to remove.
        val api = FakeDistrictApi().apply { addMemberResult = conflict(CODE_MEMBER_EXISTS) }

        val outcome = MembersRepository(api).addMember("ws-1", "dupe@example.com", "client")

        assertEquals(MemberMutationOutcome.Duplicate, outcome)
    }

    @Test
    fun `a last-agency 409 becomes LastAgency on BOTH the demotion and the removal`() = runTest {
        // ⛔ THE SERVER SENDS THIS CODE FROM TWO DIFFERENT ROUTES — PATCH and DELETE — and a
        // mapping that only covered one would leave the other showing "something went wrong" for
        // the single refusal on this screen that is not the operator's fault.
        val api = FakeDistrictApi().apply {
            changeRoleResult = conflict(CODE_LAST_AGENCY_MEMBER)
            removeMemberResult = conflict(CODE_LAST_AGENCY_MEMBER)
        }
        val repository = MembersRepository(api)

        assertEquals(
            MemberMutationOutcome.LastAgency,
            repository.changeRole("ws-1", "founder@example.com", "viewer"),
        )
        assertEquals(
            MemberMutationOutcome.LastAgency,
            repository.removeMember("ws-1", "founder@example.com"),
        )
    }

    @Test
    fun `an unknown code and a codeless 409 both fall through to Failed`() = runTest {
        // ⚠️ FAILS OPEN TO THE SERVER'S OWN SENTENCE, which is the right direction: a code this
        // build has not learned yet must not be silently reported as one of the two it has. The
        // server's wording for an unrecognised refusal is more specific than anything invented
        // here.
        val api = FakeDistrictApi().apply { addMemberResult = conflict("some_future_code") }
        val bare = FakeDistrictApi().apply {
            addMemberResult = ApiResult.HttpFailure(HTTP_CONFLICT, "no code at all", null)
        }

        val unknown = MembersRepository(api).addMember("ws-1", "a@example.com", null)
        val codeless = MembersRepository(bare).addMember("ws-1", "a@example.com", null)

        assertTrue(unknown is MemberMutationOutcome.Failed)
        assertTrue(codeless is MemberMutationOutcome.Failed)
    }

    @Test
    fun `a 403 and a 404 are Failed rather than a membership refusal`() = runTest {
        // ⛔ NEITHER IS A REFUSAL OF THIS KIND. A 404 means the roster on screen is stale (that
        // address is not a member); a 403 means the caller's role changed under them. Reporting
        // either as `LastAgency` would tell an operator the workspace needs an administrator when
        // the real problem is something else entirely.
        val forbidden = FakeDistrictApi().apply {
            removeMemberResult = ApiResult.Forbidden("Forbidden")
        }
        val notFound = FakeDistrictApi().apply {
            changeRoleResult = ApiResult.NotFound("That email is not a member of this workspace")
        }

        val refused = MembersRepository(forbidden).removeMember("ws-1", "a@example.com")
        val missing = MembersRepository(notFound).changeRole("ws-1", "a@example.com", "viewer")

        assertTrue(refused is MemberMutationOutcome.Failed)
        assertTrue(missing is MemberMutationOutcome.Failed)
    }

    @Test
    fun `a 429 is Failed and keeps the server's wait-and-retry wording`() = runTest {
        // ⚠️ The three writes share ONE 20/min budget keyed on the WORKSPACE, so this is reachable
        // by an operator doing a bulk tidy-up. It must not be mistaken for either 409.
        val api = FakeDistrictApi().apply {
            addMemberResult = ApiResult.RateLimited("Too many membership changes.")
        }

        val outcome = MembersRepository(api).addMember("ws-1", "a@example.com", "client")

        assertEquals(
            ApiResult.RateLimited("Too many membership changes."),
            (outcome as MemberMutationOutcome.Failed).failure,
        )
    }

    // ── The writes that land ─────────────────────────────────────────────────

    @Test
    fun `a successful add carries the echoed row and the request it sent`() = runTest {
        val api = FakeDistrictApi()

        val outcome = MembersRepository(api).addMember("ws-1", "new@example.com", "viewer")

        assertTrue(outcome is MemberMutationOutcome.Done)
        assertEquals(1, api.memberAdds.size)
        assertEquals("ws-1", api.memberAdds[0].workspaceId)
        assertEquals("new@example.com", api.memberAdds[0].email)
        assertEquals("viewer", api.memberAdds[0].role)
    }

    @Test
    fun `a null role is sent as absent so the server applies its own default`() = runTest {
        // ⚠️ `explicitNulls = false` on the body encoder means a null is OMITTED rather than sent,
        // which is what makes "the operator did not choose" reach the route as an absent key — and
        // therefore as `client` — instead of as a 400.
        val api = FakeDistrictApi()

        MembersRepository(api).addMember("ws-1", "new@example.com", null)

        assertNull(api.memberAdds[0].role)
    }

    @Test
    fun `a removal is Done even though it echoes no row at all`() = runTest {
        // ⛔ THE DELETE ANSWERS A BARE `{success:true}`. `Done(member = null)` is the correct and
        // only possible reading; treating a missing row as a failure would report every successful
        // removal as broken.
        val api = FakeDistrictApi()

        val outcome = MembersRepository(api).removeMember("ws-1", "gone@example.com")

        assertEquals(MemberMutationOutcome.Done(null), outcome)
        assertEquals(listOf("ws-1" to "gone@example.com"), api.memberRemovals)
    }

    @Test
    fun `a structurally empty 200 on a write is drift, not a silent success`() = runTest {
        // ⛔ THE MOST DANGEROUS DECODE ON THIS SURFACE. `{}` is byte-identical to a successful
        // removal once every field has a default, so without the envelope check a write that never
        // happened would be reported as done and the screen would re-read a list that had not
        // changed.
        val api = FakeDistrictApi().apply {
            removeMemberResult = ApiResult.Success(MemberMutationResponse())
        }

        val outcome = MembersRepository(api).removeMember("ws-1", "a@example.com")

        assertTrue((outcome as MemberMutationOutcome.Failed).failure is ApiResult.DecodeFailure)
    }

    // ── The rename ───────────────────────────────────────────────────────────

    @Test
    fun `rename adopts the server's echoed name rather than the string it sent`() = runTest {
        // ⛔ THE ROUTE TRIMS BEFORE IT MEASURES AND ECHOES WHAT IT STORED. Returning the requested
        // value would display a name nobody saved — and would hide the trim from an operator who
        // typed trailing spaces.
        val api = FakeDistrictApi().apply {
            renameResult = ApiResult.Success(RenameResponse(success = true, name = "Trimmed Name"))
        }

        val result = MembersRepository(api).rename("ws-1", "  Trimmed Name  ")

        assertEquals("Trimmed Name", (result as ApiResult.Success).value)
        assertEquals("ws-1", api.renameRequests[0].workspaceId)
        assertEquals("  Trimmed Name  ", api.renameRequests[0].name)
    }

    @Test
    fun `a rename whose envelope does not affirm success is drift`() = runTest {
        val api = FakeDistrictApi().apply {
            renameResult = ApiResult.Success(RenameResponse(name = "Whatever"))
        }

        assertTrue(MembersRepository(api).rename("ws-1", "Whatever") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a failed rename is returned unchanged`() = runTest {
        val failure = ApiResult.RateLimited("Too many rename attempts.")
        val api = FakeDistrictApi().apply { renameResult = failure }

        assertEquals(failure, MembersRepository(api).rename("ws-1", "New"))
    }

    private companion object {
        /** ⚠️ Named rather than inlined: detekt's `MagicNumber` counts a bare 409 as one. */
        const val HTTP_CONFLICT = 409
    }
}
