package com.distronode.districtai.ui.scheduling.admin.bookings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.appLanguage
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingFieldKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingNoticeLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.schedulingDateTime
import com.distronode.districtai.ui.scheduling.admin.schedulingTime
import com.distronode.districtai.ui.UiText
import java.time.ZoneOffset

/** Whichever booking dialog [writes] has open. */
@Composable
fun SchedulingBookingDialogs(dialog: BookingDialog?, writes: SchedulingBookingWrites, timezone: String) {
    when (dialog) {
        is BookingDialog.Cancel -> SchedulingBookingCancelDialog(dialog, writes.cancel, @DontMemoize { writes.close() })
        is BookingDialog.Reschedule ->
            RescheduleDialog(dialog, writes.reschedule, @DontMemoize { writes.close() }, timezone)
        is BookingDialog.Reassign -> ReassignDialog(dialog, writes.reassign, @DontMemoize { writes.close() })
        else -> Unit
    }
}

/** ⛔ Destructive: the attendee is emailed. The reason is optional and goes in that email. */
@Composable
fun SchedulingBookingCancelDialog(dialog: BookingDialog.Cancel, cancel: BookingCancelFlow, onClose: () -> Unit) {
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_bookings_cancel_title),
        subtitle = stringResource(R.string.scheduling_admin_bookings_cancel_body),
        confirmLabel = stringResource(R.string.scheduling_admin_bookings_cancel_confirm),
        cancelLabel = stringResource(R.string.scheduling_admin_bookings_cancel_keep),
        state = dialog.state,
        onConfirm = @DontMemoize { cancel.submit() },
        onCancel = onClose,
        tag = SCHEDULING_BOOKING_CANCEL_DIALOG_TAG,
        confirmTag = SCHEDULING_BOOKING_CANCEL_CONFIRM_TAG,
        destructive = true,
    ) {
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_bookings_cancel_reason_label),
            value = dialog.reason,
            onValueChange = @DontMemoize { value -> cancel.editReason(value) },
            tag = SCHEDULING_BOOKING_CANCEL_REASON_TAG,
            enabled = dialog.state != WriteState.Working,
            hint = stringResource(R.string.scheduling_admin_bookings_cancel_reason_hint),
            error = if (dialog.reasonTooLong) {
                stringResource(R.string.scheduling_admin_bookings_cancel_reason_too_long)
            } else {
                null
            },
            kind = SchedulingFieldKind.MULTILINE,
        )
    }
}

/**
 * ⚠️ ONE DAY AT A TIME, FROM THE MEMBER'S TODAY. The reference's date picker becomes a day stepper
 * here: the slots are read per day either way, and a stepper cannot pick a day in the past.
 */
@Composable
private fun RescheduleDialog(
    dialog: BookingDialog.Reschedule,
    reschedule: BookingRescheduleFlow,
    onClose: () -> Unit,
    timezone: String,
) {
    val working = dialog.state == WriteState.Working
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_bookings_reschedule_title),
        subtitle = stringResource(R.string.scheduling_admin_bookings_reschedule_body),
        confirmLabel = stringResource(R.string.scheduling_admin_bookings_reschedule_confirm),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        // ⚠️ "Booking moved to …" names the new time, so it is drawn here rather than as a plain notice.
        state = if (dialog.state is WriteState.Done) WriteState.Idle else dialog.state,
        onConfirm = @DontMemoize { reschedule.submit() },
        onCancel = onClose,
        tag = SCHEDULING_BOOKING_RESCHEDULE_DIALOG_TAG,
        confirmTag = SCHEDULING_BOOKING_RESCHEDULE_CONFIRM_TAG,
        confirmEnabled = dialog.selectedStart != null,
        onRetry = @DontMemoize { reschedule.loadSlots() },
    ) {
        Text(
            text = stringResource(R.string.scheduling_admin_bookings_reschedule_day_label),
            style = MaterialTheme.typography.labelMedium,
            color = DistrictTheme.colors.mutedForeground,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_bookings_reschedule_previous_day),
                onClick = @DontMemoize { reschedule.previousDay() },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = !working && dialog.day > dialog.earliest,
                modifier = Modifier.testTag(SCHEDULING_BOOKING_RESCHEDULE_PREVIOUS_TAG),
            )
            Text(
                text = DistrictFormat.date(
                    dialog.day.atStartOfDay().toInstant(ZoneOffset.UTC),
                    ZoneOffset.UTC,
                    DistrictFormat.DateStyle.MEDIUM,
                    appLanguage(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(SCHEDULING_BOOKING_RESCHEDULE_DAY_TAG),
            )
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_bookings_reschedule_next_day),
                onClick = @DontMemoize { reschedule.nextDay() },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = !working,
                modifier = Modifier.testTag(SCHEDULING_BOOKING_RESCHEDULE_NEXT_TAG),
            )
        }
        when (val slots = dialog.slots) {
            is SectionState.Failed -> SchedulingSectionFailure(
                slots.failure,
                @DontMemoize { reschedule.loadSlots() },
                SCHEDULING_BOOKING_RESCHEDULE_FAILURE_TAG,
            )
            is SectionState.Ready -> if (slots.value.isEmpty()) {
                SchedulingHint(stringResource(R.string.scheduling_admin_bookings_reschedule_no_slots))
            } else {
                SchedulingChoiceRow(
                    options = slots.value.map { it.start to schedulingTime(it.start, timezone) },
                    selected = dialog.selectedStart,
                    onSelect = @DontMemoize { start -> reschedule.select(start) },
                    tagPrefix = SCHEDULING_BOOKING_RESCHEDULE_SLOT_TAG,
                    enabled = !working,
                    label = stringResource(R.string.scheduling_admin_bookings_reschedule_time_label),
                )
            }
            else -> SchedulingSectionLoading(SCHEDULING_BOOKING_RESCHEDULE_LOADING_TAG, rows = 1)
        }
        if (dialog.pickRejected) {
            SchedulingRejection(stringResource(R.string.scheduling_admin_bookings_reschedule_pick_time))
        }
        if (dialog.state is WriteState.Done) {
            SchedulingNoticeLine(
                UiText.Literal(
                    stringResource(
                        R.string.scheduling_admin_bookings_reschedule_done_at,
                        schedulingDateTime(dialog.movedTo, timezone),
                    ),
                ),
            )
        }
    }
}

/** Offered only to a scheduler admin; the hosts are the active scheduler users other than the current one. */
@Composable
private fun ReassignDialog(dialog: BookingDialog.Reassign, reassign: BookingReassignFlow, onClose: () -> Unit) {
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_bookings_reassign_title),
        subtitle = stringResource(R.string.scheduling_admin_bookings_reassign_body),
        confirmLabel = stringResource(R.string.scheduling_admin_bookings_reassign_confirm),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { reassign.submit() },
        onCancel = onClose,
        tag = SCHEDULING_BOOKING_REASSIGN_DIALOG_TAG,
        confirmTag = SCHEDULING_BOOKING_REASSIGN_CONFIRM_TAG,
        confirmEnabled = dialog.selectedHostId != null,
    ) {
        when (val hosts = dialog.hosts) {
            is SectionState.Failed -> SchedulingSectionFailure(
                hosts.failure,
                @DontMemoize { reassign.loadHosts() },
                SCHEDULING_BOOKING_REASSIGN_FAILURE_TAG,
            )
            is SectionState.Ready -> if (hosts.value.isEmpty()) {
                SchedulingHint(stringResource(R.string.scheduling_admin_bookings_reassign_no_hosts))
            } else {
                SchedulingChoiceRow(
                    options = hosts.value.map { it.id to it.name.ifEmpty { it.email } },
                    selected = dialog.selectedHostId,
                    onSelect = @DontMemoize { id -> reassign.select(id) },
                    tagPrefix = SCHEDULING_BOOKING_REASSIGN_HOST_TAG,
                    enabled = dialog.state != WriteState.Working,
                    label = stringResource(R.string.scheduling_admin_bookings_reassign_host_label),
                )
            }
            else -> SchedulingSectionLoading(SCHEDULING_BOOKING_REASSIGN_LOADING_TAG, rows = 1)
        }
    }
}

const val SCHEDULING_BOOKING_CANCEL_DIALOG_TAG: String = "district-scheduling-booking-cancel"
const val SCHEDULING_BOOKING_CANCEL_CONFIRM_TAG: String = "district-scheduling-booking-cancel-confirm"
const val SCHEDULING_BOOKING_CANCEL_REASON_TAG: String = "district-scheduling-booking-cancel-reason"
const val SCHEDULING_BOOKING_RESCHEDULE_DIALOG_TAG: String = "district-scheduling-booking-reschedule"
const val SCHEDULING_BOOKING_RESCHEDULE_CONFIRM_TAG: String = "district-scheduling-booking-reschedule-confirm"
const val SCHEDULING_BOOKING_RESCHEDULE_PREVIOUS_TAG: String = "district-scheduling-booking-reschedule-previous"
const val SCHEDULING_BOOKING_RESCHEDULE_NEXT_TAG: String = "district-scheduling-booking-reschedule-next"
const val SCHEDULING_BOOKING_RESCHEDULE_DAY_TAG: String = "district-scheduling-booking-reschedule-day"
const val SCHEDULING_BOOKING_RESCHEDULE_LOADING_TAG: String = "district-scheduling-booking-reschedule-loading"
const val SCHEDULING_BOOKING_RESCHEDULE_FAILURE_TAG: String = "district-scheduling-booking-reschedule-failure"
const val SCHEDULING_BOOKING_RESCHEDULE_SLOT_TAG: String = "district-scheduling-booking-reschedule-slot"
const val SCHEDULING_BOOKING_REASSIGN_DIALOG_TAG: String = "district-scheduling-booking-reassign"
const val SCHEDULING_BOOKING_REASSIGN_CONFIRM_TAG: String = "district-scheduling-booking-reassign-confirm"
const val SCHEDULING_BOOKING_REASSIGN_LOADING_TAG: String = "district-scheduling-booking-reassign-loading"
const val SCHEDULING_BOOKING_REASSIGN_FAILURE_TAG: String = "district-scheduling-booking-reassign-failure"
const val SCHEDULING_BOOKING_REASSIGN_HOST_TAG: String = "district-scheduling-booking-reassign-host"
