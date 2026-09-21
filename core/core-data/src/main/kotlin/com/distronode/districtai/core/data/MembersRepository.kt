package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CODE_LAST_AGENCY_MEMBER
import com.distronode.districtai.core.model.CODE_MEMBER_EXISTS
import com.distronode.districtai.core.model.MemberAddRequest
import com.distronode.districtai.core.model.MemberMutationResponse
import com.distronode.districtai.core.model.MemberRoleRequest
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRenameRequest
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.MembersApi

/**
 * Who belongs to this workspace, and what it is called.
 *
 * ⛔ THE TWO 409 CODES ARE TRANSLATED HERE, ONCE, AND NEVER STRING-MATCHED ABOVE. The server sends
 * `code: "member_exists"` and `code: "last_agency_member"` alongside sentences written for an
 * operator, and both refusals need their OWN wording on screen — "already a member" is something
 * the operator can fix by picking someone else, while "this workspace would have no administrator"
 * is not a mistake at all. Leaving that to the UI would mean a ViewModel comparing English strings
 * against a message the server is free to reword, so the branch happens against the CODE, in one
 * place, and the screen receives a [MemberMutationOutcome] it can only handle exhaustively.
 *
 * ⛔ NOTHING HERE PATCHES A LIST LOCALLY. A removal echoes no row at all, an add echoes one row of
 * a roster whose ORDER (`createdAt asc`) this client does not control, and a role change echoes a
 * row whose position may now differ from what is on screen. So every write is followed by a
 * re-read of the list — the caller's job, because the list is what is on screen, not because a
 * save needs a baseline. Contrast [WorkspaceConfigRepository], which re-reads because its routes
 * echo nothing and it is rebuilding the input to a wholesale replace.
 *
 * ⛔ AND [rename] IS THE ONE WRITE ON THIS SURFACE THAT NEEDS NO RE-READ, for the reason
 * `knowledge-mode` needs none: the route echoes the value it STORED (trimmed, through its own
 * validation) rather than the value that was sent, so the echo is what a later read will see.
 *
 * ⚠️ THE MUTATIONS ARE AGENCY-ONLY SERVER-SIDE — narrower than the `["agency","client"]` that
 * `WorkspaceRole.canMutate` mirrors — while `rename` admits both and the LIST admits `viewer` as
 * well. Three different guards behind one repository; the gating lives in the ViewModel and is
 * checked per call rather than derived from one flag.
 */
class MembersRepository(private val api: MembersApi) {

    /**
     * The roster, oldest first.
     *
     * ⚠️ AN EMPTY LIST IS NOT A REAL ANSWER HERE AND YET IT IS STILL RETURNED AS SUCCESS. A
     * workspace with zero members cannot exist in practice — the caller had to be a member to pass
     * the guard — so an empty array is either a structurally empty 200 (which [rejectedEnvelope]
     * catches, because `success` would not be true) or a genuine race with a removal. Neither is
     * worth a fabricated failure; the screen shows an empty roster and a retry.
     */
    suspend fun members(workspaceId: String): ApiResult<List<WorkspaceMember>> =
        when (val result = api.members(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(LIST_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.members)
            is ApiResult.Failure -> result
        }

    /**
     * Add one member.
     *
     * ⚠️ NO INVITATION IS SENT. [MemberMutationOutcome.Done] means a row exists, not that anyone
     * was told about it — the screen has to say so.
     */
    suspend fun addMember(
        workspaceId: String,
        email: String,
        role: String?,
    ): MemberMutationOutcome = outcome(
        api.addMember(MemberAddRequest(workspaceId = workspaceId, email = email, role = role)),
    )

    /** Change one member's role. ⛔ May be refused with [MemberMutationOutcome.LastAgency]. */
    suspend fun changeRole(
        workspaceId: String,
        email: String,
        role: String,
    ): MemberMutationOutcome = outcome(
        api.changeMemberRole(
            MemberRoleRequest(workspaceId = workspaceId, email = email, role = role),
        ),
    )

    /** Remove one member. ⛔ May be refused with [MemberMutationOutcome.LastAgency]. */
    suspend fun removeMember(workspaceId: String, email: String): MemberMutationOutcome =
        outcome(api.removeMember(workspaceId, email))

    /**
     * Rename the workspace.
     *
     * ⛔ THE SERVER'S ECHO IS ADOPTED, NEVER THE REQUESTED STRING. The route trims before it
     * measures and returns what it wrote, so returning the sent value would display a name nobody
     * stored — and would hide the trim from an operator who typed trailing spaces.
     *
     * ⚠️ NOT A [MemberMutationOutcome]: this route has no 409 and nothing to branch on beyond
     * success or failure, and giving it the membership outcome type would imply two refusals it
     * can never produce.
     */
    suspend fun rename(workspaceId: String, name: String): ApiResult<String?> =
        when (
            val result = api.renameWorkspace(
                WorkspaceRenameRequest(workspaceId = workspaceId, name = name),
            )
        ) {
            is ApiResult.Success ->
                rejectedEnvelope(RENAME_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.name)
            is ApiResult.Failure -> result
        }

    /**
     * The one place a membership write's result becomes an outcome.
     *
     * ⛔ THE ENVELOPE IS CHECKED BEFORE THE ROW IS TRUSTED, exactly as on the reads. Every field of
     * [MemberMutationResponse] defaults, so a 200 carrying `{}` would otherwise decode into "done,
     * with no row" — indistinguishable from a real removal, and the screen would re-read a list
     * that had not changed and report success for a write that never happened.
     *
     * ⛔ AND THE CODE IS MATCHED ONLY ON AN [ApiResult.HttpFailure]. A 403 or a 404 is not a
     * refusal of this KIND: 404 means the roster on screen is stale (the address is not a member),
     * and 403 means the role changed under the operator. Both fall through to [Failed] with the
     * server's own sentence, which is more specific than anything invented here.
     */
    private fun outcome(result: ApiResult<MemberMutationResponse>): MemberMutationOutcome =
        when (result) {
            is ApiResult.Success ->
                rejectedEnvelope(MUTATION_ENVELOPE, result.value.success)
                    ?.let { MemberMutationOutcome.Failed(it) }
                    ?: MemberMutationOutcome.Done(result.value.member)
            is ApiResult.HttpFailure -> when (result.code) {
                CODE_MEMBER_EXISTS -> MemberMutationOutcome.Duplicate
                CODE_LAST_AGENCY_MEMBER -> MemberMutationOutcome.LastAgency
                else -> MemberMutationOutcome.Failed(result)
            }
            is ApiResult.Failure -> MemberMutationOutcome.Failed(result)
        }

    private companion object {
        const val LIST_ENVELOPE = "MemberListResponse"
        const val MUTATION_ENVELOPE = "MemberMutationResponse"
        const val RENAME_ENVELOPE = "RenameResponse"
    }
}

/**
 * What happened to a membership write.
 *
 * ⛔ FOUR CASES, AND THE TWO IN THE MIDDLE ARE THE REASON THIS TYPE EXISTS. Collapsing them into
 * [Failed] would leave the screen with the server's operator-facing sentence and nothing to branch
 * on — so a ViewModel wanting to say "every workspace needs at least one agency member" would have
 * to substring-match English that the server is free to reword. The server already publishes a
 * stable machine-readable `code` for exactly this; matching it once, here, is what keeps the UI
 * off the message text.
 *
 * ⛔ [LastAgency] IS NOT A VALIDATION FAILURE AND MUST NOT BE WORDED AS ONE. Nothing the operator
 * typed is wrong — the workspace simply needs someone left who can administer it, and the write was
 * refused whole. [Duplicate] is the opposite: it IS something they can fix, by picking a different
 * address.
 *
 * ⚠️ [Failed] CARRIES AN [ApiResult.Failure], NOT A RENDERED MESSAGE. This module cannot see the
 * UI's `FailureText` — that lives in the app module, next to the string resources it names — and
 * the mapping from a failure to what a user reads is deliberately single-homed there so every
 * screen says the same thing about the same fault. Same shape as [SaveOutcome.NotSaved].
 */
sealed interface MemberMutationOutcome {

    /**
     * The write landed.
     *
     * @param member the echoed row for an add or a role change, and NULL for a removal — the
     *   DELETE answers a bare `{success:true}`. ⚠️ Present or absent, it is not a substitute for
     *   re-reading the roster: see the ⛔ on [MembersRepository].
     */
    data class Done(val member: WorkspaceMember?) : MemberMutationOutcome

    /** ⚠️ 409 `member_exists`. The address is already on the roster; nothing was written. */
    data object Duplicate : MemberMutationOutcome

    /**
     * ⛔ 409 `last_agency_member`. Demoting or removing this member would leave the workspace with
     * no administrator, and no remaining caller would pass the agency-only guard to undo it — so
     * the server refuses rather than creating a state only support access can unpick.
     */
    data object LastAgency : MemberMutationOutcome

    /** Anything else: offline, signed out, rate limited, 403, 404, 500, or contract drift. */
    data class Failed(val failure: ApiResult.Failure) : MemberMutationOutcome
}
