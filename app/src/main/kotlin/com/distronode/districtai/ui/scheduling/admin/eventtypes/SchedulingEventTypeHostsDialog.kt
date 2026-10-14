package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingFieldKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.isWorking

/**
 * Who can be booked on this event type, and how the next booking reaches them; after district-ios's
 * `SchedulingEventTypeHostsSheet`.
 *
 * ⛔ SAVE IS OFF UNTIL THIS DIALOG'S OWN READ OF THE HOSTS IS IN HAND: the save replaces the list
 * wholesale. ⚠️ The rows are in the order the fork served them and there is no drag; `priority` is
 * the only ordering signal, read only under the Priority rotation.
 */
@Composable
fun SchedulingEventTypeHostsDialog(dialog: EventTypeDialog.Hosts, hosts: EventTypeHostsFlow, onClose: () -> Unit) {
    val enabled = !dialog.state.isWorking
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_event_types_hosts_title),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_common_save),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { hosts.save() },
        onCancel = onClose,
        tag = SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_TAG,
        confirmTag = SCHEDULING_EVENT_TYPE_HOSTS_SAVE_TAG,
        confirmEnabled = dialog.load is SectionState.Ready,
    ) {
        Routing(dialog, hosts, enabled)
        when (val load = dialog.load) {
            is SectionState.Ready -> HostRows(dialog, hosts, enabled)
            is SectionState.Failed ->
                SchedulingSectionFailure(
                    load.failure,
                    @DontMemoize { hosts.load() },
                    SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_FAILURE_TAG,
                )
            else -> SchedulingSectionLoading(SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_LOADING_TAG, rows = 1)
        }
        SchedulingRejection(dialog.validation?.let { stringResource(it) })
    }
}

/** ⚠️ The rotation choice is drawn only for round robin: it decides nothing under the other two. */
@Composable
private fun Routing(dialog: EventTypeDialog.Hosts, hosts: EventTypeHostsFlow, enabled: Boolean) {
    SchedulingChoiceRow(
        options = SCHEDULING_ROUTING_MODES.map { (wire, label) -> wire to stringResource(label) },
        selected = dialog.routingMode,
        onSelect = @DontMemoize { hosts.setRouting(it) },
        tagPrefix = SCHEDULING_EVENT_TYPE_ROUTING_TAG,
        enabled = enabled,
        label = stringResource(R.string.scheduling_admin_event_types_routing_mode_label),
    )
    if (dialog.routingMode == EventTypeHostsFlow.ROUND_ROBIN) {
        SchedulingChoiceRow(
            options = SCHEDULING_ROTATION_STRATEGIES.map { (wire, label) -> wire to stringResource(label) },
            selected = dialog.rotationStrategy,
            onSelect = @DontMemoize { hosts.setRotation(it) },
            tagPrefix = SCHEDULING_EVENT_TYPE_ROTATION_TAG,
            enabled = enabled,
            label = stringResource(R.string.scheduling_admin_event_types_rotation_label),
        )
        SchedulingHint(stringResource(R.string.scheduling_admin_event_types_rotation_hint))
    }
}

/**
 * ⚠️ THE ADD CHOICE IS HIDDEN, NOT DISABLED, WHEN THE DIRECTORY READ WAS REFUSED, which is what the
 * reference and the web do; it is also hidden when every active scheduler user already hosts.
 */
@Composable
private fun HostRows(dialog: EventTypeDialog.Hosts, hosts: EventTypeHostsFlow, enabled: Boolean) {
    if (dialog.rows.isEmpty()) SchedulingHint(stringResource(R.string.scheduling_admin_event_types_hosts_empty))
    dialog.rows.forEach { HostRow(it, hosts, enabled) }
    if (dialog.canAddHosts && dialog.candidates.isNotEmpty()) {
        SchedulingChoiceRow(
            options = dialog.candidates.map { it.id to it.name.ifEmpty { it.email } },
            selected = null,
            onSelect = @DontMemoize { hosts.add(it) },
            tagPrefix = SCHEDULING_EVENT_TYPE_ADD_HOST_TAG,
            enabled = enabled,
            label = stringResource(R.string.scheduling_admin_event_types_add_host),
        )
    }
}

@Composable
private fun HostRow(row: SchedulingHostRow, hosts: EventTypeHostsFlow, enabled: Boolean) {
    Column(
        modifier = Modifier.testTag("$SCHEDULING_EVENT_TYPE_HOST_ROW_TAG-${row.userId}"),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Text(
            text = row.name.ifEmpty { row.email },
            style = MaterialTheme.typography.titleSmall,
            color = DistrictTheme.colors.foreground,
        )
        SchedulingHint(row.email)
        SchedulingChoiceRow(
            options = SCHEDULING_HOST_ROLES.map { (wire, label) -> wire to stringResource(label) },
            selected = row.role,
            onSelect = @DontMemoize { role -> hosts.editRow(row.userId) { it.copy(role = role) } },
            tagPrefix = "$SCHEDULING_EVENT_TYPE_HOST_ROLE_TAG-${row.userId}",
            enabled = enabled,
        )
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_event_types_priority_label),
            value = row.priority,
            onValueChange = @DontMemoize { value -> hosts.editRow(row.userId) { it.copy(priority = value) } },
            tag = "$SCHEDULING_EVENT_TYPE_HOST_PRIORITY_TAG-${row.userId}",
            enabled = enabled,
            kind = SchedulingFieldKind.NUMBER,
        )
        DistrictButton(
            text = stringResource(R.string.scheduling_admin_event_types_remove),
            onClick = @DontMemoize { hosts.remove(row.userId) },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            enabled = enabled,
            modifier = Modifier.testTag("$SCHEDULING_EVENT_TYPE_HOST_REMOVE_TAG-${row.userId}"),
        )
    }
}

const val SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_TAG: String = "district-scheduling-event-type-hosts-dialog"
const val SCHEDULING_EVENT_TYPE_HOSTS_SAVE_TAG: String = "district-scheduling-event-type-hosts-save"
const val SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_LOADING_TAG: String = "district-scheduling-event-type-hosts-dialog-loading"
const val SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_FAILURE_TAG: String = "district-scheduling-event-type-hosts-dialog-failure"
const val SCHEDULING_EVENT_TYPE_ROUTING_TAG: String = "district-scheduling-event-type-routing"
const val SCHEDULING_EVENT_TYPE_ROTATION_TAG: String = "district-scheduling-event-type-rotation"
const val SCHEDULING_EVENT_TYPE_ADD_HOST_TAG: String = "district-scheduling-event-type-add-host"
const val SCHEDULING_EVENT_TYPE_HOST_ROW_TAG: String = "district-scheduling-event-type-host"
const val SCHEDULING_EVENT_TYPE_HOST_ROLE_TAG: String = "district-scheduling-event-type-host-role"
const val SCHEDULING_EVENT_TYPE_HOST_PRIORITY_TAG: String = "district-scheduling-event-type-host-priority"
const val SCHEDULING_EVENT_TYPE_HOST_REMOVE_TAG: String = "district-scheduling-event-type-host-remove"
