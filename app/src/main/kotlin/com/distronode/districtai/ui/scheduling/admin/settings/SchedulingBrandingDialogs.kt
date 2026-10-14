package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingFieldKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingToggleRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import kotlin.math.roundToInt

/**
 * The public booking page: seven fields, a logo and a banner; district-ios's
 * `SchedulingBrandingSheet`.
 *
 * ⛔ THE SLIDERS ARE CLAMPED TO THE FORK'S RANGES, and the ranges are still checked before the
 * request (a stored value can be outside them). ⚠️ The fallback language is chosen from the
 * server's `supported_locales`; with none, only the hint is shown.
 */
@Composable
internal fun SettingsBrandingDialog(
    dialog: SettingsDialog.Branding,
    writes: SchedulingSettingsWrites,
    onPickImage: (SchedulingAdminUploadTarget) -> Unit,
) {
    val enabled = !dialog.busy
    val draft = dialog.draft
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_settings_branding_title),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_settings_branding_save),
        cancelLabel = stringResource(R.string.scheduling_admin_settings_close),
        state = dialog.state,
        onConfirm = @DontMemoize { writes.branding.save() },
        onCancel = @DontMemoize { writes.close() },
        tag = SETTINGS_BRANDING_DIALOG_TAG,
        confirmTag = SETTINGS_BRANDING_SAVE_TAG,
        confirmEnabled = enabled,
        onRetry = @DontMemoize { writes.branding.save() },
    ) {
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_settings_business_name),
            value = draft.businessName,
            onValueChange = @DontMemoize { value -> writes.branding.edit { it.copy(businessName = value) } },
            tag = SETTINGS_BRANDING_NAME_TAG,
            enabled = enabled,
            hint = stringResource(R.string.scheduling_admin_settings_business_name_hint),
        )
        SettingsSlider(
            label = stringResource(R.string.scheduling_admin_settings_logo_height),
            hint = stringResource(R.string.scheduling_admin_settings_logo_height_hint),
            value = draft.logoHeight,
            range = LOGO_HEIGHT_RANGE,
            enabled = enabled,
            tag = SETTINGS_BRANDING_LOGO_HEIGHT_TAG,
        ) @DontMemoize { value -> writes.branding.edit { it.copy(logoHeight = value) } }
        SettingsSlider(
            label = stringResource(R.string.scheduling_admin_settings_logo_opacity),
            hint = stringResource(R.string.scheduling_admin_settings_opacity_hint),
            value = draft.logoOpacity,
            range = OPACITY_RANGE,
            enabled = enabled,
            tag = SETTINGS_BRANDING_LOGO_OPACITY_TAG,
        ) @DontMemoize { value -> writes.branding.edit { it.copy(logoOpacity = value) } }
        SettingsSlider(
            label = stringResource(R.string.scheduling_admin_settings_banner_opacity),
            hint = stringResource(R.string.scheduling_admin_settings_opacity_hint),
            value = draft.bannerOpacity,
            range = OPACITY_RANGE,
            enabled = enabled,
            tag = SETTINGS_BRANDING_BANNER_OPACITY_TAG,
        ) @DontMemoize { value -> writes.branding.edit { it.copy(bannerOpacity = value) } }
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_settings_privacy_url),
            value = draft.privacyUrl,
            onValueChange = @DontMemoize { value -> writes.branding.edit { it.copy(privacyUrl = value) } },
            tag = SETTINGS_BRANDING_PRIVACY_TAG,
            enabled = enabled,
            kind = SchedulingFieldKind.URL,
        )
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_settings_terms_url),
            value = draft.termsUrl,
            onValueChange = @DontMemoize { value -> writes.branding.edit { it.copy(termsUrl = value) } },
            tag = SETTINGS_BRANDING_TERMS_TAG,
            enabled = enabled,
            hint = stringResource(R.string.scheduling_admin_settings_legal_url_hint),
            kind = SchedulingFieldKind.URL,
        )
        val locales = dialog.branding.supportedLocales.orEmpty()
        if (locales.isNotEmpty()) {
            SchedulingChoiceRow(
                options = locales.map { it.code to it.name.ifEmpty { it.code } },
                selected = draft.fallbackLocale,
                onSelect = @DontMemoize { code -> writes.branding.edit { it.copy(fallbackLocale = code) } },
                tagPrefix = SETTINGS_BRANDING_LOCALE_TAG,
                enabled = enabled,
                label = stringResource(R.string.scheduling_admin_settings_page_language),
            )
        }
        SchedulingHint(stringResource(R.string.scheduling_admin_settings_fallback_locale_hint))
        SchedulingRejection(dialog.rejected?.let { stringResource(it) })
        SettingsImageRow(
            label = stringResource(R.string.scheduling_admin_settings_logo),
            url = dialog.branding.logoUrl,
            state = dialog.logo,
            enabled = enabled,
            target = SchedulingAdminUploadTarget.LOGO,
            onChoose = onPickImage,
            onRemove = @DontMemoize { target -> writes.images.askRemove(target) },
        )
        SettingsImageRow(
            label = stringResource(R.string.scheduling_admin_settings_banner),
            url = dialog.branding.bannerUrl,
            state = dialog.banner,
            enabled = enabled,
            target = SchedulingAdminUploadTarget.BANNER,
            onChoose = onPickImage,
            onRemove = @DontMemoize { target -> writes.images.askRemove(target) },
        )
    }
}

/**
 * The booking assistant: its switch and its extra instructions. ⚠️ Save is offered only when
 * something moved.
 */
@Composable
internal fun SettingsAssistantDialog(dialog: SettingsDialog.Assistant, writes: SchedulingSettingsWrites) {
    val enabled = !dialog.busy
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_settings_assistant_title),
        subtitle = stringResource(R.string.scheduling_admin_settings_assistant_hint),
        confirmLabel = stringResource(R.string.scheduling_admin_settings_assistant_save),
        cancelLabel = stringResource(R.string.scheduling_admin_settings_close),
        state = dialog.state,
        onConfirm = @DontMemoize { writes.assistant.save() },
        onCancel = @DontMemoize { writes.close() },
        tag = SETTINGS_ASSISTANT_DIALOG_TAG,
        confirmTag = SETTINGS_ASSISTANT_SAVE_TAG,
        confirmEnabled = dialog.isDirty,
        onRetry = @DontMemoize { writes.assistant.save() },
    ) {
        SchedulingToggleRow(
            label = stringResource(R.string.scheduling_admin_settings_assistant_toggle),
            checked = dialog.enabled,
            onCheckedChange = @DontMemoize { on -> writes.assistant.editEnabled(on) },
            tag = SETTINGS_ASSISTANT_TOGGLE_TAG,
            enabled = enabled,
        )
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_settings_assistant_instructions_label),
            value = dialog.instructions,
            onValueChange = @DontMemoize { value -> writes.assistant.editInstructions(value) },
            tag = SETTINGS_ASSISTANT_INSTRUCTIONS_TAG,
            enabled = enabled,
            hint = stringResource(R.string.scheduling_admin_settings_assistant_instructions_hint),
            error = if (dialog.tooLong) {
                stringResource(R.string.scheduling_admin_settings_assistant_instructions_too_long)
            } else {
                null
            },
            kind = SchedulingFieldKind.MULTILINE,
        )
    }
}

/** A whole number on a slider, with its value in the caption. */
@Composable
private fun SettingsSlider(
    label: String,
    hint: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    tag: String,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.scheduling_admin_settings_slider_value, label, value),
            style = MaterialTheme.typography.labelMedium,
            color = DistrictTheme.colors.foreground,
        )
        Slider(
            value = value.coerceIn(range).toFloat(),
            onValueChange = @DontMemoize { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            enabled = enabled,
            modifier = Modifier
                .testTag(tag)
                .semantics { contentDescription = label },
        )
        SchedulingHint(hint)
    }
}

const val SETTINGS_BRANDING_DIALOG_TAG: String = "district-scheduling-settings-branding"
const val SETTINGS_BRANDING_SAVE_TAG: String = "district-scheduling-settings-branding-save"
const val SETTINGS_BRANDING_NAME_TAG: String = "district-scheduling-settings-branding-name"
const val SETTINGS_BRANDING_LOGO_HEIGHT_TAG: String = "district-scheduling-settings-branding-logo-height"
const val SETTINGS_BRANDING_LOGO_OPACITY_TAG: String = "district-scheduling-settings-branding-logo-opacity"
const val SETTINGS_BRANDING_BANNER_OPACITY_TAG: String = "district-scheduling-settings-branding-banner-opacity"
const val SETTINGS_BRANDING_PRIVACY_TAG: String = "district-scheduling-settings-branding-privacy"
const val SETTINGS_BRANDING_TERMS_TAG: String = "district-scheduling-settings-branding-terms"
const val SETTINGS_BRANDING_LOCALE_TAG: String = "district-scheduling-settings-branding-locale"
const val SETTINGS_ASSISTANT_DIALOG_TAG: String = "district-scheduling-settings-assistant"
const val SETTINGS_ASSISTANT_SAVE_TAG: String = "district-scheduling-settings-assistant-save"
const val SETTINGS_ASSISTANT_TOGGLE_TAG: String = "district-scheduling-settings-assistant-toggle"
const val SETTINGS_ASSISTANT_INSTRUCTIONS_TAG: String = "district-scheduling-settings-assistant-instructions"
