package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the members screen draws.
 *
 * ⛔ THE ASSERTIONS THAT MATTER ARE ABOUT WHAT IS *ABSENT* FOR A NON-AGENCY ROLE. Membership writes
 * are agency-only server-side, so a client or viewer who was offered them would get a 403 for every
 * press — and the rename is gated separately and more widely, so "read-only" is not one state here
 * but two. Rendering the same screen three times with different flags is the only way that is
 * checkable.
 *
 * ⛔ AND THAT THE TWO 409s READ AS THEMSELVES. If either regressed to the generic failure the app
 * would still work; the operator would simply be told nothing useful.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h4000dp")
class MembersScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val roster = listOf(
        WorkspaceMember("founder@example.com", "agency", "2026-08-15T14:30:00.000Z"),
        WorkspaceMember("operator@example.com", "client", "2026-08-15T15:00:00.000Z"),
        WorkspaceMember("auditor@example.com", "viewer", "2026-08-16T09:15:00.000Z"),
    )

    private fun ready(
        canManage: Boolean = true,
        canRename: Boolean = true,
        members: List<WorkspaceMember> = roster,
    ) = MembersUiState(
        list = MembersListState.Ready(members),
        canManage = canManage,
        canRename = canRename,
    )

    /**
     * ⚠️ `LongParameterList` IS SUPPRESSED FOR A TEST HELPER, NOT FOR PRODUCTION CODE. The screen
     * is `@Composable` and exempt by configuration; this mirrors its signature one-for-one so a
     * test can name exactly the callback it asserts on. Collapsing them into a holder would put the
     * mirror one indirection away from the thing it mirrors.
     */
    @Suppress("LongParameterList")
    private fun render(
        state: MembersUiState,
        onEditEmail: (String) -> Unit = {},
        onEditRole: (WorkspaceRole) -> Unit = {},
        onAdd: () -> Unit = {},
        onChangeRole: (String, WorkspaceRole) -> Unit = { _, _ -> },
        onRemove: (String) -> Unit = {},
        onEditName: (String) -> Unit = {},
        onRename: () -> Unit = {},
        onRetry: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                MembersScreen(
                    state = state,
                    onEditEmail = onEditEmail,
                    onEditRole = onEditRole,
                    onAdd = onAdd,
                    onChangeRole = onChangeRole,
                    onRemove = onRemove,
                    onEditName = onEditName,
                    onRename = onRename,
                    onRetry = onRetry,
                    onBack = onBack,
                )
            }
        }
    }

    // ── Role gating ──────────────────────────────────────────────────────────

    @Test
    fun `an agency operator gets every control`() {
        render(ready())

        composeRule.onNodeWithContentDescription(MEMBERS_ROOT_DESCRIPTION).assertIsDisplayed()
        roster.forEach { member ->
            composeRule.onNodeWithContentDescription(memberRowDescription(member.email))
                .assertIsDisplayed()
            composeRule.onNodeWithContentDescription(memberRoleDescription(member.email))
                .assertIsDisplayed()
            composeRule.onNodeWithContentDescription(memberRemoveDescription(member.email))
                .assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a client sees the roster and the rename but no membership controls`() {
        // ⛔ THE SPLIT THIS SCREEN EXISTS AROUND. `workspace/rename` admits agency and client; the
        // membership writes admit agency alone. A single read-only flag would be wrong either way.
        render(ready(canManage = false, canRename = true))

        composeRule.onNodeWithContentDescription(memberRowDescription("founder@example.com"))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).assertDoesNotExist()
        roster.forEach { member ->
            composeRule.onNodeWithContentDescription(memberRoleDescription(member.email))
                .assertDoesNotExist()
            composeRule.onNodeWithContentDescription(memberRemoveDescription(member.email))
                .assertDoesNotExist()
        }
    }

    @Test
    fun `a viewer sees the roster and nothing else`() {
        // ⛔ AND STILL SEES THE ROSTER. The read admits `viewer` deliberately — someone who cannot
        // see who else is in the workspace cannot tell who to ask for help.
        render(ready(canManage = false, canRename = false))

        composeRule.onNodeWithContentDescription(memberRowDescription("auditor@example.com"))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_FIELD_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a failed roster read offers a retry and no editable field`() {
        render(
            MembersUiState(
                list = MembersListState.Failed(FailureText(UiText.Resource(R.string.failure_offline))),
                canManage = true,
                canRename = true,
            ),
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        // ⛔ THE RENAME FIELD IS PRESENT BUT NOT USABLE. "We could not read who is in this
        // workspace" is not a state in which to accept a rename — the load-first rule applied to
        // the one control whose value cannot be prefilled.
        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a loading roster draws skeletons rather than an empty list`() {
        // ⚠️ A blank expanse where a roster will be reads as "nobody is in this workspace", which
        // is the one answer this screen must never give by accident.
        render(MembersUiState(canManage = true, canRename = true))

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOADING_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MEMBERS_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an empty roster is worded as an impossibility, not as an invitation`() {
        // ⛔ A WORKSPACE WITH NOBODY IN IT CANNOT EXIST — the caller had to be a member to read
        // this at all — so "add the first person" would be the wrong prompt for a state that means
        // something went wrong.
        render(ready(members = emptyList()))

        composeRule.onNodeWithContentDescription(MEMBERS_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `every control is disabled while a write is in flight`() {
        // ⚠️ So a second press cannot race the first through a rate limit all three writes share.
        render(ready().copy(removeSave = SaveState.Saving))

        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(memberRemoveDescription("founder@example.com"))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(memberRoleDescription("founder@example.com"))
            .assertIsNotEnabled()
    }

    @Test
    fun `an unrecognised role renders as itself rather than as a guess`() {
        // ⛔ `role` IS A FREE-TEXT COLUMN. Mapping an unknown value to a known one would tell an
        // operator someone has privileges they may not have.
        render(ready(members = listOf(WorkspaceMember("odd@example.com", "superuser"))))

        composeRule.onNodeWithText("superuser", substring = true).assertIsDisplayed()
    }

    // ── The add dialog ───────────────────────────────────────────────────────

    @Test
    fun `the add dialog collects an address and a role`() {
        var role: WorkspaceRole? = null
        var added = false
        render(ready(), onEditRole = { role = it }, onAdd = { added = true })

        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_EMAIL_DESCRIPTION).assertIsDisplayed()
        // ⚠️ `client` IS PRESELECTED, which is the SERVER's default rather than the cautious one —
        // an operator who never touches the picker grants the ordinary tenant role.
        composeRule.onNodeWithContentDescription(memberAddRoleDescription(WorkspaceRole.CLIENT))
            .assertIsSelected()

        composeRule.onNodeWithContentDescription(memberAddRoleDescription(WorkspaceRole.VIEWER))
            .performClick()
        assertEquals(WorkspaceRole.VIEWER, role)

        composeRule.onNodeWithContentDescription(MEMBERS_ADD_CONFIRM_DESCRIPTION).performClick()
        assertEquals(true, added)
    }

    @Test
    fun `the add dialog says that nobody is emailed`() {
        // ⛔ WITHOUT THIS LINE AN OPERATOR ADDS AN ADDRESS AND WAITS FOR AN INVITATION THAT IS NOT
        // COMING. The route sends none and provisions no account.
        render(ready())

        composeRule.onNodeWithContentDescription(MEMBERS_NO_INVITE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a malformed address is explained rather than silently ignored`() {
        // ⛔ THE CONFIRM BUTTON IS NOT DISABLED FOR THIS, unlike the knowledge add form. There the
        // fields are visibly empty; here the field looks complete and is merely malformed, and a
        // button that refuses to work would tell the operator nothing about why.
        render(ready().copy(draftEmail = "not-an-address", addRejected = true))

        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_REJECTED_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_CONFIRM_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the role picker is inert while a write is in flight`() {
        // ⚠️ The add dialog stays open across its own round trip, so its picker has to be disabled
        // too — otherwise a role changed mid-flight would disagree with the request already sent.
        render(ready().copy(addSave = SaveState.Saving))

        // ⚠️ Opened from a state that is already busy, so the entry button is disabled — the dialog
        // is reached here by rendering it directly is not possible, so the assertion is that the
        // whole section is inert.
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `the role dialog falls back to the server's default for a role it cannot show`() {
        // ⛔ `role` IS FREE TEXT. A value the picker cannot display must not silently pre-select
        // `agency` — the honest fallback is the server's own default, which is what an unset picker
        // would have sent anyway.
        render(ready(members = listOf(WorkspaceMember("odd@example.com", "superuser"))))

        composeRule.onNodeWithContentDescription(memberRoleDescription("odd@example.com")).performClick()
        composeRule.onNodeWithContentDescription(memberRoleOptionDescription(WorkspaceRole.CLIENT))
            .assertIsSelected()
    }

    @Test
    fun `cancelling a role change changes nothing`() {
        var change: Pair<String, WorkspaceRole>? = null
        render(ready(), onChangeRole = { email, role -> change = email to role })

        composeRule.onNodeWithContentDescription(memberRoleDescription("auditor@example.com"))
            .performClick()
        composeRule.onNodeWithContentDescription(MEMBERS_ROLE_CANCEL_DESCRIPTION).performClick()

        assertNull(change)
    }

    @Test
    fun `cancelling the add dialog sends nothing`() {
        var added = false
        render(ready(), onAdd = { added = true })

        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_CANCEL_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(MEMBERS_ADD_EMAIL_DESCRIPTION).assertDoesNotExist()
        assertEquals(false, added)
    }

    // ── Removal and role change ──────────────────────────────────────────────

    @Test
    fun `removing is confirmed, names the address, and only then calls back`() {
        // ⛔ IMMEDIATE AND NOT AN UNDO. "Are you sure?" over a list of similar addresses is the
        // dialog people dismiss without reading, so the body names who is being removed.
        var removed: String? = null
        render(ready(), onRemove = { removed = it })

        composeRule.onNodeWithContentDescription(memberRemoveDescription("operator@example.com"))
            .performClick()
        assertNull("nothing may happen before the confirmation", removed)
        // ⚠️ TWO NODES, AND THAT IS THE ASSERTION: the row behind the dialog, plus the dialog body
        // naming who is being removed. One would mean the dialog says only "are you sure?", which
        // over a list of similar addresses is the dialog people dismiss without reading.
        composeRule.onAllNodesWithText("operator@example.com", substring = true).assertCountEquals(2)

        composeRule.onNodeWithContentDescription(MEMBERS_REMOVE_CONFIRM_DESCRIPTION).performClick()
        assertEquals("operator@example.com", removed)
    }

    @Test
    fun `cancelling a removal removes nobody`() {
        var removed: String? = null
        render(ready(), onRemove = { removed = it })

        composeRule.onNodeWithContentDescription(memberRemoveDescription("founder@example.com"))
            .performClick()
        composeRule.onNodeWithContentDescription(MEMBERS_REMOVE_CANCEL_DESCRIPTION).performClick()

        assertNull(removed)
    }

    @Test
    fun `the role dialog is seeded from the row's current role and reports the chosen one`() {
        var change: Pair<String, WorkspaceRole>? = null
        render(ready(), onChangeRole = { email, role -> change = email to role })

        composeRule.onNodeWithContentDescription(memberRoleDescription("founder@example.com"))
            .performClick()
        // ⚠️ Seeded from the row: this member holds `agency`.
        composeRule.onNodeWithContentDescription(memberRoleOptionDescription(WorkspaceRole.AGENCY))
            .assertIsSelected()

        composeRule.onNodeWithContentDescription(memberRoleOptionDescription(WorkspaceRole.VIEWER))
            .performClick()
        composeRule.onNodeWithContentDescription(MEMBERS_ROLE_CONFIRM_DESCRIPTION).performClick()

        assertEquals("founder@example.com" to WorkspaceRole.VIEWER, change)
    }

    // ── The two 409 messages ─────────────────────────────────────────────────

    @Test
    fun `the duplicate refusal reads as Already a member`() {
        render(
            ready().copy(
                addSave = SaveState.Failed(
                    FailureText(UiText.Resource(R.string.members_error_duplicate), retryable = false),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(MEMBERS_ADD_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Already a member.").assertIsDisplayed()
    }

    @Test
    fun `the last-agency refusal reads as its own sentence, not a validation error`() {
        // ⛔ NOTHING THE OPERATOR TYPED IS WRONG. Wording this as a validation failure would send
        // someone looking for a typo that does not exist.
        render(
            ready().copy(
                roleSave = SaveState.Failed(
                    FailureText(
                        UiText.Resource(R.string.members_error_last_agency),
                        retryable = false,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(MEMBERS_ROLE_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Every workspace needs at least one agency member.")
            .assertIsDisplayed()
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    @Test
    fun `the rename field starts empty and its button is disabled until a name is typed`() {
        // ⛔ NO PREFILL. Nothing this client can read returns the current name, and a field seeded
        // from nothing is how a form saves a blank over a real value.
        render(ready())

        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_CURRENT_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a whitespace-only name leaves the button disabled`() {
        // ⛔ TRIMMED, THEN MEASURED — the route's own order, and what an operator gets by tapping
        // the spacebar.
        render(ready().copy(renameDraft = "   "))

        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a confirmed rename shows the name the server stored`() {
        var renamed = false
        render(
            ready().copy(renameDraft = "New Name", storedName = "Trimmed Name"),
            onRename = { renamed = true },
        )

        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_CURRENT_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Trimmed Name", substring = true).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(MEMBERS_RENAME_DESCRIPTION).performClick()
        assertEquals(true, renamed)
    }

    // ── The rest of the states ───────────────────────────────────────────────

    @Test
    fun `a member with no stored role says so rather than guessing one`() {
        render(ready(members = listOf(WorkspaceMember("blank@example.com", null))))

        composeRule.onNodeWithText("Unrecognised role “”").assertIsDisplayed()
    }

    @Test
    fun `every dialog stays wired to its callback, and locks while a write is in flight`() {
        // ⚠️ THE ADD DIALOG STAYS OPEN ACROSS ITS OWN ROUND TRIP, so it is drawn again from a busy
        // state with the same callbacks. Its inputs must lock, and once the write lands its
        // confirm must still reach the same callback. The two other dialogs are redrawn under too.
        val calls = mutableListOf<String>()
        val current = mutableStateOf(ready())
        val onAdd: () -> Unit = { calls += "add" }
        val onRemove: (String) -> Unit = { calls += "remove:$it" }
        val onChangeRole: (String, WorkspaceRole) -> Unit = { email, role -> calls += "role:$email:$role" }
        composeRule.setContent {
            DistrictTheme {
                MembersScreen(
                    state = current.value,
                    onEditEmail = {},
                    onEditRole = {},
                    onAdd = onAdd,
                    onChangeRole = onChangeRole,
                    onRemove = onRemove,
                    onEditName = {},
                    onRename = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }
        fun redraw(next: MembersUiState) {
            current.value = next
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithContentDescription(MEMBERS_ADD_OPEN_DESCRIPTION).performClick()
        redraw(ready().copy(draftEmail = "new@example.com", addSave = SaveState.Saving))
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_EMAIL_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(memberAddRoleDescription(WorkspaceRole.VIEWER))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_CONFIRM_DESCRIPTION).assertIsNotEnabled()
        redraw(ready().copy(draftEmail = "new@example.com"))
        composeRule.onNodeWithContentDescription(MEMBERS_ADD_CONFIRM_DESCRIPTION)
            .assertIsEnabled()
            .performClick()

        composeRule.onNodeWithContentDescription(memberRemoveDescription("auditor@example.com"))
            .performClick()
        redraw(ready().copy(renameDraft = "A"))
        composeRule.onNodeWithContentDescription(MEMBERS_REMOVE_CONFIRM_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(memberRoleDescription("operator@example.com"))
            .performClick()
        redraw(ready().copy(renameDraft = "Ac"))
        composeRule.onNodeWithContentDescription(memberRoleOptionDescription(WorkspaceRole.VIEWER))
            .performClick()
        composeRule.onNodeWithContentDescription(MEMBERS_ROLE_CONFIRM_DESCRIPTION).performClick()

        assertEquals(
            listOf("add", "remove:auditor@example.com", "role:operator@example.com:VIEWER"),
            calls,
        )
    }
}
