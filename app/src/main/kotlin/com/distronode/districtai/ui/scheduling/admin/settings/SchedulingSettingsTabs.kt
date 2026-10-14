package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SectionState

/**
 * The booking page as published. ⛔ The editor is drawn only from a loaded row: its save replaces
 * every field.
 */
@Composable
internal fun SettingsBrandingTab(
    state: SchedulingSettingsUiState,
    writes: SchedulingSettingsWrites,
    onRetry: () -> Unit,
) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_settings_tab_booking_page)) {
        SettingsRead(state.branding, onRetry) { branding ->
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_business_name),
                branding.businessName.ifEmpty { stringResource(R.string.scheduling_admin_common_not_set) },
            )
            SchedulingReadOnlyRow(stringResource(R.string.scheduling_admin_settings_logo), setOrNot(branding.logoUrl))
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_banner),
                setOrNot(branding.bannerUrl),
            )
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_privacy_url),
                branding.privacyUrl.ifEmpty { stringResource(R.string.scheduling_admin_common_not_set) },
            )
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_terms_url),
                branding.termsUrl.ifEmpty { stringResource(R.string.scheduling_admin_common_not_set) },
            )
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_page_language),
                schedulingLocaleLabel(branding.fallbackLocale, branding.supportedLocales),
            )
            if (state.canManage) {
                EditButton(R.string.scheduling_admin_settings_branding_edit, SETTINGS_BRANDING_EDIT_TAG) @DontMemoize {
                    writes.branding.open(branding)
                }
            }
        }
    }
}

@Composable
internal fun SettingsAssistantTab(
    state: SchedulingSettingsUiState,
    writes: SchedulingSettingsWrites,
    onRetry: () -> Unit,
) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_settings_tab_assistant)) {
        SettingsRead(state.assistant, onRetry) { llm ->
            SchedulingHint(stringResource(R.string.scheduling_admin_settings_assistant_hint))
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_assistant_enabled),
                onOff(llm.enabled),
            )
            if (llm.extraInstructions.isNotEmpty()) {
                SchedulingReadOnlyRow(
                    stringResource(R.string.scheduling_admin_settings_assistant_instructions),
                    llm.extraInstructions,
                )
            }
            if (state.canManage) {
                EditButton(
                    R.string.scheduling_admin_settings_assistant_edit,
                    SETTINGS_ASSISTANT_EDIT_TAG,
                ) @DontMemoize {
                    writes.assistant.open(llm)
                }
            }
        }
    }
}

/** ⛔ The edit button is ungated: `me.patch` and the avatar are `viewer`-level. */
@Composable
internal fun SettingsProfileTab(
    state: SchedulingSettingsUiState,
    writes: SchedulingSettingsWrites,
    onRetry: () -> Unit,
) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_settings_tab_profile)) {
        SettingsRead(state.profile, onRetry) { me ->
            SchedulingReadOnlyRow(stringResource(R.string.scheduling_admin_settings_profile_name), me.name)
            SchedulingReadOnlyRow(stringResource(R.string.scheduling_admin_settings_profile_timezone), me.timezone)
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_profile_time_format),
                schedulingTimeFormatLabel(me.timeFormat),
            )
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_profile_week_start),
                schedulingWeekStartLabel(me.weekStart),
            )
            SchedulingReadOnlyRow(
                stringResource(R.string.scheduling_admin_settings_profile_date_format),
                schedulingDateFormatLabel(me.dateFormat),
            )
            SchedulingReadOnlyRow(stringResource(R.string.scheduling_admin_settings_avatar), setOrNot(me.avatarUrl))
            EditButton(R.string.scheduling_admin_settings_profile_edit, SETTINGS_PROFILE_EDIT_TAG) @DontMemoize {
                writes.profile.open(me)
            }
        }
    }
}

/** The seven switches in their two groups. ⛔ Ungated, for the profile's reason. */
@Composable
internal fun SettingsNotificationsTab(
    state: SchedulingSettingsUiState,
    writes: SchedulingSettingsWrites,
    onRetry: () -> Unit,
) {
    SettingsRead(state.profile, onRetry) { me ->
        SchedulingNotifyGroup.entries.forEach { group ->
            SchedulingCard(eyebrow = stringResource(group.heading)) {
                SchedulingHint(stringResource(group.description))
                SchedulingNotifySwitch.entries.filter { it.group == group }.forEach { switch ->
                    SchedulingReadOnlyRow(stringResource(switch.label), onOff(switch.read(me)))
                }
            }
        }
        EditButton(
            R.string.scheduling_admin_settings_notifications_edit,
            SETTINGS_NOTIFICATIONS_EDIT_TAG,
        ) @DontMemoize {
            writes.notifications.open(me)
        }
    }
}

/** One tab's read: loading (also before the first read), failed with Retry, or its value. */
@Composable
private fun <T> SettingsRead(read: SectionState<T>?, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (read) {
        is SectionState.Ready -> content(read.value)
        is SectionState.Failed -> SchedulingSectionFailure(read.failure, onRetry, SETTINGS_FAILURE_TAG)
        // ⚠️ Not yet read (null) or on its way: both draw as loading.
        else -> SchedulingSectionLoading(SETTINGS_LOADING_TAG)
    }
}

@Composable
private fun EditButton(label: Int, tag: String, onClick: () -> Unit) {
    DistrictButton(text = stringResource(label), onClick = onClick, modifier = Modifier.testTag(tag))
}

/** ⚠️ An image is "Set" or "Not set", never its address: the address is the server's storage key. */
@Composable
private fun setOrNot(url: String?): String = if (url.isNullOrEmpty()) {
    stringResource(R.string.scheduling_admin_common_not_set)
} else {
    stringResource(R.string.scheduling_admin_settings_set)
}

@Composable
private fun onOff(on: Boolean): String = if (on) {
    stringResource(R.string.scheduling_admin_settings_on)
} else {
    stringResource(R.string.scheduling_admin_settings_off)
}

const val SETTINGS_LOADING_TAG: String = "district-scheduling-settings-loading"
const val SETTINGS_FAILURE_TAG: String = "district-scheduling-settings-failure"
const val SETTINGS_BRANDING_EDIT_TAG: String = "district-scheduling-settings-branding-edit"
const val SETTINGS_ASSISTANT_EDIT_TAG: String = "district-scheduling-settings-assistant-edit"
const val SETTINGS_PROFILE_EDIT_TAG: String = "district-scheduling-settings-profile-edit"
const val SETTINGS_NOTIFICATIONS_EDIT_TAG: String = "district-scheduling-settings-notifications-edit"
