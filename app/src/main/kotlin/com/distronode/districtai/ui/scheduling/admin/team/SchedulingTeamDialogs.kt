package com.distronode.districtai.ui.scheduling.admin.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingTeamMember
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingConfirmDialog
import com.distronode.districtai.ui.scheduling.admin.SchedulingFieldKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.schedulingDateTime

/** Whichever team dialog [writes] has open. [timezone] is the member's, for booking times. */
@Composable
fun SchedulingTeamDialogs(dialog: TeamDialog?, writes: SchedulingTeamWrites, timezone: String) {
    when (dialog) {
        is TeamDialog.Editor -> EditorDialog(dialog, writes.editor, @DontMemoize { writes.close() })
        is TeamDialog.Members -> MembersDialog(dialog, writes.members, @DontMemoize { writes.close() })
        is TeamDialog.Archive -> ArchiveDialog(dialog, writes.archive, @DontMemoize { writes.close() }, timezone)
        // ⚠️ `else` is no dialog: an exhaustive dispatch in a composable leaves an unreachable branch.
        else -> Unit
    }
}

/**
 * Create a team, or rename and delete one.
 *
 * ⚠️ THE DELETE IS NOT THE DIALOG'S PRIMARY BUTTON AND HAS ITS OWN QUESTION: the primary commits a
 * rename, and the two differ by everything while sitting under the same thumb.
 */
@Composable
private fun EditorDialog(dialog: TeamDialog.Editor, flow: TeamEditorFlow, onClose: () -> Unit) {
    val creating = dialog.teamId == null
    val working = dialog.state.isWorking
    SchedulingWriteDialog(
        title = if (creating) stringResource(R.string.scheduling_admin_team_create_title) else dialog.teamName,
        subtitle = stringResource(R.string.scheduling_admin_team_teams_subtitle),
        confirmLabel = stringResource(
            if (creating) R.string.scheduling_admin_team_create else R.string.scheduling_admin_team_rename,
        ),
        cancelLabel = stringResource(
            if (creating) R.string.scheduling_admin_common_cancel else R.string.scheduling_admin_team_close,
        ),
        state = dialog.state,
        onConfirm = @DontMemoize { flow.submit() },
        onCancel = onClose,
        tag = SCHEDULING_TEAM_EDITOR_DIALOG_TAG,
        confirmTag = SCHEDULING_TEAM_EDITOR_CONFIRM_TAG,
        onRetry = @DontMemoize { flow.submit() },
    ) {
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_team_name_label),
            value = dialog.name,
            onValueChange = @DontMemoize { flow.editName(it) },
            tag = SCHEDULING_TEAM_NAME_TAG,
            enabled = !working,
            error = dialog.nameRejected?.let { stringResource(it) },
        )
        if (!creating) {
            SchedulingHint(stringResource(R.string.scheduling_admin_team_slug_read_only_hint))
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_team_delete_confirm),
                onClick = @DontMemoize { flow.askDelete(true) },
                variant = ButtonVariant.Danger,
                enabled = !working,
                modifier = Modifier.testTag(SCHEDULING_TEAM_DELETE_TAG),
            )
        }
    }
    if (dialog.confirmingDelete) {
        SchedulingConfirmDialog(
            title = stringResource(R.string.scheduling_admin_team_delete_title, dialog.teamName),
            body = stringResource(R.string.scheduling_admin_team_delete_body),
            confirmLabel = stringResource(R.string.scheduling_admin_team_delete_confirm),
            cancelLabel = stringResource(R.string.scheduling_admin_team_delete_keep),
            onConfirm = @DontMemoize { flow.delete() },
            onCancel = @DontMemoize { flow.askDelete(false) },
            tag = SCHEDULING_TEAM_DELETE_DIALOG_TAG,
            confirmTag = SCHEDULING_TEAM_DELETE_CONFIRM_TAG,
        )
    }
}

/**
 * One team's roster. ⚠️ Every control saves on its own button, so the dialog's dismissal reads
 * "Done" rather than "Cancel"; its primary button adds the host picked below.
 */
@Composable
private fun MembersDialog(dialog: TeamDialog.Members, flow: TeamMembersFlow, onClose: () -> Unit) {
    SchedulingWriteDialog(
        title = dialog.team.name,
        subtitle = stringResource(R.string.scheduling_admin_team_members_hint),
        confirmLabel = stringResource(R.string.scheduling_admin_team_add),
        cancelLabel = stringResource(R.string.scheduling_admin_team_close),
        state = dialog.state,
        onConfirm = @DontMemoize { flow.addMember() },
        onCancel = onClose,
        tag = SCHEDULING_TEAM_MEMBERS_DIALOG_TAG,
        confirmTag = SCHEDULING_TEAM_MEMBERS_ADD_TAG,
        confirmEnabled = dialog.pickedUserId != null,
    ) {
        if (dialog.roster.isEmpty()) {
            SchedulingHint(stringResource(R.string.scheduling_admin_team_members_empty_roster))
        }
        dialog.roster.forEach { RosterRow(it, dialog, flow) }
        when (val users = dialog.users) {
            is SectionState.Failed -> SchedulingSectionFailure(
                failure = users.failure,
                onRetry = @DontMemoize { flow.loadUsers() },
                tag = SCHEDULING_TEAM_USERS_FAILURE_TAG,
            )
            is SectionState.Ready -> if (dialog.addable.isEmpty()) {
                SchedulingHint(stringResource(R.string.scheduling_admin_team_add_none))
            } else {
                SchedulingChoiceRow(
                    options = dialog.addable.map { it.id to it.name.ifEmpty { it.email } },
                    selected = dialog.pickedUserId,
                    onSelect = @DontMemoize { flow.pick(it) },
                    tagPrefix = SCHEDULING_TEAM_PICK_TAG,
                    enabled = !dialog.state.isWorking,
                    label = stringResource(R.string.scheduling_admin_team_add_label),
                )
            }
            // ⚠️ `else` IS LOADING: an exhaustive sealed `when` here leaves a branch no test reaches.
            else -> SchedulingSectionLoading(SCHEDULING_TEAM_USERS_LOADING_TAG, rows = 1)
        }
    }
    dialog.removing?.let {
        SchedulingConfirmDialog(
            title = stringResource(R.string.scheduling_admin_team_remove_title),
            body = stringResource(R.string.scheduling_admin_team_remove_body),
            confirmLabel = stringResource(R.string.scheduling_admin_team_remove),
            cancelLabel = stringResource(R.string.scheduling_admin_team_remove_keep),
            onConfirm = @DontMemoize { flow.removeMember() },
            onCancel = @DontMemoize { flow.askRemove(null) },
            tag = SCHEDULING_TEAM_REMOVE_DIALOG_TAG,
            confirmTag = SCHEDULING_TEAM_REMOVE_CONFIRM_TAG,
        )
    }
}

/**
 * ⚠️ A NUMBER FIELD, NOT A STEPPER: the field has to hold "" and "2.5" long enough to refuse them
 * by name, which is how the web teaches the rule.
 */
@Composable
private fun RosterRow(member: SchedulingTeamMember, dialog: TeamDialog.Members, flow: TeamMembersFlow) {
    val enabled = !dialog.state.isWorking
    SchedulingCard(eyebrow = member.name.ifEmpty { member.email }) {
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_team_priority_label),
            // ⚠️ Every member has a draft: the drafts are seeded from the same roster this row is.
            value = dialog.priorityDrafts.getValue(member.id),
            onValueChange = @DontMemoize { flow.editPriority(member.id, it) },
            tag = "$SCHEDULING_TEAM_PRIORITY_TAG-${member.id}",
            enabled = enabled,
            error = if (member.id in dialog.priorityRejected) {
                stringResource(R.string.scheduling_admin_team_priority_invalid)
            } else {
                null
            },
            kind = SchedulingFieldKind.NUMBER,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_team_save_priority),
                onClick = @DontMemoize { flow.savePriority(member.id) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$SCHEDULING_TEAM_SAVE_PRIORITY_TAG-${member.id}"),
            )
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_team_remove),
                onClick = @DontMemoize { flow.askRemove(member) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$SCHEDULING_TEAM_REMOVE_TAG-${member.id}"),
            )
        }
    }
}

/**
 * ⛔ IT SHOWS THE BLOCKING BOOKINGS RATHER THAN JUST REFUSING: the fork's refusal while somebody
 * still hosts is the design, so the dialog lists every upcoming booking, with whom and when, which
 * is what an operator needs in order to go and move them. ⚠️ And it says why a disabled button is
 * disabled.
 */
@Composable
private fun ArchiveDialog(dialog: TeamDialog.Archive, flow: UserArchiveFlow, onClose: () -> Unit, timezone: String) {
    val title = stringResource(R.string.scheduling_admin_team_archive_title, dialog.userName)
    val body = stringResource(R.string.scheduling_admin_team_archive_body)
    SchedulingWriteDialog(
        title = title,
        subtitle = body,
        confirmLabel = stringResource(R.string.scheduling_admin_team_archive),
        cancelLabel = stringResource(R.string.scheduling_admin_team_archive_keep),
        state = dialog.state,
        onConfirm = @DontMemoize { flow.askConfirm(true) },
        onCancel = onClose,
        tag = SCHEDULING_TEAM_ARCHIVE_DIALOG_TAG,
        confirmTag = SCHEDULING_TEAM_ARCHIVE_CONFIRM_TAG,
        destructive = true,
        confirmEnabled = dialog.canArchive,
    ) {
        when (val upcoming = dialog.upcoming) {
            is SectionState.Failed -> {
                SchedulingHint(stringResource(R.string.scheduling_admin_team_archive_counting))
                SchedulingSectionFailure(
                    failure = upcoming.failure,
                    onRetry = @DontMemoize { flow.loadUpcoming() },
                    tag = SCHEDULING_TEAM_UPCOMING_FAILURE_TAG,
                )
            }
            is SectionState.Ready -> if (upcoming.value.isNotEmpty()) {
                SchedulingHint(stringResource(R.string.scheduling_admin_team_archive_blocked))
                upcoming.value.forEach { booking ->
                    SchedulingReadOnlyRow(
                        label = booking.eventTypeName,
                        value = stringResource(
                            R.string.scheduling_admin_team_upcoming_line,
                            booking.attendeeName,
                            schedulingDateTime(booking.startAt, timezone),
                        ),
                    )
                }
            }
            // ⚠️ `else` IS LOADING: an exhaustive sealed `when` here leaves a branch no test reaches.
            else -> {
                SchedulingHint(stringResource(R.string.scheduling_admin_team_archive_counting))
                SchedulingSectionLoading(SCHEDULING_TEAM_UPCOMING_LOADING_TAG, rows = 1)
            }
        }
    }
    if (dialog.confirming) {
        SchedulingConfirmDialog(
            title = title,
            body = body,
            confirmLabel = stringResource(R.string.scheduling_admin_team_archive),
            cancelLabel = stringResource(R.string.scheduling_admin_team_archive_keep),
            onConfirm = @DontMemoize { flow.archive() },
            onCancel = @DontMemoize { flow.askConfirm(false) },
            tag = SCHEDULING_TEAM_ARCHIVE_QUESTION_TAG,
            confirmTag = SCHEDULING_TEAM_ARCHIVE_QUESTION_CONFIRM_TAG,
        )
    }
}

const val SCHEDULING_TEAM_EDITOR_DIALOG_TAG: String = "district-scheduling-team-editor"
const val SCHEDULING_TEAM_EDITOR_CONFIRM_TAG: String = "district-scheduling-team-editor-confirm"
const val SCHEDULING_TEAM_NAME_TAG: String = "district-scheduling-team-name"
const val SCHEDULING_TEAM_DELETE_TAG: String = "district-scheduling-team-delete"
const val SCHEDULING_TEAM_DELETE_DIALOG_TAG: String = "district-scheduling-team-delete-dialog"
const val SCHEDULING_TEAM_DELETE_CONFIRM_TAG: String = "district-scheduling-team-delete-confirm"
const val SCHEDULING_TEAM_MEMBERS_DIALOG_TAG: String = "district-scheduling-team-members"
const val SCHEDULING_TEAM_MEMBERS_ADD_TAG: String = "district-scheduling-team-members-add"
const val SCHEDULING_TEAM_USERS_LOADING_TAG: String = "district-scheduling-team-users-loading"
const val SCHEDULING_TEAM_USERS_FAILURE_TAG: String = "district-scheduling-team-users-failure"
const val SCHEDULING_TEAM_PICK_TAG: String = "district-scheduling-team-pick"
const val SCHEDULING_TEAM_PRIORITY_TAG: String = "district-scheduling-team-priority"
const val SCHEDULING_TEAM_SAVE_PRIORITY_TAG: String = "district-scheduling-team-save-priority"
const val SCHEDULING_TEAM_REMOVE_TAG: String = "district-scheduling-team-remove"
const val SCHEDULING_TEAM_REMOVE_DIALOG_TAG: String = "district-scheduling-team-remove-dialog"
const val SCHEDULING_TEAM_REMOVE_CONFIRM_TAG: String = "district-scheduling-team-remove-confirm"
const val SCHEDULING_TEAM_ARCHIVE_DIALOG_TAG: String = "district-scheduling-team-archive-dialog"
const val SCHEDULING_TEAM_ARCHIVE_CONFIRM_TAG: String = "district-scheduling-team-archive-confirm"
const val SCHEDULING_TEAM_UPCOMING_LOADING_TAG: String = "district-scheduling-team-upcoming-loading"
const val SCHEDULING_TEAM_UPCOMING_FAILURE_TAG: String = "district-scheduling-team-upcoming-failure"
const val SCHEDULING_TEAM_ARCHIVE_QUESTION_TAG: String = "district-scheduling-team-archive-question"
const val SCHEDULING_TEAM_ARCHIVE_QUESTION_CONFIRM_TAG: String = "district-scheduling-team-archive-question-confirm"
