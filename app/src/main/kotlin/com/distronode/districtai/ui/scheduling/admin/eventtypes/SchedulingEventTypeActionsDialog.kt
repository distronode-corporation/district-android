package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.ui.scheduling.admin.SchedulingConfirmDialog
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.isWorking

/**
 * What can be done to an event type: on or off, archive or restore, a test email of each kind, and
 * delete; after district-ios's `SchedulingEventTypeActionsSheet`.
 *
 * ⛔ DELETE IS THE DIALOG'S DANGER BUTTON AND THE ONLY ONE THAT ASKS FIRST, with the reference's
 * wording; archive sits beside it because most people reaching for delete mean archive. ⚠️ The
 * dialog stays open after a toggle or a test email, with the sentence that says what happened.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchedulingEventTypeActionsDialog(
    dialog: EventTypeDialog.Actions,
    actions: EventTypeActionsFlow,
    onClose: () -> Unit,
) {
    if (dialog.confirmingDelete) {
        SchedulingConfirmDialog(
            title = stringResource(R.string.scheduling_admin_event_types_delete_title),
            body = stringResource(R.string.scheduling_admin_event_types_delete_body),
            confirmLabel = stringResource(R.string.scheduling_admin_event_types_delete_confirm),
            cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
            onConfirm = @DontMemoize { actions.delete() },
            onCancel = @DontMemoize { actions.askDelete(false) },
            tag = SCHEDULING_EVENT_TYPE_DELETE_DIALOG_TAG,
            confirmTag = SCHEDULING_EVENT_TYPE_DELETE_CONFIRM_TAG,
        )
        return
    }
    val enabled = !dialog.state.isWorking
    val item = dialog.eventType
    SchedulingWriteDialog(
        title = item.name,
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_event_types_delete_confirm),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { actions.askDelete(true) },
        onCancel = onClose,
        tag = SCHEDULING_EVENT_TYPE_ACTIONS_TAG,
        confirmTag = SCHEDULING_EVENT_TYPE_ACTIONS_DELETE_TAG,
        destructive = true,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(
                    if (item.isActive == false) {
                        R.string.scheduling_admin_event_types_turn_on
                    } else {
                        R.string.scheduling_admin_event_types_turn_off
                    },
                ),
                onClick = @DontMemoize { actions.toggleActive() },
                variant = ButtonVariant.Secondary,
                enabled = enabled,
                modifier = Modifier.testTag(SCHEDULING_EVENT_TYPE_ACTIONS_TOGGLE_TAG),
            )
            DistrictButton(
                text = stringResource(
                    if (item.archived == true) {
                        R.string.scheduling_admin_event_types_restore
                    } else {
                        R.string.scheduling_admin_event_types_archive
                    },
                ),
                onClick = @DontMemoize { actions.toggleArchived() },
                variant = ButtonVariant.Secondary,
                enabled = enabled,
                modifier = Modifier.testTag(SCHEDULING_EVENT_TYPE_ACTIONS_ARCHIVE_TAG),
            )
        }
        // ⚠️ The stored wording is sent, never the editor's, and to the member's own scheduler address.
        Eyebrow(stringResource(R.string.scheduling_admin_event_types_send_test_email))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            SCHEDULING_EMAIL_TEMPLATES.forEach { (type, label) ->
                DistrictButton(
                    text = stringResource(label),
                    onClick = @DontMemoize { actions.sendTestEmail(type) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    enabled = enabled,
                    modifier = Modifier.testTag("$SCHEDULING_EVENT_TYPE_TEST_EMAIL_TAG-$type"),
                )
            }
        }
    }
}

const val SCHEDULING_EVENT_TYPE_ACTIONS_TAG: String = "district-scheduling-event-type-actions"
const val SCHEDULING_EVENT_TYPE_ACTIONS_TOGGLE_TAG: String = "district-scheduling-event-type-actions-toggle"
const val SCHEDULING_EVENT_TYPE_ACTIONS_ARCHIVE_TAG: String = "district-scheduling-event-type-actions-archive"
const val SCHEDULING_EVENT_TYPE_ACTIONS_DELETE_TAG: String = "district-scheduling-event-type-actions-delete"
const val SCHEDULING_EVENT_TYPE_TEST_EMAIL_TAG: String = "district-scheduling-event-type-test-email"
const val SCHEDULING_EVENT_TYPE_DELETE_DIALOG_TAG: String = "district-scheduling-event-type-delete"
const val SCHEDULING_EVENT_TYPE_DELETE_CONFIRM_TAG: String = "district-scheduling-event-type-delete-confirm"
