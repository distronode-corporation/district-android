package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.ui.scheduling.admin.DAYS_IN_WEEK
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingConfirmDialog
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingToggleRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteOutcome
import com.distronode.districtai.ui.scheduling.admin.WriteState

/** Whichever settings dialog [writes] has open, and the image Remove prompt over it. */
@Composable
fun SchedulingSettingsDialogs(writes: SchedulingSettingsWrites, onPickImage: (SchedulingAdminUploadTarget) -> Unit) {
    val dialog by writes.dialog.collectAsState()
    when (val open = dialog) {
        is SettingsDialog.Profile -> ProfileDialog(open, writes, onPickImage)
        is SettingsDialog.Notifications -> NotificationsDialog(open, writes)
        is SettingsDialog.Branding -> SettingsBrandingDialog(open, writes, onPickImage)
        is SettingsDialog.Assistant -> SettingsAssistantDialog(open, writes)
        null -> Unit
    }
    val removing = (dialog as? SettingsDialog.Profile)?.confirmingRemove == true ||
        (dialog as? SettingsDialog.Branding)?.confirmingRemove != null
    if (removing) {
        SchedulingConfirmDialog(
            title = stringResource(R.string.scheduling_admin_settings_image_remove_title),
            body = stringResource(R.string.scheduling_admin_settings_image_immediate),
            confirmLabel = stringResource(R.string.scheduling_admin_settings_image_remove),
            cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
            onConfirm = @DontMemoize { writes.images.confirmRemove() },
            onCancel = @DontMemoize { writes.images.cancelRemove() },
            tag = SETTINGS_IMAGE_REMOVE_DIALOG_TAG,
            confirmTag = SETTINGS_IMAGE_REMOVE_CONFIRM_TAG,
        )
    }
}

/**
 * The member's own scheduler profile and picture.
 *
 * ⚠️ THE ZONE IS A TEXT FIELD, where district-ios has a picker over the whole IANA list: several
 * hundred buttons are no kinder here, and the name is checked against the device's zone list.
 */
@Composable
private fun ProfileDialog(
    dialog: SettingsDialog.Profile,
    writes: SchedulingSettingsWrites,
    onPickImage: (SchedulingAdminUploadTarget) -> Unit,
) {
    val enabled = !dialog.busy
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_settings_profile_title),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_settings_profile_save),
        cancelLabel = stringResource(R.string.scheduling_admin_settings_close),
        state = dialog.state,
        onConfirm = @DontMemoize { writes.profile.save() },
        onCancel = @DontMemoize { writes.close() },
        tag = SETTINGS_PROFILE_DIALOG_TAG,
        confirmTag = SETTINGS_PROFILE_SAVE_TAG,
        confirmEnabled = enabled,
        onRetry = @DontMemoize { writes.profile.save() },
    ) {
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_settings_profile_name),
            value = dialog.draft.name,
            onValueChange = @DontMemoize { value -> writes.profile.edit { it.copy(name = value) } },
            tag = SETTINGS_PROFILE_NAME_TAG,
            enabled = enabled,
        )
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_settings_profile_timezone),
            value = dialog.draft.timezone,
            onValueChange = @DontMemoize { value -> writes.profile.edit { it.copy(timezone = value) } },
            tag = SETTINGS_PROFILE_TIMEZONE_TAG,
            enabled = enabled,
            hint = stringResource(R.string.scheduling_admin_settings_timezone_hint),
        )
        SchedulingChoiceRow(
            options = SCHEDULING_TIME_FORMATS.map { (value, label) -> value to stringResource(label) },
            selected = dialog.draft.timeFormat,
            onSelect = @DontMemoize { value -> writes.profile.edit { it.copy(timeFormat = value) } },
            tagPrefix = SETTINGS_PROFILE_TIME_FORMAT_TAG,
            enabled = enabled,
            label = stringResource(R.string.scheduling_admin_settings_profile_time_format),
        )
        SchedulingChoiceRow(
            options = (0 until DAYS_IN_WEEK).map { it to schedulingWeekStartLabel(it) },
            selected = dialog.draft.weekStart,
            onSelect = @DontMemoize { value -> writes.profile.edit { it.copy(weekStart = value) } },
            tagPrefix = SETTINGS_PROFILE_WEEK_START_TAG,
            enabled = enabled,
            label = stringResource(R.string.scheduling_admin_settings_profile_week_start),
        )
        SchedulingChoiceRow(
            options = SCHEDULING_DATE_FORMATS.map { (value, label) -> value to stringResource(label) },
            selected = dialog.draft.dateFormat,
            onSelect = @DontMemoize { value -> writes.profile.edit { it.copy(dateFormat = value) } },
            tagPrefix = SETTINGS_PROFILE_DATE_FORMAT_TAG,
            enabled = enabled,
            label = stringResource(R.string.scheduling_admin_settings_profile_date_format),
        )
        SchedulingRejection(dialog.rejected?.let { stringResource(it) })
        SettingsImageRow(
            label = stringResource(R.string.scheduling_admin_settings_avatar),
            url = dialog.me.avatarUrl,
            state = dialog.avatar,
            enabled = enabled,
            target = SchedulingAdminUploadTarget.AVATAR,
            onChoose = onPickImage,
            onRemove = @DontMemoize { target -> writes.images.askRemove(target) },
        )
    }
}

/**
 * The seven email switches, in their two groups. ⚠️ The groups are load-bearing: "Booking
 * cancelled" and "Someone cancelled with you" are one event seen from two sides.
 */
@Composable
private fun NotificationsDialog(dialog: SettingsDialog.Notifications, writes: SchedulingSettingsWrites) {
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_settings_notifications_title),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_settings_notifications_save),
        cancelLabel = stringResource(R.string.scheduling_admin_settings_close),
        state = dialog.state,
        onConfirm = @DontMemoize { writes.notifications.save() },
        onCancel = @DontMemoize { writes.close() },
        tag = SETTINGS_NOTIFICATIONS_DIALOG_TAG,
        confirmTag = SETTINGS_NOTIFICATIONS_SAVE_TAG,
        onRetry = @DontMemoize { writes.notifications.save() },
    ) {
        SchedulingNotifyGroup.entries.forEach { group ->
            Eyebrow(stringResource(group.heading))
            SchedulingNotifySwitch.entries.filter { it.group == group }.forEach { switch ->
                SchedulingToggleRow(
                    label = stringResource(switch.label),
                    checked = dialog.values.getValue(switch),
                    onCheckedChange = @DontMemoize { writes.notifications.toggle(switch, it) },
                    tag = "$SETTINGS_NOTIFY_TAG-${switch.name}",
                    enabled = !dialog.busy,
                )
            }
        }
    }
}

/**
 * One image: whether it is set, the picker, and a Remove behind a confirmation.
 *
 * ⛔ REMOVE ASKS FIRST, though the web's does not: it is an immediate write on a public page, a
 * thumb-width from the picker, and closing the dialog does not undo it.
 */
@Composable
internal fun SettingsImageRow(
    label: String,
    url: String?,
    state: WriteState,
    enabled: Boolean,
    target: SchedulingAdminUploadTarget,
    onChoose: (SchedulingAdminUploadTarget) -> Unit,
    onRemove: (SchedulingAdminUploadTarget) -> Unit,
) {
    val isSet = !url.isNullOrEmpty()
    SchedulingCard(eyebrow = label) {
        SchedulingReadOnlyRow(
            label,
            if (isSet) {
                stringResource(R.string.scheduling_admin_settings_set)
            } else {
                stringResource(R.string.scheduling_admin_common_not_set)
            },
        )
        SchedulingHint(stringResource(R.string.scheduling_admin_settings_image_hint))
        SchedulingHint(stringResource(R.string.scheduling_admin_settings_image_immediate))
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_settings_image_choose),
                onClick = @DontMemoize { onChoose(target) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$SETTINGS_IMAGE_CHOOSE_TAG-${target.wire}"),
            )
            if (isSet) {
                DistrictButton(
                    text = stringResource(R.string.scheduling_admin_settings_image_remove),
                    onClick = @DontMemoize { onRemove(target) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Sm,
                    enabled = enabled,
                    modifier = Modifier.testTag("$SETTINGS_IMAGE_REMOVE_TAG-${target.wire}"),
                )
            }
        }
        SchedulingWriteOutcome(state)
    }
}

const val SETTINGS_PROFILE_DIALOG_TAG: String = "district-scheduling-settings-profile"
const val SETTINGS_PROFILE_SAVE_TAG: String = "district-scheduling-settings-profile-save"
const val SETTINGS_PROFILE_NAME_TAG: String = "district-scheduling-settings-profile-name"
const val SETTINGS_PROFILE_TIMEZONE_TAG: String = "district-scheduling-settings-profile-timezone"
const val SETTINGS_PROFILE_TIME_FORMAT_TAG: String = "district-scheduling-settings-profile-time-format"
const val SETTINGS_PROFILE_WEEK_START_TAG: String = "district-scheduling-settings-profile-week-start"
const val SETTINGS_PROFILE_DATE_FORMAT_TAG: String = "district-scheduling-settings-profile-date-format"
const val SETTINGS_NOTIFICATIONS_DIALOG_TAG: String = "district-scheduling-settings-notifications"
const val SETTINGS_NOTIFICATIONS_SAVE_TAG: String = "district-scheduling-settings-notifications-save"
const val SETTINGS_NOTIFY_TAG: String = "district-scheduling-settings-notify"
const val SETTINGS_IMAGE_CHOOSE_TAG: String = "district-scheduling-settings-image-choose"
const val SETTINGS_IMAGE_REMOVE_TAG: String = "district-scheduling-settings-image-remove"
const val SETTINGS_IMAGE_REMOVE_DIALOG_TAG: String = "district-scheduling-settings-image-remove-dialog"
const val SETTINGS_IMAGE_REMOVE_CONFIRM_TAG: String = "district-scheduling-settings-image-remove-confirm"
