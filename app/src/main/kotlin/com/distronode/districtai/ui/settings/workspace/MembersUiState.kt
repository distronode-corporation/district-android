package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.DEFAULT_MEMBER_ROLE
import com.distronode.districtai.core.model.MAX_WORKSPACE_NAME_LENGTH
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.ui.FailureText

/**
 * What the members screen holds.
 *
 * ⛔ TWO DIFFERENT ROLE GATES ON ONE SCREEN, AND COLLAPSING THEM WOULD BE WRONG IN BOTH
 * DIRECTIONS. The membership writes are **agency-only** server-side — the narrowest allow-list in
 * the API, because membership is what every other guard is derived from — while `workspace/rename`
 * admits `agency` and `client` like an ordinary write. So [canManage] is `role == AGENCY` and
 * [canRename] is [allowsMutation]; using the latter for both would offer a client three buttons
 * that 403, and using the former for both would hide a rename they are entitled to.
 *
 * ⛔ AND THE READ ADMITS `viewer`, WHICH NEITHER GATE IMPLIES. A viewer who reaches this screen
 * sees the roster and no controls at all. That is the route's own decision (a viewer who cannot
 * see who else is here cannot tell who to ask for help) and it is why the list is never gated on a
 * role.
 *
 * ⛔ THE RENAME FIELD DOES NOT PREFILL, AND THAT IS THE LOAD-FIRST RULE APPLIED HONESTLY RATHER
 * THAN A MISSING FEATURE. Nothing this client can call returns the workspace's current name: the
 * roster does not carry it, `workspace/config` excludes it (and excludes `viewer` besides), and
 * the only read that has it is `workspace/list`, which fans out across every region. A field
 * seeded from nothing is exactly the shape that turns "the load failed" into "the operator saved a
 * blank" — the trap `ConfigState` exists to prevent on this surface. So the control asks for a NEW
 * name, starts empty, and shows a current name only once one has been READ BACK from a successful
 * rename ([storedName]). It is still gated on the roster read succeeding, because "we could not
 * read who is in this workspace" is not a state in which to offer to rename it.
 *
 * ⚠️ [members] IS NEVER PATCHED LOCALLY. A removal echoes no row, and an add echoes one row of a
 * list ordered `createdAt asc` — so appending or dropping locally would show a roster in an order
 * the server does not agree with. Every write re-reads.
 */
data class MembersUiState(
    val list: MembersListState = MembersListState.Loading,
    /** ⛔ `agency` ONLY. See the ⛔ on the class. */
    val canManage: Boolean = false,
    /** ⚠️ `agency` OR `client` — a wider gate than [canManage], on purpose. */
    val canRename: Boolean = false,
    val draftEmail: String = "",
    val draftRole: WorkspaceRole = DEFAULT_MEMBER_ROLE,
    /** ⚠️ Set by a rejected add, cleared by the next keystroke. */
    val addRejected: Boolean = false,
    val addSave: SaveState = SaveState.Idle,
    val roleSave: SaveState = SaveState.Idle,
    val removeSave: SaveState = SaveState.Idle,
    val renameDraft: String = "",
    /**
     * The name the server last confirmed it stored, or null.
     *
     * ⛔ ONLY EVER SET FROM A RENAME RESPONSE — the trimmed value the route wrote, never the string
     * that was typed. Null means "this client has not read this workspace's name", which is the
     * truth on entry and must not render as an empty name.
     */
    val storedName: String? = null,
    val renameSave: SaveState = SaveState.Idle,
) {

    val members: List<WorkspaceMember>
        get() = (list as? MembersListState.Ready)?.members ?: emptyList()

    val busy: Boolean get() = addSave.busy || roleSave.busy || removeSave.busy || renameSave.busy

    /** ⚠️ True once the roster has been read, whatever it contained. Gates the rename control. */
    val loaded: Boolean get() = list is MembersListState.Ready

    /**
     * ⛔ THE ROUTE'S OWN EMAIL RULE, MIRRORED SO THE BUTTON IS HONEST. It is deliberately loose —
     * the point is that a stored address is MATCHABLE by the membership lookups, not that it is
     * deliverable — and an address that fails it is a 400. Checking here costs nothing and saves
     * an operator a round trip to be told.
     */
    val canAdd: Boolean
        get() = canManage && !busy && EMAIL_PATTERN.matches(draftEmail.trim().lowercase())

    /**
     * ⛔ TRIMMED, THEN MEASURED — the route's own order, and it is the difference between " "
     * being an empty name and a one-character one. A space is what an operator gets by tapping the
     * spacebar in an empty field.
     */
    val canRenameNow: Boolean
        get() = canRename && loaded && !busy &&
            renameDraft.trim().length in 1..MAX_WORKSPACE_NAME_LENGTH

    private companion object {
        /** The route's `EMAIL_REGEX`, character for character. */
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

/**
 * Turn a resolved role into the two gates this screen needs.
 *
 * ⚠️ AN EXTENSION ON THE NULLABLE TYPE, for the reason [allowsMutation] is one: a role that failed
 * to parse must reach "no privileges" without anybody having to remember to null-check it.
 */
internal fun WorkspaceRole?.canManageMembers(): Boolean = this == WorkspaceRole.AGENCY

/** ⚠️ Wider than [canManageMembers] — `rename` admits `client` too. */
internal fun WorkspaceRole?.canRenameWorkspace(): Boolean = allowsMutation()

/**
 * The roster's own load state.
 *
 * ⚠️ SEPARATE FROM [ConfigState] BECAUSE IT IS A DIFFERENT READ WITH A DIFFERENT PAYLOAD, and
 * because nothing behind it is a wholesale-replace save — the same reasoning [KnowledgeListState]
 * states. Reusing the config state would imply this screen hydrates from `workspace/config`, which
 * excludes `viewer` and does not carry a roster.
 */
sealed interface MembersListState {

    data object Loading : MembersListState

    /** ⚠️ Oldest first, which is the server's `createdAt asc` and not this client's choice. */
    data class Ready(val members: List<WorkspaceMember>) : MembersListState

    data class Failed(val failure: FailureText) : MembersListState
}
